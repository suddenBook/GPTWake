package com.desmond.gptwake;

import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts a human-typed wake phrase into the phone/pinyin token sequence the KWS model expects,
 * entirely on device. Mirrors what `sherpa-onnx-cli text2token --tokens-type phone+ppinyin` does:
 * Chinese characters become initial + toned final, English words become CMU phones.
 */
public final class WakeWordTokenizer {

    /**
     * Why a phrase was rejected. The tokenizer deliberately reports a code rather than a message so
     * that it needs no Context and stays localisable — the UI maps these onto string resources.
     */
    public enum Err {
        NONE,
        DICT_NOT_LOADED,
        EMPTY,
        /** {@link Result#errArg} is the offending character. */
        UNSUPPORTED_HAN,
        /** {@link Result#errArg} is the unknown word. */
        UNKNOWN_ENGLISH,
        /** {@link Result#errArg} is the offending character. */
        UNSUPPORTED_CHAR,
        UNPARSEABLE,
        /** {@link Result#errArg} is the offending model token. */
        UNSUPPORTED_PHONE,
        /** Not fatal: the phrase works but {@link Result#errCount} syllables is short enough to
         *  invite false wakes. */
        TOO_SHORT,
    }

    public static final class Result {
        public final boolean ok;
        public final String tokens;      // space separated model tokens
        public final String readable;    // pinyin / phones for display
        public final String keywordLine; // ready for KeywordSpotter.createStream()
        public final Err err;
        public final String errArg;      // substitution for the error message, may be null
        public final int errCount;       // syllable count, only meaningful for TOO_SHORT

        Result(boolean ok, String tokens, String readable, String keywordLine,
               Err err, String errArg, int errCount) {
            this.ok = ok;
            this.tokens = tokens;
            this.readable = readable;
            this.keywordLine = keywordLine;
            this.err = err;
            this.errArg = errArg;
            this.errCount = errCount;
        }

        public boolean hasMessage() {
            return err != Err.NONE;
        }

        static Result fail(Err err, String arg) {
            return new Result(false, "", "", "", err, arg, 0);
        }
    }

    private final Map<Character, String[]> han = new HashMap<>(32768);
    private final Map<String, String> english = new HashMap<>(160000);
    private final Set<String> valid = new HashSet<>(512);
    private volatile boolean loaded;

    public boolean isLoaded() {
        return loaded;
    }

    public synchronized void load(AssetManager am) throws Exception {
        if (loaded) return;
        long t0 = System.currentTimeMillis();

        try (BufferedReader r = reader(am, "kws/tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                if (sp > 0) valid.add(line.substring(0, sp));
            }
        }

        try (BufferedReader r = reader(am, "kws/pinyin_tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length >= 3 && f[0].length() == 1) {
                    han.put(f[0].charAt(0), new String[]{f[1], f[2]});
                }
            }
        }

        try (BufferedReader r = reader(am, "kws/en.phone")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) english.put(line.substring(0, sp).toUpperCase(Locale.US),
                        line.substring(sp + 1).trim());
            }
        }

        loaded = true;
        L.i("TOKENIZER_READY han=" + han.size() + " english=" + english.size()
                + " modelTokens=" + valid.size() + " loadMs=" + (System.currentTimeMillis() - t0));
    }

    private static BufferedReader reader(AssetManager am, String path) throws Exception {
        return new BufferedReader(new InputStreamReader(am.open(path), StandardCharsets.UTF_8), 1 << 16);
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isEnglishLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    public Result convert(String phrase) {
        if (!loaded) return Result.fail(Err.DICT_NOT_LOADED, null);
        if (phrase == null) return Result.fail(Err.EMPTY, null);

        String p = phrase.trim().replaceAll("\\s+", " ");
        if (p.isEmpty()) return Result.fail(Err.EMPTY, null);

        List<String> tokens = new ArrayList<>();
        List<String> readable = new ArrayList<>();
        int syllables = 0;

        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if (isHan(c)) {
                String[] e = han.get(c);
                if (e == null) {
                    return Result.fail(Err.UNSUPPORTED_HAN, String.valueOf(c));
                }
                for (String t : e[0].split(" ")) tokens.add(t);
                readable.add(e[1]);
                syllables++;
                i++;
                continue;
            }
            if (isEnglishLetter(c) || c == '\'') {
                int j = i;
                while (j < p.length()
                        && (isEnglishLetter(p.charAt(j)) || p.charAt(j) == '\'')) j++;
                String word = p.substring(i, j).toUpperCase(Locale.US);
                String phones = english.get(word);
                if (phones == null) {
                    return Result.fail(Err.UNKNOWN_ENGLISH, word);
                }
                for (String t : phones.split("\\s+")) {
                    tokens.add(t);
                    // CMU vowel phones carry a stress digit; each represents one syllable.
                    char stress = t.charAt(t.length() - 1);
                    if (stress >= '0' && stress <= '2') syllables++;
                }
                readable.add(phones);
                i = j;
                continue;
            }
            return Result.fail(Err.UNSUPPORTED_CHAR, String.valueOf(c));
        }

        if (tokens.isEmpty()) return Result.fail(Err.UNPARSEABLE, null);

        for (String t : tokens) {
            if (!valid.contains(t)) {
                return Result.fail(Err.UNSUPPORTED_PHONE, t);
            }
        }

        String tok = String.join(" ", tokens);
        String display = String.join(" ", readable);
        String line = tok + " @" + p.replace(' ', '_');

        if (syllables < 3) {
            return new Result(true, tok, display, line, Err.TOO_SHORT, null, syllables);
        }
        return new Result(true, tok, display, line, Err.NONE, null, syllables);
    }

    /** Rough syllable estimate, used by the UI to warn about over-short phrases. */
    public static int estimateSyllables(String phrase) {
        int n = 0;
        for (char c : phrase.toCharArray()) if (isHan(c)) n++;
        return n;
    }
}
