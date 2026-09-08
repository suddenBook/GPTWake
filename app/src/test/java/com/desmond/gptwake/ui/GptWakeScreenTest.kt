package com.desmond.gptwake.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.desmond.gptwake.WakeLanguage
import com.desmond.gptwake.WakeWordStore
import com.desmond.gptwake.WakeWordTokenizer
import com.desmond.gptwake.ui.theme.GptWakeTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test
    fun japanesePhraseCanBeAppliedAndResetWithCorrectRecognitionControls() {
        val context = RuntimeEnvironment.getApplication()
        val tokenizer = WakeWordTokenizer().also { it.load(context.assets) }
        showScreen(tokenizer = tokenizer)
        compose.onNodeWithTag("wakeLanguage-ja").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithTag("wakePhraseInput").performScrollTo().performTextInput("東京")
        compose.onNodeWithTag("wakeReadingInput").performScrollTo().performTextInput("とうきょう")
        compose.onNodeWithText("Apply").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(WakeLanguage.JAPANESE, WakeWordStore.language(context))
            assertEquals("東京", WakeWordStore.phrase(context))
            assertEquals("とうきょう", WakeWordStore.read(context).japaneseReading)
        }
        compose.onNodeWithText("Japanese phrase matching").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("japaneseExample").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("東京", WakeWordStore.phrase(context)) }
        compose.onNodeWithText("Apply").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("もしもし", WakeWordStore.phrase(context))
            assertEquals("もしもし", WakeWordStore.read(context).japaneseReading)
        }
        compose.onNodeWithText("Reset to default").performScrollTo().performClick()
        compose.onNodeWithTag("wakeLanguage-zh-en").assertIsSelected()
        compose.onNodeWithText("Sensitivity").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(WakeLanguage.ZH_EN, WakeWordStore.language(context)) }
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h740dp-mdpi")
    fun japaneseLayoutWithLargeTextCanReachTheFooter() {
        RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
            .getSharedPreferences("wakeword", 0).edit()
            .putString("language", "ja").putString("phrase", "おはようございます")
            .putString("japanese_reading", "おはようございます").commit()
        showScreen(fontScale = 2f)
        compose.onNodeWithTag("wakeLanguage-ja").performScrollTo().assertIsSelected()
        snapshot("japanese-large-text")
        compose.onNodeWithText("音声認識はすべてこの端末で行われます。音声はアップロードされません")
            .performScrollTo().assertIsDisplayed()
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

    private fun showScreen(
        fontScale: Float = 1f,
        events: List<String> = emptyList(),
        tokenizer: WakeWordTokenizer? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                GptWakeTheme(dynamicColor = false) {
                    GptWakeScreen(
                        ui = WakeUiState(null, false, false, "", events),
                        permissions = Permissions(false, false, false, false),
                        tokenizer = tokenizer,
                        snackbarHostState = SnackbarHostState(),
                        onRunStep = {},
                        onToggleService = {},
                        onRestartListening = {},
                        onWakeWordApplied = {},
                        onWakeWordReset = { WakeWordStore.reset(RuntimeEnvironment.getApplication()) },
                    )
                }
            }
        }
    }
}
