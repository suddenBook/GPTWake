package com.desmond.gptwake.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.desmond.gptwake.ui.theme.GptWakeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GptWakeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun phoneLaunchCanReachEventLog() {
        showScreen()
        compose.onNodeWithText("Start").assertIsDisplayed()
        snapshot("phone")
        compose.onNodeWithText("Recent events").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w740dp-h360dp-land-mdpi")
    fun landscapeCanReachFooter() {
        showScreen()
        assertFooterReachable()
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h1000dp-mdpi")
    fun tallPhoneCanReachFooter() {
        showScreen()
        assertFooterReachable()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h1200dp-mdpi")
    fun tabletWithLargeTextCanReachFooter() {
        showScreen(fontScale = 2f)
        snapshot("tablet-large-text")
        assertFooterReachable()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1000dp-h700dp-land-mdpi")
    fun wideShortWindowCanReachEventLog() {
        showScreen(events = List(40) { "Event $it: " + "long recording details ".repeat(10) })
        compose.onNodeWithText("Recent events").performScrollTo().assertIsDisplayed()
        assertFooterReachable()
    }

    private fun assertFooterReachable() {
        compose.onNodeWithText("Wake word detection runs entirely on this device; no audio is uploaded")
            .performScrollTo().assertIsDisplayed()
    }

    private fun snapshot(name: String) {
        val directory = File("build/reports/ui-snapshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun showScreen(fontScale: Float = 1f, events: List<String> = emptyList()) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                GptWakeTheme(dynamicColor = false) {
                    GptWakeScreen(
                        ui = WakeUiState(null, false, false, "", events),
                        permissions = Permissions(false, false, false, false),
                        tokenizer = null,
                        snackbarHostState = SnackbarHostState(),
                        onRunStep = {},
                        onToggleService = {},
                        onRestartListening = {},
                        onWakeWordApplied = {},
                        onWakeWordReset = {},
                    )
                }
            }
        }
    }
}
