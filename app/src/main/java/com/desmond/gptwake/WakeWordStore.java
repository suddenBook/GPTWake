package com.desmond.gptwake;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Persisted wake phrase, kept in device-protected storage so it survives Direct Boot. */
public final class WakeWordStore {

    public static final String DEFAULT_PHRASE = "芝麻开门";
    /** Matches assets/kws/keywords.txt, used when nothing custom is stored. */
    public static final String DEFAULT_LINE = "zh ī m á k āi m én @芝麻开门";

    private static SharedPreferences sp(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("wakeword", Context.MODE_PRIVATE);
    }

    /** One preference snapshot prevents a language change from mixing old and new settings. */
    public static final class Selection {
        public final WakeLanguage language;
        public final String phrase;
        public final String keywordLine;
        public final String japaneseReading;

        Selection(WakeLanguage language, String phrase, String keywordLine, String japaneseReading) {
            this.language = language;
            this.phrase = phrase;
            this.keywordLine = keywordLine;
            this.japaneseReading = japaneseReading;
        }
    }

    public static Selection read(Context c) {
        Map<String, ?> values = sp(c).getAll();
        return new Selection(WakeLanguage.fromId(string(values, "language", "zh-en")),
                string(values, "phrase", DEFAULT_PHRASE), string(values, "line", DEFAULT_LINE),
                string(values, "japanese_reading", ""));
    }

    private static String string(Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static WakeLanguage language(Context c) {
        return read(c).language;
    }

    public static String phrase(Context c) {
        return sp(c).getString("phrase", DEFAULT_PHRASE);
    }

    public static String keywordLine(Context c) {
        return sp(c).getString("line", DEFAULT_LINE);
    }

    public static void save(Context c, String phrase, String line) {
        sp(c).edit().putString("phrase", phrase).putString("line", line)
                .putString("language", WakeLanguage.ZH_EN.id).remove("japanese_reading").commit();
        L.i("WAKEWORD_SAVED phrase=" + phrase + " line=" + line);
    }

    public static void save(Context c, String phrase, WakeLanguage language,
                            WakeWordTokenizer.Result result) {
        if (!result.ok) throw new IllegalArgumentException("Cannot save an invalid wake phrase");
        if (language == WakeLanguage.ZH_EN) {
            save(c, phrase, result.keywordLine);
            return;
        }
        sp(c).edit().putString("phrase", JapaneseText.normalizeInput(phrase))
                .putString("language", language.id).putString("japanese_reading", result.readable)
                .remove("line").commit();
        L.i("WAKEWORD_SAVED language=ja phrase=" + phrase + " reading=" + result.readable);
    }

    public static void reset(Context c) {
        sp(c).edit().remove("phrase").remove("line").remove("language")
                .remove("japanese_reading").commit();
        L.i("WAKEWORD_RESET");
    }

    public static float threshold(Context c) {
        float value = sp(c).getFloat("threshold", KwsEngine.DEFAULT_THRESHOLD);
        return Float.isFinite(value) && value >= 0f && value <= 1f
                ? value : KwsEngine.DEFAULT_THRESHOLD;
    }

    public static void saveThreshold(Context c, float value) {
        if (!Float.isFinite(value) || value < 0f || value > 1f) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1");
        }
        sp(c).edit().putFloat("threshold", value).apply();
    }

    private WakeWordStore() {
    }
}
