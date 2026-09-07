package com.desmond.gptwake;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 16 kHz mono capture loop. Buffers are preallocated once and reused; short reads are
 * accumulated into whole 80 ms frames before anything is handed to the keyword spotter.
 */
public final class AudioProbe {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 1280;   // 80 ms

    public interface WakeListener {
        void onKeyword(String keyword);
        void onFirstFrame();
        void onCaptureError(String reason);
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean RECORDING = new AtomicBoolean(false);
    private static final AtomicBoolean FEEDING = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile AudioRecord activeRecord;
    private static volatile int audioSessionId = -1;
    private static volatile String lastResult = "none";
    private static volatile KwsEngine engine;
    private static volatile WakeListener listener;
    private static volatile long captureThreadCpuMs = -1;
    private static volatile double lastRms = 0;

    public static double lastRms() {
        return lastRms;
    }

    public static long captureThreadCpuMs() {
        return captureThreadCpuMs;
    }

    public static boolean isRunning() {
        return RUNNING.get() && RECORDING.get();
    }

    static int audioSessionId() {
        return audioSessionId;
    }

    public static String lastResult() {
        return lastResult;
    }

    public static void bind(KwsEngine e, WakeListener l) {
        engine = e;
        listener = l;
    }

    /** Stops handing frames to the spotter without tearing down the capture loop. */
    public static void setFeeding(boolean on) {
        FEEDING.set(on);
    }

    public static synchronized void start(String who) {
        if (worker != null && worker.isAlive()) {
            L.i("AUDIO_ALREADY_RUNNING who=" + who);
            return;
        }
        RUNNING.set(true);
        RECORDING.set(false);
        worker = new Thread(() -> {
            ThreadCpu.publish("audio-capture");
            loop(who);
        }, "kws-capture");
        worker.setPriority(Thread.MAX_PRIORITY - 1);
        worker.start();
    }

    public static synchronized void stop() {
        RUNNING.set(false);
        RECORDING.set(false);
        FEEDING.set(false);
        AudioRecord record = activeRecord;
        if (record != null) {
            try {
                record.stop(); // Unblock read() before waiting for the capture thread.
            } catch (IllegalStateException e) {
                L.e("AUDIO_STOP_RECORD_FAIL", e);
            }
        }
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
                L.i("AUDIO_JOIN_OK alive=" + t.isAlive());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (t == null || !t.isAlive()) {
            worker = null;
            L.i("AUDIO_STOPPED");
        } else {
            L.e("AUDIO_STOP_TIMEOUT", null);
        }
    }

    private static double pct(double[] sorted, int cnt, double q) {
        if (cnt <= 0) return 0;
        int i = (int) Math.ceil(q * cnt) - 1;
        if (i < 0) i = 0;
        if (i >= cnt) i = cnt - 1;
        return sorted[i];
    }

    private static void loop(String who) {
        AudioRecord ar = null;
        final WakeListener captureListener = listener;
        final short[] pcm = new short[FRAME_SAMPLES];
        final float[] frame = new float[FRAME_SAMPLES];
        final short[] acc = new short[FRAME_SAMPLES];
        int accLen = 0;

        long frames = 0, droppedFrames = 0, decodes = 0;
        double decodeSum = 0, decodeMax = 0;
        final double[] decodeSamples = new double[2048];
        int decodeIdx = 0;
        long statWindow = System.currentTimeMillis();
        long statCpuMs = android.os.Process.getElapsedCpuTime();
        long statThreadCpuNs = android.os.Debug.threadCpuTimeNanos();
        long statFrames = 0;
        boolean firstFrameSent = false;

        try {
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                lastResult = "MIN_BUFFER_BAD";
                L.i("AUDIO_MIN_BUFFER_BAD=" + min + " who=" + who);
                return;
            }

            ar = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, FRAME_SAMPLES * 2 * 8))
                    .build();

            activeRecord = ar;
            audioSessionId = ar.getAudioSessionId();

