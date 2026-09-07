package com.desmond.gptwake;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wake-word state machine. Every transition runs on one serial scheduled executor so that the
 * capture thread, the AudioManager callbacks and the service lifecycle can never race on state.
 */
public final class WakeController implements AudioProbe.WakeListener, AudioStateMonitor.Listener {

    public enum State {
        STARTING, KWS_MODEL_LOADING, KWS_LISTENING, MIC_HANDOFF, CHATGPT_LAUNCHING,
        VOICE_ACTIVE, EXTERNAL_COMMUNICATION, KWS_REACQUIRING, ERROR, STOPPED
    }

    private static final long HANDOFF_CAPTURE_GONE_TIMEOUT_MS = 500;
    private static final long HANDOFF_DRAIN_MS = 250;
    private static final long VOICE_CONFIRM_MS = 5000;
    private static final long DEEPLINK_CONFIRM_MS = 7000;
    private static final long VOICE_END_DEBOUNCE_MS = 1500;
    private static final long REACQUIRE_CONFIRM_MS = 3000;

    // Also serializes teardown with the next service instance after a quick Stop/Start.
    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "wake-state"));

    private final Context ctx;
    private final KwsEngine kws;
    private final ScheduledExecutorService exec;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile State state = State.STOPPED;
    private long launchStartedAt;
    private boolean deeplinkTried;
    private long voiceEndCandidateSince;
    private long lastAcceptedHitMs;
    private volatile long rawHits;
    private volatile long acceptedHits;
    private volatile long suppressedHits;

    public WakeController(Context c) {
        this(c, new KwsEngine(), EXEC);
    }

    WakeController(Context c, KwsEngine kws, ScheduledExecutorService exec) {
        this.ctx = c.getApplicationContext();
        this.kws = kws;
        this.exec = exec;
    }

    public State state() {
        return state;
    }

    public KwsEngine engine() {
        return kws;
    }

    private void set(State s) {
        if (stopped.get() && s != State.STOPPED) return;
        if (state != s) {
            L.i("STATE " + state + " -> " + s);
            Measure.event("STATE", Measure.jstr("from", state.name()) + "," + Measure.jstr("to", s.name()));
            state = s;
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        post(() -> {
            set(State.STARTING);
            AudioStateMonitor.install(ctx);
            KwsEngine.customKeywordLine = WakeWordStore.keywordLine(ctx);
            KwsEngine.keywordsThreshold = WakeWordStore.threshold(ctx);
            L.i("WAKEWORD phrase=" + WakeWordStore.phrase(ctx));
            AudioStateMonitor.setListener(this);
            AudioProbe.bind(kws, this);
            AudioProbe.setFeeding(false);   // capture first, feed after the model is ready
            boolean captureDeferred = hasCommunication();
            if (!captureDeferred) AudioProbe.start("FGS");
            set(State.KWS_MODEL_LOADING);
            loadModel(captureDeferred);
        });
    }

    private void loadModel(boolean captureDeferred) {
        try {
            AssetManager am = ctx.getAssets();
            kws.load(am);
            if (stopped.get()) return;
            if (hasCommunication()) {
                pauseForCommunication();
                return;
            }
            // A call may have ended while the model was loading, before any capture was started.
            if (captureDeferred) AudioProbe.start("FGS");
            kws.newStream();
            AudioProbe.setFeeding(true);
            if (AudioProbe.isRunning()) {
                set(State.KWS_LISTENING);
                L.i("KWS_READY");
            } else {
                later(() -> {
                    if (state == State.KWS_MODEL_LOADING && !AudioProbe.isRunning()) {
                        fail("MIC_START_TIMEOUT", null);
                    }
                }, REACQUIRE_CONFIRM_MS);
            }
        } catch (Throwable t) {
            fail("KWS_MODEL_LOAD_FAIL", t);
        }
    }

    // ---------------- capture callbacks ----------------

    @Override
    public void onFirstFrame() {
        post(() -> {
            L.i("MIC_RECORDING confirmed");
            if (state == State.KWS_MODEL_LOADING && kws.isLoaded() && AudioProbe.isRunning()) {
                if (hasCommunication()) pauseForCommunication();
                else {
                    set(State.KWS_LISTENING);
                    L.i("KWS_READY");
                }
            }
        });
    }

    @Override
    public void onCaptureError(String reason) {
        post(() -> {
            if (state == State.KWS_LISTENING || state == State.KWS_MODEL_LOADING
                    || state == State.KWS_REACQUIRING) fail("MIC_CAPTURE_FAIL " + reason, null);
        });
    }

    @Override
    public void onKeyword(String keyword) {
        post(() -> {
            if (state != State.KWS_LISTENING) {
                L.i("KWS_HIT_IGNORED state=" + state);
                return;
            }
            rawHits++;
            long now = SystemClock.elapsedRealtime();
            if (acceptedHits > 0 && now - lastAcceptedHitMs < Cfg.refractoryMs) {
                suppressedHits++;
                Measure.event("KWS_HIT", Measure.jstr("keyword", keyword)
                        + "," + Measure.jstr("class", "DUPLICATE_SUPPRESSED")
                        + ",\"sinceLastMs\":" + (now - lastAcceptedHitMs)
                        + ",\"raw\":" + rawHits + ",\"accepted\":" + acceptedHits
                        + ",\"suppressed\":" + suppressedHits
                        + "," + Measure.jstr("trial", TrialLog.currentTrialId()));
                L.i("KWS_HIT_SUPPRESSED keyword=" + keyword
                        + " sinceLastMs=" + (now - lastAcceptedHitMs)
                        + " raw=" + rawHits + " accepted=" + acceptedHits
                        + " suppressed=" + suppressedHits);
                resumeListening(false);
                return;
            }
            lastAcceptedHitMs = now;
            acceptedHits++;
            Measure.event("KWS_HIT", Measure.jstr("keyword", keyword)
                    + "," + Measure.jstr("class", TrialLog.active() ? "TP_USER" : "UNATTRIBUTED")
                    + ",\"raw\":" + rawHits + ",\"accepted\":" + acceptedHits
                    + ",\"suppressed\":" + suppressedHits
                    + ",\"inTrial\":" + TrialLog.active()
                    + "," + Measure.jstr("trial", TrialLog.currentTrialId()));
            L.i("KWS_HIT_ACCEPTED keyword=" + keyword
                    + " raw=" + rawHits + " accepted=" + acceptedHits
                    + " suppressed=" + suppressedHits);

            if (Cfg.evalMode) {
                L.i("KWS_EVAL_HIT keyword=" + keyword + " timestamp=" + now
                        + " (evaluation mode, ChatGPT not launched)");
                resumeListening(true);
                return;
            }
            if (hasCommunication()) {
                L.i("EXTERNAL_COMMUNICATION_DETECTED at wake, not launching");
                pauseForCommunication();
                return;
            }
            set(State.MIC_HANDOFF);
            handoff();
        });
    }

    /** Eval-mode / duplicate path: rebuild the stream and resume after the refractory window. */
    private void resumeListening(boolean withRefractory) {
        AudioProbe.setFeeding(false);
        long delay = withRefractory ? Cfg.refractoryMs : 200;
        later(() -> {
            if (state != State.KWS_LISTENING) return;
            if (hasCommunication()) {
                pauseForCommunication();
                return;
            }
            if (!AudioProbe.isRunning()) {
                L.i("KWS_RESUME_SKIPPED captureNotRunning");
                return;
            }
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("KWS_RESUMED afterMs=" + delay);
        }, delay);
    }

    /** Rebuilds the decoding stream so a new keyword or threshold takes effect immediately. */
    public void restartStream() {
        post(() -> {
            // New settings are read at the next stream creation after a call/handoff finishes.
            if (state != State.KWS_LISTENING || !kws.isLoaded()) {
                L.i("STREAM_RESTART_DEFERRED state=" + state);
                return;
            }
            AudioProbe.setFeeding(false);
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("STREAM_RESTARTED");
        });
    }

    public String counters() {
        return "raw=" + rawHits + " accepted=" + acceptedHits + " suppressed=" + suppressedHits;
    }

    public long rawHits() {
        return rawHits;
    }

    public long acceptedHits() {
        return acceptedHits;
    }

    public long suppressedHits() {
        return suppressedHits;
    }

    public void resetCounters() {
        post(() -> {
            rawHits = 0;
            acceptedHits = 0;
            suppressedHits = 0;
            lastAcceptedHitMs = 0;
            L.i("KWS_COUNTERS_RESET");
        });
    }

    private void handoff() {
        long t0 = SystemClock.elapsedRealtime();
        L.i("MIC_RELEASE_BEGIN");
        AudioProbe.setFeeding(false);
        AudioProbe.stop();                       // stop + join + release
        kws.releaseStream();

        long deadline = SystemClock.elapsedRealtime() + HANDOFF_CAPTURE_GONE_TIMEOUT_MS;
        while (!stopped.get() && AudioStateMonitor.hasOwnCaptureConfig()
                && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        L.i("MIC_RELEASE_DONE elapsedMs=" + (SystemClock.elapsedRealtime() - t0)
                + " ownConfigStillPresent=" + AudioStateMonitor.hasOwnCaptureConfig());

        later(this::launchChatGpt, HANDOFF_DRAIN_MS);
    }

    private void launchChatGpt() {
        if (state != State.MIC_HANDOFF) return;
        if (hasCommunication()) {
            pauseForCommunication();
            return;
        }
        set(State.CHATGPT_LAUNCHING);
        launchStartedAt = SystemClock.elapsedRealtime();
        deeplinkTried = false;
        L.i("CHATGPT_LAUNCH_ATTEMPT route=AssistantActivity");
        GptLauncher.launchDirect(ctx);
        later(this::checkVoiceConfirm, VOICE_CONFIRM_MS);
    }

    private void checkVoiceConfirm() {
        if (state != State.CHATGPT_LAUNCHING) return;
        if (AudioStateMonitor.isVoiceConfirmed()) {
            confirmVoice();
            return;
        }
        if (!deeplinkTried) {
            deeplinkTried = true;
            L.i("VOICE_CONFIRM_TIMEOUT after=" + (SystemClock.elapsedRealtime() - launchStartedAt)
                    + "ms, falling back once");
            L.i("CHATGPT_LAUNCH_ATTEMPT route=deeplink");
            GptLauncher.launchDeeplink(ctx);
            later(this::checkVoiceConfirm, DEEPLINK_CONFIRM_MS);
            return;
        }
        L.i("VOICE_CONFIRM_TIMEOUT final, giving up and restoring KWS");
        reacquire();
    }

    private void confirmVoice() {
        L.i("VOICE_CONFIRMED latencyMs=" + (SystemClock.elapsedRealtime() - launchStartedAt));
        set(State.VOICE_ACTIVE);
        voiceEndCandidateSince = 0;
    }

    // ---------------- audio state callbacks ----------------

    @Override
    public void onAudioStateChanged(String why) {
        post(() -> {
            switch (state) {
                case KWS_LISTENING:
                case KWS_MODEL_LOADING:
                case KWS_REACQUIRING:
                    if (hasCommunication()) pauseForCommunication();
                    break;
                case CHATGPT_LAUNCHING:
                    if (AudioStateMonitor.isVoiceConfirmed()) confirmVoice();
                    break;
                case VOICE_ACTIVE:
                    evaluateVoiceEnd();
                    break;
                case EXTERNAL_COMMUNICATION:
                    if (!hasCommunication()) {
                        L.i("EXTERNAL_COMMUNICATION ended");
                        reacquire();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void evaluateVoiceEnd() {
        boolean ended = !AudioStateMonitor.isCommunicationMode()
                && !AudioStateMonitor.hasRealCommunicationCapture();
        long now = SystemClock.elapsedRealtime();
        if (!ended) {
            voiceEndCandidateSince = 0;
            return;
        }
        if (voiceEndCandidateSince == 0) {
            voiceEndCandidateSince = now;
            later(() -> {
                if (state == State.VOICE_ACTIVE) evaluateVoiceEnd();
            }, VOICE_END_DEBOUNCE_MS + 100);
            return;
        }
        if (now - voiceEndCandidateSince >= VOICE_END_DEBOUNCE_MS) {
            L.i("VOICE_ENDED");
            reacquire();
        }
    }

    /** Re-acquires the microphone inside the existing FGS. No ShimActivity on this path. */
    private void reacquire() {
        if (hasCommunication()) {
            pauseForCommunication();
            return;
        }
        set(State.KWS_REACQUIRING);
        long t0 = SystemClock.elapsedRealtime();
        L.i("MIC_REACQUIRE_BEGIN");
        AudioProbe.setFeeding(false);
        AudioProbe.start("REACQUIRE");

        pollReacquire(t0, 0);
    }

    /** Polls instead of waiting a fixed window, so recovery is bounded by the device, not a timer. */
    private void pollReacquire(long t0, int attempt) {
        if (state != State.KWS_REACQUIRING) return;
        boolean running = AudioProbe.isRunning();
        boolean live = AudioStateMonitor.hasOwnLiveCapture();
        if (running && live) {
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("MIC_REACQUIRE_OK latencyMs=" + (SystemClock.elapsedRealtime() - t0)
                    + " attempts=" + attempt);
            L.i("KWS_READY");
            return;
        }
        if (SystemClock.elapsedRealtime() - t0 >= REACQUIRE_CONFIRM_MS) {
            L.i("MIC_REACQUIRE_FAIL running=" + running + " liveCapture=" + live
                    + " own=" + AudioStateMonitor.ownCaptureState());
            fail("MIC_REACQUIRE_TIMEOUT", null);
            return;
        }
        later(() -> pollReacquire(t0, attempt + 1), 100);
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        exec.execute(() -> {
            try {
                AudioProbe.setFeeding(false);
                AudioProbe.stop();
                AudioProbe.bind(null, null);
                AudioStateMonitor.clearListener(this);
                kws.release();
            } finally {
                set(State.STOPPED);
            }
        });
    }

    private static boolean hasCommunication() {
        return AudioStateMonitor.isCommunicationMode()
                || AudioStateMonitor.hasRealCommunicationCapture();
    }

    private void pauseForCommunication() {
        set(State.EXTERNAL_COMMUNICATION);
        AudioProbe.setFeeding(false);
        AudioProbe.stop();
        kws.releaseStream();
    }

    private void fail(String reason, Throwable error) {
        L.e(reason, error);
        AudioProbe.setFeeding(false);
        AudioProbe.stop();
        kws.releaseStream();
        set(State.ERROR);
    }

    private void runIfActive(Runnable task) {
        if (stopped.get()) return;
        try {
            task.run();
        } catch (Throwable error) {
            fail("WAKE_CONTROLLER_FAIL", error);
        }
    }

    private void post(Runnable task) {
        if (!stopped.get()) exec.execute(() -> runIfActive(task));
    }

    private void later(Runnable task, long delayMs) {
        if (!stopped.get()) exec.schedule(() -> runIfActive(task), delayMs, TimeUnit.MILLISECONDS);
    }
}
