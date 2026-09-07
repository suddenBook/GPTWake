package com.desmond.gptwake

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.desmond.gptwake.ui.GptWakeScreen
import com.desmond.gptwake.ui.Permissions
import com.desmond.gptwake.ui.SetupStep
import com.desmond.gptwake.ui.readPermissions
import com.desmond.gptwake.ui.rememberPermissions
import com.desmond.gptwake.ui.rememberWakeUiState
import com.desmond.gptwake.ui.theme.GptWakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Bumped whenever we return from a permission flow, to force an immediate re-read. */
    private var permissionRevision by mutableIntStateOf(0)

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionRevision++
        }
    private val openSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionRevision++
        }

    private var autoPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AudioStateMonitor.install(this)
        KwsEngine.keywordsThreshold = WakeWordStore.threshold(this)

        setContent {
            GptWakeTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                val ui by rememberWakeUiState()
                val polled by rememberPermissions(context)
                // Re-read eagerly after a permission flow instead of waiting for the next poll.
                val permissions: Permissions =
                    remember(permissionRevision, polled) { readPermissions(context) }

                // The CMU dictionary is ~3.3MB and only needed when changing the wake word, so it
                // loads off the main thread and the UI degrades gracefully until it is ready.
                val tokenizer by produceState<WakeWordTokenizer?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            WakeWordTokenizer().also { it.load(assets) }
                        }.onFailure { L.e("TOKENIZER_LOAD_FAIL", it) }.getOrNull()
                    }
                }

                val changed = stringResource(R.string.msg_wake_word_changed)
                val wasReset = stringResource(R.string.msg_wake_word_reset)

                GptWakeScreen(
                    ui = ui,
                    permissions = permissions,
                    tokenizer = tokenizer,
                    snackbarHostState = snackbarHostState,
                    onRunStep = ::runStep,
                    onToggleService = ::toggleService,
                    onRestartListening = ::restartListening,
                    onWakeWordApplied = { phrase ->
                        restartListening()
                        // Otherwise the persistent notification keeps advertising the old phrase.
                        WakeService.refresh(this@MainActivity)
                        scope.launch {
                            snackbarHostState.showSnackbar(String.format(changed, phrase))
                        }
                    },
                    onWakeWordReset = {
                        WakeWordStore.reset(this@MainActivity)
                        KwsEngine.customKeywordLine = WakeWordStore.keywordLine(this@MainActivity)
                        restartListening()
                        WakeService.refresh(this@MainActivity)
                        scope.launch { snackbarHostState.showSnackbar(wasReset) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KwsEngine.customKeywordLine = WakeWordStore.keywordLine(this)
        permissionRevision++
        // On a fresh device, ask for the things the system will actually show a dialog for.
        if (!autoPrompted) {
            autoPrompted = true
            val step = readPermissions(this).next
            if (step == SetupStep.MIC && !hasBeenAsked(Manifest.permission.RECORD_AUDIO) ||
                step == SetupStep.NOTIFICATIONS && !hasBeenAsked(Manifest.permission.POST_NOTIFICATIONS)
            ) runStep(step)
        }
    }

    // ---------------- setup / permissions ----------------

    private fun runStep(step: SetupStep) {
        when (step) {
            SetupStep.MIC -> askOrOpenSettings(Manifest.permission.RECORD_AUDIO)
            SetupStep.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) {
                    askOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS)
                }

            SetupStep.OVERLAY -> launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )

            SetupStep.ASSISTANT -> launch(Intent("android.settings.VOICE_INPUT_SETTINGS"))
            SetupStep.DONE -> Unit
        }
    }

    /**
     * Requests a runtime permission, or sends the user to app settings once the system will no
     * longer show the dialog (permanently denied).
     */
    private fun askOrOpenSettings(permission: String) {
        val granted = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val canAsk = shouldShowRequestPermissionRationale(permission) ||
                (!granted && !hasBeenAsked(permission))
        if (canAsk) {
            markAsked(permission)
            requestPermission.launch(permission)
        } else {
            launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun hasBeenAsked(p: String) =
        getPreferences(MODE_PRIVATE).getBoolean("asked_$p", false)

    private fun markAsked(p: String) =
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked_$p", true).apply()

    private fun launch(intent: Intent) {
        runCatching { openSettings.launch(intent) }
            .onFailure {
                L.e("LAUNCH_SETTINGS_FAIL", it)
                Toast.makeText(this, R.string.msg_no_settings_page, Toast.LENGTH_LONG).show()
            }
    }

    // ---------------- listening ----------------

    private fun toggleService() {
        if (WakeService.isForegroundNow()) {
            Prefs.setListeningEnabled(this, false)
            stopService(Intent(this, WakeService::class.java))
            return
        }
        val step = readPermissions(this).listeningStep
        if (step != SetupStep.DONE) {
            runStep(step)
            return
        }
        // The transparent shim provides the visible moment a background microphone FGS needs.
        startActivity(
            Intent(this, ShimActivity::class.java)
                .putExtra(ShimActivity.EXTRA_ACTION, "fgs")
        )
    }

    private fun restartListening() {
        if (!WakeService.isForegroundNow()) return
        WakeService.controller()?.restartStream()
    }
}