            L.i("AUDIO_BUILT who=" + who + " state=" + ar.getState());
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                lastResult = "INIT_FAIL";
                L.i("AUDIO_INIT_FAIL state=" + ar.getState() + " who=" + who);
                return;
            }

            if (!RUNNING.get()) return;
            ar.startRecording();
            int rs = ar.getRecordingState();
            L.i("AUDIO_STARTRECORDING who=" + who + " recordingState="
                    + (rs == AudioRecord.RECORDSTATE_RECORDING ? "RECORDSTATE_RECORDING" : rs));
            if (rs != AudioRecord.RECORDSTATE_RECORDING) {
                lastResult = "START_FAIL";
                L.i("AUDIO_START_FAIL recState=" + rs + " who=" + who);
                return;
            }
            L.i("DIRECT_AUDIORECORD_OK who=" + who);

            double rms = 0;
            while (RUNNING.get()) {
                int want = FRAME_SAMPLES - accLen;
                int n = ar.read(pcm, 0, want);
                if (n < 0) {
                    lastResult = "READ_ERR" + n;
                    L.i("AUDIO_READ_ERR n=" + n + " who=" + who);
                    break;
                }
                if (n == 0) continue;
                if (!RUNNING.get()) break;

                System.arraycopy(pcm, 0, acc, accLen, n);
                accLen += n;
                if (accLen < FRAME_SAMPLES) continue;   // wait for a whole frame
                accLen = 0;
                frames++;

                double sq = 0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    short s = acc[i];
                    frame[i] = s / 32768.0f;
                    sq += (double) s * s;
                }
                rms = Math.sqrt(sq / FRAME_SAMPLES);
                lastRms = rms;

                if (!firstFrameSent) {
                    firstFrameSent = true;
                    RECORDING.set(true);
                    L.i("KWS_AUDIO_FIRST_FRAME rms=" + String.format(Locale.ROOT, "%.1f", rms) + " who=" + who);
                    WakeListener l = captureListener;
                    if (l != null) l.onFirstFrame();
                }

                KwsEngine e = engine;
                if (e != null && e.isLoaded() && FEEDING.get() && !Cfg.micOnly) {
                    long d0 = System.nanoTime();
                    String hit = e.accept(frame, SAMPLE_RATE);
                    double ms = (System.nanoTime() - d0) / 1e6;
                    decodes++;
                    decodeSum += ms;
                    if (ms > decodeMax) decodeMax = ms;
                    decodeSamples[decodeIdx++ % decodeSamples.length] = ms;
                    if (ms > 80) droppedFrames++;   // slower than real time

                    if (hit != null) {
                        L.i("KWS_HIT keyword=" + hit + " timestamp=" + System.currentTimeMillis()
                                + " rms=" + String.format(Locale.ROOT, "%.1f", rms));
                        FEEDING.set(false);         // stop feeding immediately
                        WakeListener l = captureListener;
                        if (l != null) l.onKeyword(hit);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - statWindow >= 60_000) {
                    long wallDelta = now - statWindow;
                    long cpuNow = android.os.Process.getElapsedCpuTime();
                    long threadCpuNow = android.os.Debug.threadCpuTimeNanos();
                    long cpuDelta = cpuNow - statCpuMs;
                    long threadCpuDeltaMs = (threadCpuNow - statThreadCpuNs) / 1_000_000L;
                    captureThreadCpuMs = threadCpuNow / 1_000_000L;
                    ThreadCpu.publish("audio-capture");

                    int cnt = (int) Math.min(decodeIdx, decodeSamples.length);
                    double[] copy = Arrays.copyOf(decodeSamples, cnt);
                    Arrays.sort(copy);
                    double p50 = pct(copy, cnt, 0.50);
                    double p95 = pct(copy, cnt, 0.95);
                    double p99 = pct(copy, cnt, 0.99);

                    L.i(String.format(Locale.ROOT,
                            "KWS_STATS mode=%s frames=%d framesDelta=%d decodes=%d "
                                    + "decodeAvgMs=%.2f decodeP50Ms=%.2f decodeP95Ms=%.2f decodeP99Ms=%.2f maxDecodeMs=%.2f "
                                    + "droppedFrames=%d rms=%.1f %s "
                                    + "wallDeltaMs=%d cpuDeltaMs=%d cpuOneCorePct=%.2f "
                                    + "captureThreadCpuDeltaMs=%d captureThreadPct=%.2f rssKb=%d",
                            Cfg.micOnly ? "MIC_ONLY_MODEL_LOADED" : (Cfg.evalMode ? "KWS_EVAL" : "KWS_FULL"),
                            frames, frames - statFrames, decodes,
                            decodes > 0 ? decodeSum / decodes : 0, p50, p95, p99, decodeMax,
                            droppedFrames, rms, AudioStateMonitor.ownCaptureState(),
                            wallDelta, cpuDelta, wallDelta > 0 ? cpuDelta * 100.0 / wallDelta : 0,
                            threadCpuDeltaMs, wallDelta > 0 ? threadCpuDeltaMs * 100.0 / wallDelta : 0,
                            Sys.rssKb()));

                    Measure.event("KWS_STATS", "\"mode\":\"" + (Cfg.micOnly
                            ? "MIC_ONLY_MODEL_LOADED" : (Cfg.evalMode ? "KWS_EVAL" : "KWS_FULL"))
                            + "\",\"framesDelta\":" + (frames - statFrames)
                            + ",\"decodes\":" + decodes
                            + ",\"acceptCalls\":" + KwsEngine.acceptCalls.get()
                            + ",\"readyTrueCount\":" + KwsEngine.readyTrueCount.get()
                            + ",\"decodeCalls\":" + KwsEngine.decodeCalls.get()
                            + String.format(Locale.ROOT, ",\"decodeAvgMs\":%.2f,\"decodeP50Ms\":%.2f,"
                                    + "\"decodeP95Ms\":%.2f,\"decodeP99Ms\":%.2f,\"maxDecodeMs\":%.2f",
                                    decodes > 0 ? decodeSum / decodes : 0, p50, p95, p99, decodeMax)
                            + ",\"droppedFrames\":" + droppedFrames
                            + String.format(Locale.ROOT, ",\"rms\":%.1f", rms)
                            + ",\"wallDeltaMs\":" + wallDelta
                            + ",\"cpuDeltaMs\":" + cpuDelta
                            + ",\"captureThreadCpuDeltaMs\":" + threadCpuDeltaMs);

                    statWindow = now;
                    statCpuMs = cpuNow;
                    statThreadCpuNs = threadCpuNow;
                    statFrames = frames;
                    decodeSum = 0;
                    decodes = 0;
                    decodeMax = 0;
                    decodeIdx = 0;
                    Arrays.fill(decodeSamples, 0);
                }
                lastResult = "RECORDING rms=" + String.format(Locale.ROOT, "%.1f", rms);
            }
        } catch (SecurityException se) {
            lastResult = "SECURITY_EXCEPTION";
            L.e("AUDIO_SECURITY_EXCEPTION who=" + who, se);
        } catch (Throwable t) {
            lastResult = "EXCEPTION";
            L.e("DIRECT_AUDIORECORD_FAIL who=" + who, t);
        } finally {
            try {
                if (ar != null) {
                    if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
                    ar.release();
                    L.i("AUDIO_RELEASED who=" + who + " frames=" + frames);
                }
            } catch (Throwable error) {
                L.e("AUDIO_RELEASE_FAIL", error);
            }
            activeRecord = null;
            boolean failed = RUNNING.getAndSet(false);
            RECORDING.set(false);
            lastRms = 0;
            L.i("AUDIO_LOOP_EXIT who=" + who);
            if (failed && captureListener != null) captureListener.onCaptureError(lastResult);
        }
    }
}
