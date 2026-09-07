package com.desmond.gptwake;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String MODE_DIRECT_VIS_RECORD = "DIRECT_VIS_RECORD";
    public static final String MODE_MIC_FGS_FROM_VIS = "MIC_FGS_FROM_VIS";
    public static final String MODE_OFF = "OFF";

    private static SharedPreferences sp(Context c) {
        Context de = c.createDeviceProtectedStorageContext();
        return de.getSharedPreferences("probe", Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        return sp(c).getString("mode", MODE_DIRECT_VIS_RECORD);
    }

    public static void setMode(Context c, String m) {
        sp(c).edit().putString("mode", m).commit();
    }

    public static boolean listeningEnabled(Context c) {
        return sp(c).getBoolean("listening_enabled", false);
    }

    public static void setListeningEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean("listening_enabled", enabled).apply();
    }
}
