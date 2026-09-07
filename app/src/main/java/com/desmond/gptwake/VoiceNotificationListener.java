package com.desmond.gptwake;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.os.BundleCompat;

public class VoiceNotificationListener extends NotificationListenerService {

    private static volatile PendingIntent hangUp;

    public static PendingIntent hangUpIntent() {
        return hangUp;
    }

    private boolean isChatGptVoice(StatusBarNotification sbn) {
        if (!"com.openai.chatgpt".equals(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        return "voice_mode_ongoing".equals(n.getChannelId())
                || Notification.CATEGORY_CALL.equals(n.category);
    }

    @Override
    public void onListenerConnected() {
        L.i("NLS_CONNECTED");
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (isChatGptVoice(sbn)) {
                        L.i("NLS_RESTORE_ACTIVE key=" + sbn.getKey());
                        capture(sbn);
                    }
                }
            }
        } catch (Throwable t) {
            L.e("NLS_CONNECTED_SCAN_FAIL", t);
        }
    }

    private void capture(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        PendingIntent pi = BundleCompat.getParcelable(n.extras,
                Notification.EXTRA_HANG_UP_INTENT, PendingIntent.class);
        if (pi == null && n.actions != null && n.actions.length > 0) {
            pi = n.actions[0].actionIntent;
        }
        hangUp = pi;
        StringBuilder titles = new StringBuilder();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) titles.append('[').append(a.title).append(']');
        }
        L.i("NLS_POST channel=" + n.getChannelId()
                + " category=" + n.category
                + " id=" + sbn.getId()
                + " actions=" + titles
                + " hangup=" + (pi != null));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isChatGptVoice(sbn)) return;
        capture(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isChatGptVoice(sbn)) return;
        hangUp = null;
        L.i("NLS_REMOVE id=" + sbn.getId());
    }
}
