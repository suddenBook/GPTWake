package com.desmond.gptwake;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class AndroidCompatibilityTest {
    @Test
    public void notificationStopWorksOnMinimumAndroidVersion() {
        var lifecycle = Robolectric.buildService(WakeService.class).create();
        try {
            WakeService service = lifecycle.get();
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
                    new Intent(service, WakeService.class).setAction(WakeService.ACTION_STOP), 0, 1));
        } finally {
            lifecycle.destroy();
        }
    }

    @Test
    public void exportedAssistantLaunchesOnMinimumAndroidVersion() {
        Context context = RuntimeEnvironment.getApplication();
        ComponentName component = new ComponentName(
                "com.openai.chatgpt", "com.openai.voice.assistant.AssistantActivity");
        ActivityInfo info = new ActivityInfo();
        info.packageName = component.getPackageName();
        info.name = component.getClassName();
        info.exported = true;
        shadowOf(context.getPackageManager()).addOrUpdateActivity(info);

        assertTrue(GptLauncher.launchDirect(context));
        assertEquals(component, shadowOf(RuntimeEnvironment.getApplication())
                .getNextStartedActivity().getComponent());
    }
}
