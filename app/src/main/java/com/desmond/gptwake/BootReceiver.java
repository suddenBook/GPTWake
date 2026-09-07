package com.desmond.gptwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction()))) return;
        if (!Prefs.listeningEnabled(context) || WakeService.isForegroundNow()
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                || !Settings.canDrawOverlays(context)) {
            L.i("BOOT_LISTENING_SKIPPED disabled, already running, or missing permission");
            return;
        }
        L.i("BOOT_RECEIVER action=" + intent.getAction() + " mode=" + Prefs.mode(context));
        try {
            context.startActivity(new Intent(context, ShimActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ShimActivity.EXTRA_ACTION, "fgs"));
            L.i("BOOT_SHIM_START_OK");
        } catch (Throwable t) {
            L.e("BOOT_SHIM_START_FAIL", t);
        }
    }
}
