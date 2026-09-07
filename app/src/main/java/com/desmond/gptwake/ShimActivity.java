package com.desmond.gptwake;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transparent, show-when-locked activity. Our own app may legally set showWhenLocked, so this
 * gives the process a visible/TOP moment even under a secure keyguard, which is what the
 * microphone FGS while-in-use check requires. It finishes on the service ACK, not on a timer.
 */
public class ShimActivity extends Activity {

    public static final String EXTRA_ACTION = "shim_action";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private boolean dispatched = false;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setShowWhenLocked(true);
        setTurnScreenOn(false);
        overridePendingTransition(0, 0);
        L.i("SHIM_CREATE action=" + getIntent().getStringExtra(EXTRA_ACTION));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        L.i("SHIM_WINDOW_FOCUS=" + hasFocus);
        if (hasFocus) dispatch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        L.i("SHIM_RESUME");
        // Some keyguard states never grant window focus; resume plus a short grace is enough.
        ui.postDelayed(this::dispatch, 300);
    }

    private synchronized void dispatch() {
        if (dispatched || !resumed || isFinishing() || isDestroyed()) return;
        dispatched = true;

        String action = getIntent().getStringExtra(EXTRA_ACTION);
        if ("launch".equals(action)) {
            L.i("SHIM_LAUNCH_RESULT=" + GptLauncher.launch(this));
            done(800);
            return;
        }

        ResultReceiver ack = new ResultReceiver(ui) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                L.i("SHIM_GOT_ACK code=" + code
                        + (code == 0 ? " (FGS foreground + AudioRecord recording)" : " (FAILED)"));
                done(0);
            }
        };

        try {
            startForegroundService(new Intent(this, WakeService.class)
                    .putExtra(WakeService.EXTRA_ACK, ack));
            L.i("SHIM_FGS_REQUEST_OK");
        } catch (Throwable t) {
            L.e("SHIM_FGS_REQUEST_FAIL", t);
            done(0);
            return;
        }
        ui.postDelayed(() -> {
            if (finished.get()) return;   // ACK already arrived
            L.i("SHIM_ACK_TIMEOUT");
            done(0);
        }, 5000);
    }

    private void done(long delayMs) {
        if (!finished.compareAndSet(false, true)) return;
        ui.postDelayed(() -> {
            L.i("SHIM_FINISH fgs=" + WakeService.isForegroundNow()
                    + " recording=" + AudioProbe.isRunning());
            finish();
            overridePendingTransition(0, 0);
        }, delayMs);
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
