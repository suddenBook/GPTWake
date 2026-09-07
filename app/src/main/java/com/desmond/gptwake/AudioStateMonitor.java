package com.desmond.gptwake;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Composite voice-session monitor: AudioManager mode plus the active recording configurations.
 * Replaces the mode-only watcher, because MODE_IN_COMMUNICATION alone is set even when the
 * launched app is denied the microphone under a keyguard.
 */
public final class AudioStateMonitor {

    public interface Listener {
        void onAudioStateChanged(String why);
    }

    private static volatile Listener extListener;

    public static synchronized void setListener(Listener l) {
        extListener = l;
    }

    public static synchronized void clearListener(Listener l) {
        if (extListener == l) extListener = null;
    }

    private static void notifyListener(String why) {
        Listener l = extListener;
        if (l != null) {
            try {
                l.onAudioStateChanged(why);
            } catch (Throwable t) {
                L.e("AUDIO_LISTENER_FAIL", t);
            }
        }
    }

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger LAST_MODE = new AtomicInteger(AudioManager.MODE_NORMAL);
    private static volatile AudioManager am;

    public static String modeName(int m) {
        switch (m) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case AudioManager.MODE_CALL_SCREENING: return "CALL_SCREENING";
            default: return "MODE_" + m;
        }
    }

    public static String sourceName(int s) {
        switch (s) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_UPLINK: return "VOICE_UPLINK";
            case MediaRecorder.AudioSource.VOICE_DOWNLINK: return "VOICE_DOWNLINK";
            case MediaRecorder.AudioSource.VOICE_CALL: return "VOICE_CALL";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_PERFORMANCE: return "VOICE_PERFORMANCE";
            default: return "SRC_" + s;
        }
    }

    public static void install(Context c) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            am = c.getApplicationContext().getSystemService(AudioManager.class);
            LAST_MODE.set(am.getMode());
            L.i("AUDIO_MODE_INITIAL=" + modeName(LAST_MODE.get()));

            am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), mode -> {
                LAST_MODE.set(mode);
                L.i("AUDIO_MODE_CHANGED=" + modeName(mode)
                        + " voiceConfirmed=" + isVoiceConfirmed());
                dumpConfigs("modeChange");
                notifyListener("modeChange");
            });

            am.registerAudioRecordingCallback(
                    new AudioManager.AudioRecordingCallback() {
                        @Override
                        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> cfgs) {
                            logConfigs("callback", cfgs);
                            notifyListener("recordingConfig");
                        }
                    }, null);

            L.i("AUDIO_STATE_MONITOR_OK");
            dumpConfigs("install");
        } catch (Throwable t) {
            L.e("AUDIO_STATE_MONITOR_FAIL", t);
        }
    }

    public static int mode() {
        try {
            return am != null ? am.getMode() : LAST_MODE.get();
        } catch (Throwable t) {
            return LAST_MODE.get();
        }
    }

    public static List<AudioRecordingConfiguration> configs() {
        try {
            return am != null ? am.getActiveRecordingConfigurations() : null;
        } catch (Throwable t) {
            L.e("GET_CONFIGS_FAIL", t);
            return null;
        }
    }

    /** True when a non-silenced communication-class capture exists. */
    public static boolean hasRealCommunicationCapture() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            int src = c.getClientAudioSource();
            boolean comm = src == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    || src == MediaRecorder.AudioSource.VOICE_CALL;
            if (comm && !c.isClientSilenced()) return true;
        }
        return false;
    }

    public static boolean isCommunicationMode() {
        int m = mode();
        return m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_IN_CALL;
    }

    public static boolean isVoiceConfirmed() {
        return isCommunicationMode() && hasRealCommunicationCapture();
    }

    /** True when our own VOICE_RECOGNITION capture is visible and not silenced. */
    public static boolean hasOwnLiveCapture() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            if (isOwnCapture(c)
                    && !c.isClientSilenced()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOwnCaptureConfig() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            if (isOwnCapture(c)) return true;
        }
        return false;
    }

    public static String ownCaptureState() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return "configs=null";
        for (AudioRecordingConfiguration c : cfgs) {
            if (isOwnCapture(c)) {
                return "silenced=" + c.isClientSilenced();
            }
        }
        return "ownConfigAbsent(n=" + cfgs.size() + ")";
    }

    public static void dumpConfigs(String why) {
        logConfigs(why, configs());
    }

    private static boolean isOwnCapture(AudioRecordingConfiguration config) {
        return AudioProbe.audioSessionId() > 0
                && config.getClientAudioSessionId() == AudioProbe.audioSessionId()
                && config.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    private static void logConfigs(String why, List<AudioRecordingConfiguration> cfgs) {
        if (cfgs == null) {
            L.i("CONFIGS why=" + why + " -> null");
            return;
        }
        StringBuilder sb = new StringBuilder("CONFIGS why=" + why + " n=" + cfgs.size());
        for (AudioRecordingConfiguration c : cfgs) {
            sb.append(" | src=").append(sourceName(c.getClientAudioSource()))
              .append(" silenced=").append(c.isClientSilenced())
              .append(" sess=").append(c.getClientAudioSessionId())
              .append(" fmtRate=").append(c.getFormat().getSampleRate());
            try {
                if (c.getAudioDevice() != null) sb.append(" devType=").append(c.getAudioDevice().getType());
            } catch (Throwable ignored) {
            }
        }
        L.i(sb.toString());
    }
}
