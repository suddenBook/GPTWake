package com.desmond.gptwake.ui

import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34])
class PermissionsTest {
    @Test
    fun missingNotificationsDoesNotHideRequiredOverlayStep() {
        assertEquals(SetupStep.OVERLAY, Permissions(true, false, false, true).listeningStep)
        assertEquals(SetupStep.DONE, Permissions(true, false, true, true).listeningStep)
        assertEquals(SetupStep.MIC, Permissions(false, false, false, false).listeningStep)
    }

    @Test
    fun assistantPackageMustMatchExactly() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, "voice_interaction_service",
            "com.openai.chatgpt.other/.Assistant")
        assertFalse(readPermissions(context).assistant)
        Settings.Secure.putString(context.contentResolver, "voice_interaction_service",
            "com.openai.chatgpt/.Assistant")
        assertTrue(readPermissions(context).assistant)
    }
}
