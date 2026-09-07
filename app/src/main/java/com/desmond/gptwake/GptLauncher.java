package com.desmond.gptwake;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

public final class GptLauncher {

    private static final String GPT_PACKAGE = "com.openai.chatgpt";

    private static final ComponentName GPT_VOICE_COMPONENT = new ComponentName(
            GPT_PACKAGE, "com.openai.voice.assistant.AssistantActivity");

    public static boolean launchDirect(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ActivityInfo info = pm.getActivityInfo(
                    GPT_VOICE_COMPONENT, 0);
            if (!info.exported) throw new IllegalStateException("AssistantActivity not exported");
            if (info.permission != null) {
                throw new IllegalStateException("AssistantActivity needs " + info.permission);
            }
            context.startActivity(new Intent()
                    .setComponent(GPT_VOICE_COMPONENT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            L.i("DIRECT_LAUNCH_OK");
            return true;
        } catch (Throwable t) {
            L.e("DIRECT_LAUNCH_FAIL", t);
            return false;
        }
    }

    public static boolean launchDeeplink(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://chat.com/?mode=voice"))
                    .setPackage(GPT_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            L.i("DEEPLINK_LAUNCH_OK");
            return true;
        } catch (Throwable t) {
            L.e("DEEPLINK_LAUNCH_FAIL", t);
            return false;
        }
    }

    public static boolean launch(Context context) {
        return launchDirect(context) || launchDeeplink(context);
    }
}
