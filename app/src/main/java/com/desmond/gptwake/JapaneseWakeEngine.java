package com.desmond.gptwake;

import android.content.res.AssetManager;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * VAD runs on capture frames; Japanese ASR runs on a separate worker after a short silence.
 * Only one decode and one waiting segment are retained. All public calls are serialized by
 * KwsEngine; the worker exclusively owns OfflineStreams and never changes capture state.
 */
final class JapaneseWakeEngine {
    static final String DIR = "ja";
    static final int SAMPLE_RATE = 16000;
    static final int WINDOW = 512;
    static final float MAX_SPEECH_SECONDS = 8f;
    private final Vad vad;
    private final OfflineRecognizer recognizer;
    private final ExecutorService worker;
    private final float[] window = new float[WINDOW];
    private int windowLength;
    private long generation;
    private long pendingGeneration;
    private Future<String> pending;
    private float[] waiting;
    private String phrase;
    private String reading;
    private boolean active;
    private boolean closed;

    static JapaneseWakeEngine load(AssetManager assets) throws Exception {
        // Check files before entering JNI: invalid native model configurations can abort.
        for (String name : new String[]{"encoder_model.ort", "decoder_model_merged.ort",
                "tokens.txt", "silero_vad.onnx"}) {
            try (var file = assets.open(DIR + "/" + name)) {
                if (file.read() < 0) throw new IllegalStateException("Empty Japanese model: " + name);
            }
        }
        JapaneseText.load();
        OfflineMoonshineModelConfig moonshine = new OfflineMoonshineModelConfig();
        moonshine.setEncoder(DIR + "/encoder_model.ort");
        moonshine.setMergedDecoder(DIR + "/decoder_model_merged.ort");
        OfflineModelConfig model = new OfflineModelConfig();
        model.setMoonshine(moonshine);
        model.setTokens(DIR + "/tokens.txt");
        model.setNumThreads(1);
        model.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        OfflineRecognizer recognizer = new OfflineRecognizer(assets, config);
        boolean ready = false;
        try {
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(DIR + "/silero_vad.onnx");
            silero.setThreshold(0.5f);
            silero.setMinSilenceDuration(0.4f);
            silero.setMinSpeechDuration(0.25f);
            silero.setMaxSpeechDuration(MAX_SPEECH_SECONDS);
            silero.setWindowSize(WINDOW);
            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(silero);
            vadConfig.setSampleRate(SAMPLE_RATE);
            vadConfig.setNumThreads(1);
            Vad vad = new Vad(assets, vadConfig);
            JapaneseWakeEngine engine = new JapaneseWakeEngine(vad, recognizer,
                    Executors.newSingleThreadExecutor(r -> new Thread(r, "japanese-asr")));
            ready = true;
            L.i("JA_MODEL_READY model=moonshine-tiny-ja-2026-02-27");
            return engine;
        } finally {
            if (!ready) recognizer.release();
        }
    }

    JapaneseWakeEngine(Vad vad, OfflineRecognizer recognizer, ExecutorService worker) {
        this.vad = vad;
        this.recognizer = recognizer;
        this.worker = worker;
    }

    void newStream(String phrase, String reading) {
        releaseStream();
        if (closed) throw new IllegalStateException("Japanese engine is closed");
        if (reading.isEmpty()) throw new IllegalArgumentException("Japanese reading is empty");
        this.phrase = phrase;
        this.reading = reading;
        active = true;
    }

    void releaseStream() {
        active = false;
        generation++;
        waiting = null;
        windowLength = 0;
        if (!closed) vad.reset();
        // An in-flight decode must finish before its native resources can be released. Its
        // generation is now stale, so it can never trigger the next listening session.
    }

    String accept(float[] samples, int sampleRate) {
        if (!active || closed) return null;
        if (sampleRate != SAMPLE_RATE) throw new IllegalArgumentException("Expected 16 kHz audio");
        String hit = completedHit();
        if (hit != null) return hit;
        for (int offset = 0; offset < samples.length;) {
            int count = Math.min(WINDOW - windowLength, samples.length - offset);
            System.arraycopy(samples, offset, window, windowLength, count);
            offset += count;
            windowLength += count;
            if (windowLength < WINDOW) continue;
            windowLength = 0;
            vad.acceptWaveform(window);
            while (!vad.empty()) {
                float[] speech = vad.front().getSamples();
                vad.pop();
                if (speech.length < SAMPLE_RATE / 4 || speech.length > SAMPLE_RATE * 9) continue;
                if (pending == null) submit(speech);
                else {
                    if (waiting != null) L.i("JA_ASR_OVERLOAD replacedWaitingSegment=true");
                    waiting = speech;
                }
            }
        }
        return null;
    }

    private void submit(float[] samples) {
        String expectedPhrase = phrase;
        String expectedReading = reading;
        pendingGeneration = generation;
        pending = worker.submit(() -> {
            OfflineStream stream = recognizer.createStream();
            if (stream.getPtr() == 0) throw new IllegalStateException("Japanese ASR stream rejected");
            try {
                long started = System.nanoTime();
                stream.acceptWaveform(samples, SAMPLE_RATE);
                KwsEngine.decodeCalls.incrementAndGet();
                recognizer.decode(stream);
                String transcript = recognizer.getResult(stream).getText();
                boolean matched = JapaneseText.matches(transcript, expectedPhrase, expectedReading);
                L.i("JA_ASR_STATS audioMs=" + samples.length * 1000L / SAMPLE_RATE
                        + " decodeMs=" + (System.nanoTime() - started) / 1_000_000L
                        + " matched=" + matched);
                // Do not write transcriptions of ambient conversations to the event log.
                return matched ? expectedPhrase : null;
            } finally {
                ThreadCpu.publish("japanese-asr");
                stream.release();
            }
        });
    }

    private String completedHit() {
        if (pending == null || !pending.isDone()) return null;
        String hit;
        try {
            hit = pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Japanese recognition interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Japanese recognition failed", e.getCause());
        }
        pending = null;
        if (pendingGeneration != generation) hit = null;
        if (hit == null && waiting != null) {
            float[] next = waiting;
            waiting = null;
            submit(next);
        }
        return hit;
    }

    void release() {
        if (closed) return;
        releaseStream();
        closed = true;
        try {
            vad.release();
        } finally {
            // FIFO release waits for any native decode without blocking microphone handoff.
            worker.execute(() -> {
                try {
                    if (pending != null) pending.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    L.e("JA_ASR_SHUTDOWN_INTERRUPTED", e);
                } catch (ExecutionException e) {
                    L.e("JA_ASR_DECODE_FAIL", e.getCause());
                } finally {
                    recognizer.release();
                }
            });
            worker.shutdown();
        }
    }
}
