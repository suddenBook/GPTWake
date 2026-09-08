package com.desmond.gptwake;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** adb-drivable control surface so the probe can be scripted without UI taps. */
public class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        L.i("CTRL cmd=" + cmd);
        if (cmd == null) return;
        Context app = context.getApplicationContext();
        AudioStateMonitor.install(app);

        switch (cmd) {
            case "arm": {
                int delay = intent.getIntExtra("delay", 30);
                L.i("ARMED delay=" + delay + "s");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    L.i("LAUNCH_ATTEMPT");
                    boolean ok = GptLauncher.launch(app);
                    L.i("LAUNCH_RESULT=" + ok);
                }, delay * 1000L);
                break;
            }
            case "launch":
                L.i("LAUNCH_RESULT=" + GptLauncher.launch(app));
                break;
            case "mode":
                Prefs.setMode(app, intent.getStringExtra("value"));
                L.i("MODE_SET=" + Prefs.mode(app));
                break;
            case "micfgs_start":
                try {
                    app.startForegroundService(new Intent(app, WakeService.class));
                    L.i("CTRL_MIC_FGS_REQUEST_OK");
                } catch (Throwable t) {
                    L.e("CTRL_MIC_FGS_REQUEST_FAIL", t);
                }
                break;
            case "micfgs_stop":
                Prefs.setListeningEnabled(app, false);
                app.stopService(new Intent(app, WakeService.class));
                L.i("CTRL_MIC_FGS_STOP");
                break;
            case "rec_start":
                AudioProbe.start("CTRL");
                break;
            case "rec_stop":
                AudioProbe.stop();
                break;
            case "hangup": {
                PendingIntent pi = VoiceNotificationListener.hangUpIntent();
                if (pi == null) {
                    L.i("HANGUP_NO_INTENT");
                    break;
                }
                try {
                    pi.send();
                    L.i("HANGUP_SENT");
                } catch (Throwable t) {
                    L.e("HANGUP_FAIL", t);
                }
                break;
            }
            case "shim_launch":
            case "shim_fgs": {
                Intent i = new Intent(app, ShimActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(ShimActivity.EXTRA_ACTION, "shim_fgs".equals(cmd) ? "fgs" : "launch");
                try {
                    app.startActivity(i);
                    L.i("SHIM_START_OK cmd=" + cmd);
                } catch (Throwable t) {
                    L.e("SHIM_START_FAIL", t);
                }
                break;
            }
            case "cycle_no_shim": {
                int stopAfter = intent.getIntExtra("stop_after", 45);
                int resumeAfter = intent.getIntExtra("resume_after", 75);
                try {
                    app.startForegroundService(new Intent(app, WakeService.class)
                            .putExtra(WakeService.EXTRA_CYCLE, true)
                            .putExtra(WakeService.EXTRA_STOP_AFTER, stopAfter)
                            .putExtra(WakeService.EXTRA_RESUME_AFTER, resumeAfter));
                    L.i("CYCLE_REQUEST_OK stop=" + stopAfter + " resume=" + resumeAfter);
                } catch (Throwable t) {
                    L.e("CYCLE_REQUEST_FAIL", t);
                }
                break;
            }
            case "run_start": {
                Cfg.powerLogIntervalMs = intent.getIntExtra("interval", 10) * 1000L;
                String id = intent.getStringExtra("id");
                String md = Cfg.micOnly ? "MIC_ONLY_MODEL_LOADED"
                        : (Cfg.evalMode ? "KWS_EVAL" : "KWS_FULL");
                Measure.start(app, id == null ? "run" : id, md, null);
                PowerLogger.start(app, id == null ? "run" : id);
                break;
            }
            case "run_end": {
                // finish() also emits the end-of-run heavy sample (PSS, per-thread CPU) before
                // Measure.end() closes the file.
                PowerLogger.finish(app, intent.getStringExtra("id") == null
                        ? "run" : intent.getStringExtra("id"));
                Measure.end(intent.getStringExtra("reason") == null
                        ? "completed" : intent.getStringExtra("reason"));
                break;
            }
            case "trial_begin": {
                WakeController t = WakeService.controller();
                TrialLog.begin(app, intent.getStringExtra("distance") == null
                                ? "near" : intent.getStringExtra("distance"),
                        intent.getIntExtra("index", 0),
                        t == null ? 0 : t.rawHits(),
                        t == null ? 0 : t.acceptedHits(),
                        t == null ? 0 : t.suppressedHits());
                break;
            }
            case "trial_end": {
                WakeController t = WakeService.controller();
                TrialLog.end(app, t == null ? 0 : t.rawHits(),
                        t == null ? 0 : t.acceptedHits(),
                        t == null ? 0 : t.suppressedHits());
                break;
            }
            case "measure_dir": {
                java.io.File d = Measure.dir(app);
                java.io.File[] fs = d.listFiles();
                L.i("MEASURE_DIR " + d.getAbsolutePath()
                        + " files=" + (fs == null ? 0 : fs.length));
                if (fs != null) {
                    for (java.io.File f : fs) L.i("MEASURE_FILE " + f.getName() + " " + f.length() + "B");
                }
                break;
            }
            case "set_wakeword": {
                String phrase = intent.getStringExtra("phrase");
                WakeWordTokenizer tk = new WakeWordTokenizer();
                try {
                    tk.load(app.getAssets());
                } catch (Throwable t) {
                    L.e("CTRL_TOKENIZER_FAIL", t);
                    break;
                }
                WakeLanguage language = WakeLanguage.fromId(intent.getStringExtra("language"));
                WakeWordTokenizer.Result r = tk.convert(phrase, language,
                        intent.getStringExtra("reading"));
                L.i("CTRL_WAKEWORD phrase=" + phrase + " ok=" + r.ok
                        + " tokens=[" + r.tokens + "] readable=[" + r.readable + "]"
                        + " line=[" + r.keywordLine + "] err=" + r.err
                        + (r.errArg == null ? "" : " arg=" + r.errArg));
                if (r.ok) {
                    WakeWordStore.save(app, phrase, language, r);
                    WakeController wcx = WakeService.controller();
                    if (wcx != null) wcx.restartStream();
                    WakeService.refresh(app);
                }
                break;
            }
            case "eval_mode": {
                Cfg.evalMode = intent.getBooleanExtra("on", true);
                L.i("EVAL_MODE=" + Cfg.evalMode + " refractoryMs=" + Cfg.refractoryMs);
                break;
            }
            case "mic_only": {
                Cfg.micOnly = intent.getBooleanExtra("on", true);
                L.i("MIC_ONLY_MODEL_LOADED=" + Cfg.micOnly);
                break;
            }
            case "powerlog": {
                if (intent.getBooleanExtra("on", true)) {
                    Cfg.powerLogIntervalMs = intent.getIntExtra("interval", 10) * 1000L;
                    PowerLogger.start(app, intent.getStringExtra("tag") == null
                            ? "run" : intent.getStringExtra("tag"));
                } else {
                    PowerLogger.stop();
                }
                break;
            }
            case "counters": {
                WakeController wc = WakeService.controller();
                L.i("KWS_COUNTERS " + (wc == null ? "no-controller" : wc.counters())
                        + " evalMode=" + Cfg.evalMode + " micOnly=" + Cfg.micOnly);
                break;
            }
            case "reset_counters": {
                WakeController wc = WakeService.controller();
                if (wc != null) wc.resetCounters();
                break;
            }
            case "kws_params": {
                KwsEngine.keywordsScore = intent.getFloatExtra("score", KwsEngine.keywordsScore);
                KwsEngine.keywordsThreshold =
                        intent.getFloatExtra("threshold", KwsEngine.keywordsThreshold);
                WakeWordStore.saveThreshold(app, KwsEngine.keywordsThreshold);
                KwsEngine.numTrailingBlanks =
                        intent.getIntExtra("trailing", KwsEngine.numTrailingBlanks);
                L.i("KWS_PARAMS_SET score=" + KwsEngine.keywordsScore
                        + " threshold=" + KwsEngine.keywordsThreshold
                        + " trailingBlanks=" + KwsEngine.numTrailingBlanks
                        + " (restart the FGS to apply)");
                break;
            }
            case "wake_state": {
                WakeController c = WakeService.controller();
                L.i("WAKE_STATE " + (c == null ? "no-controller" : c.state())
                        + " recRunning=" + AudioProbe.isRunning()
                        + " audioMode=" + AudioStateMonitor.modeName(AudioStateMonitor.mode())
                        + " voiceConfirmed=" + AudioStateMonitor.isVoiceConfirmed()
                        + " own=" + AudioStateMonitor.ownCaptureState());
                break;
            }
            case "simulate_wake": {
                WakeController c = WakeService.controller();
                if (c == null) {
                    L.i("SIMULATE_WAKE_NO_CONTROLLER");
                } else {
                    L.i("SIMULATE_WAKE injecting keyword hit");
                    c.onKeyword("<simulated>");
                }
                break;
            }
            case "dump_configs":
                AudioStateMonitor.dumpConfigs("manual");
                L.i("VOICE_CONFIRMED=" + AudioStateMonitor.isVoiceConfirmed()
                        + " commMode=" + AudioStateMonitor.isCommunicationMode()
                        + " realCapture=" + AudioStateMonitor.hasRealCommunicationCapture());
                break;
            case "state": {
                L.i("STATE mode=" + Prefs.mode(app)
                        + " recRunning=" + AudioProbe.isRunning()
                        + " fgs=" + WakeService.isForegroundNow()
                        + " audioMode=" + AudioStateMonitor.modeName(AudioStateMonitor.mode())
                        + " voiceConfirmed=" + AudioStateMonitor.isVoiceConfirmed()
                        + " own=" + AudioStateMonitor.ownCaptureState()
                        + " hangUp=" + (VoiceNotificationListener.hangUpIntent() != null));
                break;
            }
            default:
                L.i("CTRL_UNKNOWN=" + cmd);
        }
    }
}
