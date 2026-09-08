package com.desmond.gptwake;

/** Recognition language is independent of the Android display language. */
public enum WakeLanguage {
    ZH_EN("zh-en"), JAPANESE("ja");

    public final String id;

    WakeLanguage(String id) {
        this.id = id;
    }

    public static WakeLanguage fromId(String id) {
        return "ja".equals(id) ? JAPANESE : ZH_EN;
    }
}
