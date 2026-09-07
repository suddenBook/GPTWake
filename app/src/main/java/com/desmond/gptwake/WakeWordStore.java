package com.desmond.gptwake;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted wake phrase, kept in device-protected storage so it survives Direct Boot. */
public final class WakeWordStore {

    public static final String DEFAULT_PHRASE = "芝麻开门";
    /** Matches assets/kws/keywords.txt, used when nothing custom is stored. */
    public static final String DEFAULT_LINE = "zh ī m á k āi m én @芝麻开门";

    private static SharedPreferences sp(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("wakeword", Context.MODE_PRIVATE);
    }

    public static String phrase(Context c) {
        return sp(c).getString("phrase", DEFAULT_PHRASE);
    }

    public static String keywordLine(Context c) {
        return sp(c).getString("line", DEFAULT_LINE);
    }

    public static void save(Context c, String phrase, String line) {
        sp(c).edit().putString("phrase", phrase).putString("line", line).commit();
        L.i("WAKEWORD_SAVED phrase=" + phrase + " line=" + line);
    }

    public static void reset(Context c) {
        sp(c).edit().remove("phrase").remove("line").commit();
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
