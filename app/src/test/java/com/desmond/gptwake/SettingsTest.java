package com.desmond.gptwake;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowSettings.setCanDrawOverlays(true);
    }

    @Test
    public void newInstallDoesNotStartListeningAtBoot() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test
    public void startChoiceSurvivesDirectBootAndStopDisablesIt() {
        Prefs.setListeningEnabled(context, true);
        var receiver = new BootReceiver();
        receiver.onReceive(context.createDeviceProtectedStorageContext(),
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        Intent start = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull(start);
        assertEquals(ShimActivity.class.getName(), start.getComponent().getClassName());

        Prefs.setListeningEnabled(context, false);
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test
    public void bootDoesNotTryToStartWithoutMicrophonePermission() {
        Prefs.setListeningEnabled(context, true);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test
    public void sensitivityAndPhraseAreAvailableBeforeUnlock() {
        WakeWordStore.save(context, "open sesame", "OW1 P AH0 N S EH1 S AH0 M IY0 @open_sesame");
        WakeWordStore.saveThreshold(context, 0.25f);
        Context lockedContext = context.createDeviceProtectedStorageContext();
        assertEquals(0.25f, WakeWordStore.threshold(lockedContext), 0f);
        assertEquals("open sesame", WakeWordStore.phrase(lockedContext));
        WakeWordStore.reset(context);
        assertEquals(WakeWordStore.DEFAULT_LINE, WakeWordStore.keywordLine(lockedContext));
        assertEquals(0.25f, WakeWordStore.threshold(lockedContext), 0f);
    }

    @Test
    public void invalidThresholdCannotReachNativeDecoder() {
        assertThrows(IllegalArgumentException.class, () -> WakeWordStore.saveThreshold(context, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> WakeWordStore.saveThreshold(context, 1.1f));
    }
}
