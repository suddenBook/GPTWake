package com.desmond.gptwake;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import java.text.Normalizer;
import java.util.Locale;

/** Japanese spelling/reading equivalence. Deliberately does no fuzzy or substring matching. */
public final class JapaneseText {
    private static final class Dictionary {
        static final Tokenizer TOKENIZER = new Tokenizer();
    }

    public static void load() {
        // Resolve dictionary resources off the UI and capture threads.
        Dictionary.TOKENIZER.tokenize("日本語");
    }

    public static String normalizeInput(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    public static boolean isKana(int c) {
        return (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヶ') || c == 'ー';
    }

    public static boolean isHan(int c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    public static boolean isPhraseCharacter(int c) {
        return isKana(c) || isHan(c) || c == ' ' || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** Ignore ASR punctuation and spacing, including models that insert spaces between kana. */
    static String surface(String text) {
        String normalized = normalizeInput(text).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        normalized.codePoints().filter(c -> Character.isLetterOrDigit(c) || c == 'ー')
                .forEach(c -> result.appendCodePoint(c >= 'ァ' && c <= 'ヶ' ? c - 0x60 : c));
        return result.toString();
    }

    public static String reading(String text) {
        String compact = surface(text)
                .replace("chatgpt", "ちゃっとじーぴーてぃー")
                .replace("gpt", "じーぴーてぃー");
        StringBuilder result = new StringBuilder();
        for (Token token : Dictionary.TOKENIZER.tokenize(compact)) {
            String reading = token.getReading();
            result.append(reading == null || reading.equals("*") ? token.getSurface() : reading);
        }
        return kana(result.toString());
    }

    /** Fold scripts and explicit long vowels; small kana, geminates and voicing remain distinct. */
    public static String kana(String text) {
        StringBuilder result = new StringBuilder();
        for (int c : surface(text).codePoints().toArray()) {
            if (c == 'ー' && result.length() > 0) {
                char previous = result.charAt(result.length() - 1);
                if ("ぁあかがさざただなはばぱまゃやらゎわ".indexOf(previous) >= 0) c = 'あ';
                else if ("ぃいきぎしじちぢにひびぴみりゐ".indexOf(previous) >= 0) c = 'い';
                else if ("ぅうくぐすずつづぬふぶぷむゅゆるゔ".indexOf(previous) >= 0) c = 'う';
                else if ("ぇえけげせぜてでねへべぺめれゑ".indexOf(previous) >= 0) c = 'え';
                else if ("ぉおこごそぞとどのほぼぽもょよろを".indexOf(previous) >= 0) c = 'う';
            }
            result.appendCodePoint(c);
        }
        return result.toString();
    }

    public static int moraCount(String reading) {
        return (int) reading.codePoints()
                .filter(c -> isKana(c) && "ぁぃぅぇぉゃゅょゎ".indexOf(c) < 0).count();
    }

    public static boolean matches(String transcript, String phrase, String expectedReading) {
        if (transcript == null || surface(transcript).isEmpty()) return false;
        return surface(transcript).equals(surface(phrase))
                || (!expectedReading.isEmpty() && reading(transcript).equals(expectedReading));
    }

    private JapaneseText() {}
}
