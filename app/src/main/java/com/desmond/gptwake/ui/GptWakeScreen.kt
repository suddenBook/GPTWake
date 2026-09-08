package com.desmond.gptwake.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.desmond.gptwake.Cfg
import com.desmond.gptwake.KwsEngine
import com.desmond.gptwake.JapaneseText
import com.desmond.gptwake.R
import com.desmond.gptwake.WakeController
import com.desmond.gptwake.WakeLanguage
import com.desmond.gptwake.WakeWordStore
import com.desmond.gptwake.WakeWordTokenizer

/**
 * The single screen.
 *
 * Layout intent: the status hero carries the app's whole state at a glance and is the first thing
 * on screen, so the title bar stays slim rather than reserving a large empty band. The event log is
 * full-width at the bottom because its lines are long and monospaced — putting it in a half-width
 * column forced them to wrap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GptWakeScreen(
    ui: WakeUiState,
    permissions: Permissions,
    tokenizer: WakeWordTokenizer?,
    snackbarHostState: SnackbarHostState,
    onRunStep: (SetupStep) -> Unit,
    onToggleService: () -> Unit,
    onRestartListening: () -> Unit,
    onWakeWordApplied: (String) -> Unit,
    onWakeWordReset: () -> Unit,
) {
    // No title bar: the launcher and recents already say what this app is, and a bar here only
    // reserved an empty band above the one thing worth looking at.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val readableWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
            val wide = readableWidth >= 840.dp
            val compact = readableWidth < 600.dp
            val gutter = if (maxWidth >= 600.dp) 24.dp else 16.dp
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val frame = Modifier
                    .widthIn(max = 1400.dp)
                    .fillMaxWidth()
                    .padding(horizontal = gutter)
                    .padding(bottom = 24.dp)

                // Available height alone cannot tell whether translated or enlarged text fits.
                Column(
                    frame.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ScreenBody(
                        ui, permissions, tokenizer, wide, compact,
                        onRunStep, onToggleService, onRestartListening,
                        onWakeWordApplied, onWakeWordReset,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenBody(
    ui: WakeUiState,
    permissions: Permissions,
    tokenizer: WakeWordTokenizer?,
    wide: Boolean,
    compact: Boolean,
    onRunStep: (SetupStep) -> Unit,
    onToggleService: () -> Unit,
    onRestartListening: () -> Unit,
    onWakeWordApplied: (String) -> Unit,
    onWakeWordReset: () -> Unit,
) {
    val context = LocalContext.current
    var settingsRevision by remember { mutableIntStateOf(0) }
    val selection = remember(settingsRevision, ui) { WakeWordStore.read(context) }
    val applyWord: (String) -> Unit = {
        settingsRevision++
        onWakeWordApplied(it)
    }
    val resetWord: () -> Unit = {
        onWakeWordReset()
        settingsRevision++
    }
    if (permissions.next != SetupStep.DONE) {
        SetupBanner(compact, onContinue = { onRunStep(permissions.next) })
    }

    StatusCard(ui = ui, phrase = selection.phrase, compact = compact, onToggle = onToggleService)

    if (wide) {
        // IntrinsicSize.Max fixes the row to the taller column, then the last card in each column
        // takes the slack with weight(1f) — so both columns end on the same line.
        Row(
            Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WakeWordCard(tokenizer, selection, applyWord, resetWord)
                SensitivityCard(onRestartListening, selection.language, Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PermissionsCard(permissions, onRunStep)
                TestModeCard(Modifier.weight(1f))
            }
        }
    } else {
        WakeWordCard(tokenizer, selection, applyWord, resetWord)
        SensitivityCard(onRestartListening, selection.language)
        PermissionsCard(permissions, onRunStep)
        TestModeCard()
    }

    // Full width: these lines are long and monospaced.
    EventsCard(ui.events)

    Text(
        stringResource(R.string.privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusCard(ui: WakeUiState, phrase: String, compact: Boolean, onToggle: () -> Unit) {
    val mode = ui.state.toIndicatorMode(ui.foreground)
    val level by rememberMicLevel(active = ui.state.isLive())

    // The container carries the state, so the card is readable across the room without reading any
    // text. Colour is effects motion — critically damped, so it never overshoots.
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (mode) {
            IndicatorMode.LISTENING, IndicatorMode.VOICE -> scheme.primaryContainer
            IndicatorMode.LAUNCHING, IndicatorMode.LOADING -> scheme.tertiaryContainer
            IndicatorMode.ERROR -> scheme.errorContainer
            else -> scheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "statusContainer",
    )

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListeningIndicator(
                    mode = mode,
                    amplitude = { level },
                    modifier = Modifier.size(80.dp),
                )
                Column(Modifier.weight(1f).padding(start = 24.dp)) {
                    Text(statusLabel(ui), style = MaterialTheme.typography.headlineMedium)
                    // The armed phrase is the single most useful fact here, so it sits with the
                    // state rather than only in the card below.
                    Text(
                        phrase,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!compact) ServiceButton(ui.foreground, onToggle)
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ServiceButton(ui.foreground, onToggle)
                }
            }

            Text(
                buildString {
                    append(ui.state?.name ?: "STOPPED")
                    append("  ·  ")
                    append(stringResource(if (ui.micRunning) R.string.mic_on else R.string.mic_off))
                    if (ui.counters.isNotEmpty()) {
                        append("  ·  ")
                        append(ui.counters)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServiceButton(running: Boolean, onToggle: () -> Unit) {
    Button(onToggle) {
        Text(stringResource(if (running) R.string.action_stop else R.string.action_start))
    }
}

@Composable
private fun statusLabel(ui: WakeUiState): String = when (ui.state) {
    WakeController.State.KWS_LISTENING ->
        if (ui.evalMode) stringResource(R.string.status_listening_test)
        else stringResource(R.string.status_listening)

    WakeController.State.STARTING,
    WakeController.State.KWS_MODEL_LOADING -> stringResource(R.string.status_loading_model)

    WakeController.State.MIC_HANDOFF,
    WakeController.State.CHATGPT_LAUNCHING -> stringResource(R.string.status_launching)

    WakeController.State.VOICE_ACTIVE -> stringResource(R.string.status_voice_active)
    WakeController.State.KWS_REACQUIRING -> stringResource(R.string.status_reacquiring)
    WakeController.State.EXTERNAL_COMMUNICATION -> stringResource(R.string.status_paused_call)
    WakeController.State.ERROR -> stringResource(R.string.status_error)
    WakeController.State.STOPPED, null ->
        if (ui.foreground) stringResource(R.string.status_starting)
        else stringResource(R.string.status_stopped)
}

// ---------------------------------------------------------------- setup banner

@Composable
private fun SetupBanner(compact: Boolean, onContinue: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        if (compact) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SetupExplanation()
                Button(onContinue, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SetupExplanation(Modifier.weight(1f).padding(end = 16.dp))
                Button(onContinue) { Text(stringResource(R.string.action_continue)) }
            }
        }
    }
}

@Composable
private fun SetupExplanation(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.setup_incomplete_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.setup_incomplete_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- wake word

@Composable
private fun WakeWordCard(
    tokenizer: WakeWordTokenizer?,
    current: WakeWordStore.Selection,
    onApplied: (String) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    var languageId by rememberSaveable { mutableStateOf(current.language.id) }
    var reading by rememberSaveable { mutableStateOf("") }
    val language = WakeLanguage.fromId(languageId)
    val japanese = language == WakeLanguage.JAPANESE
    val result = remember(typed, reading, language, tokenizer) {
        if (typed.isBlank()) null else tokenizer?.convert(typed, language, reading)
    }

    SectionCard(stringResource(R.string.wake_word_title)) {
        Text(
            current.phrase,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (current.language == WakeLanguage.JAPANESE) current.japaneseReading
            else current.keywordLine.substringBefore(" @"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.wake_language), modifier = Modifier.padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in WakeLanguage.values()) {
                FilterChip(
                    selected = language == option,
                    onClick = { languageId = option.id },
                    label = {
                        Text(stringResource(if (option == WakeLanguage.JAPANESE)
                            R.string.language_japanese else R.string.language_chinese_english))
                    },
                    modifier = Modifier.testTag("wakeLanguage-${option.id}"),
                )
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(if (japanese) R.string.wake_word_hint_japanese
                else R.string.wake_word_hint)) },
            singleLine = true,
            isError = result != null && !result.ok,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("wakePhraseInput"),
        )

        if (japanese) {
            TextButton(
                onClick = { typed = "もしもし"; reading = "" },
                modifier = Modifier.testTag("japaneseExample"),
            ) { Text(stringResource(R.string.japanese_example)) }
            OutlinedTextField(
                value = reading,
                onValueChange = { reading = it },
                label = { Text(stringResource(R.string.japanese_reading_hint)) },
                supportingText = { Text(stringResource(R.string.japanese_reading_help)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("wakeReadingInput"),
            )
            Hint(stringResource(R.string.japanese_wake_help))
        }

        val message = result?.messageOrNull()
        when {
            tokenizer == null && typed.isNotBlank() -> Hint(stringResource(R.string.msg_dict_loading))
            message != null -> Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )

            result != null && result.ok -> Text(
                // For English the readable form and the model tokens are the same CMU string.
                if (result.tokens.isEmpty()) result.readable
                else if (result.readable == result.tokens) result.tokens
                else result.readable + "\n" + result.tokens,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton({
                typed = ""
                reading = ""
                languageId = WakeLanguage.ZH_EN.id
                onReset()
            }) { Text(stringResource(R.string.action_reset)) }

            Button(
                onClick = {
                    val r = result ?: return@Button
                    val phrase = if (japanese) JapaneseText.normalizeInput(typed) else typed.trim()
                    WakeWordStore.save(context, phrase, language, r)
                    onApplied(phrase)
                    typed = ""
                    reading = ""
                },
                enabled = result != null && result.ok,
            ) { Text(stringResource(R.string.action_apply)) }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp),
)

/** Maps the tokenizer's error code onto a localised message. Null when there is nothing to say. */
@Composable
private fun WakeWordTokenizer.Result.messageOrNull(): String? = when (err) {
    WakeWordTokenizer.Err.NONE -> null
    WakeWordTokenizer.Err.DICT_NOT_LOADED -> stringResource(R.string.err_dict_not_loaded)
    WakeWordTokenizer.Err.EMPTY -> stringResource(R.string.err_empty)
    WakeWordTokenizer.Err.UNSUPPORTED_HAN ->
        stringResource(R.string.err_unsupported_han, errArg.orEmpty())

    WakeWordTokenizer.Err.UNKNOWN_ENGLISH ->
        stringResource(R.string.err_unknown_english, errArg.orEmpty())

    WakeWordTokenizer.Err.UNSUPPORTED_CHAR ->
        stringResource(R.string.err_unsupported_char, errArg.orEmpty())

    WakeWordTokenizer.Err.UNPARSEABLE -> stringResource(R.string.err_unparseable)
    WakeWordTokenizer.Err.UNSUPPORTED_PHONE ->
        stringResource(R.string.err_unsupported_phone, errArg.orEmpty())

    WakeWordTokenizer.Err.TOO_SHORT -> stringResource(R.string.warn_too_short, errCount)
    WakeWordTokenizer.Err.TOO_LONG -> stringResource(R.string.err_too_long)
    WakeWordTokenizer.Err.JAPANESE_LANGUAGE_REQUIRED -> stringResource(R.string.err_select_japanese)
    WakeWordTokenizer.Err.JAPANESE_READING_REQUIRED -> stringResource(R.string.err_japanese_reading_required)
    WakeWordTokenizer.Err.INVALID_JAPANESE_READING -> stringResource(R.string.err_japanese_reading)
    WakeWordTokenizer.Err.JAPANESE_TOO_SHORT -> stringResource(R.string.warn_japanese_short, errCount)
}

// ---------------------------------------------------------------- sensitivity

@Composable
private fun SensitivityCard(
    onCommit: () -> Unit,
    language: WakeLanguage,
    modifier: Modifier = Modifier,
) {
    if (language == WakeLanguage.JAPANESE) {
        SectionCard(stringResource(R.string.japanese_matching_title), modifier = modifier) {
            Hint(stringResource(R.string.japanese_wake_help))
        }
        return
    }
    val context = LocalContext.current
    var value by remember { mutableFloatStateOf(WakeWordStore.threshold(context).coerceIn(0.20f, 0.60f)) }

    SectionCard(stringResource(R.string.sensitivity_title), modifier = modifier, trailing = {
        Text(
            String.format(LocalLocale.current.platformLocale, "%.2f", value),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                KwsEngine.keywordsThreshold = value
                WakeWordStore.saveThreshold(context, value)
                onCommit()
            },
            valueRange = 0.20f..0.60f,
            steps = 7,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.sensitivity_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- setup / permissions

@Composable
private fun PermissionsCard(
    permissions: Permissions,
    onRunStep: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.permissions_title), modifier = modifier) {
        PermissionRow(
            stringResource(R.string.perm_mic), stringResource(R.string.perm_mic_why),
            permissions.mic, SetupStep.MIC, onRunStep,
        )
        PermissionRow(
            stringResource(R.string.perm_notif), stringResource(R.string.perm_notif_why),
            permissions.notifications, SetupStep.NOTIFICATIONS, onRunStep,
        )
        PermissionRow(
            stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_why),
            permissions.overlay, SetupStep.OVERLAY, onRunStep,
        )
        PermissionRow(
            stringResource(R.string.perm_assistant), stringResource(R.string.perm_assistant_why),
            permissions.assistant, SetupStep.ASSISTANT, onRunStep,
        )
    }
}

@Composable
private fun TestModeCard(modifier: Modifier = Modifier) {
    var eval by remember { mutableStateOf(Cfg.evalMode) }

    SectionCard(
        stringResource(R.string.test_mode),
        modifier = modifier,
        trailing = {
            Switch(checked = eval, onCheckedChange = {
                eval = it
                Cfg.evalMode = it
            })
        },
    ) {
        Text(
            stringResource(R.string.test_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * One setup item. The explanation is shown only when the item is NOT satisfied — once it is done,
 * the reason is noise, and hiding it is most of what keeps this card from becoming a wall of text.
 */
@Composable
private fun PermissionRow(
    label: String,
    why: String,
    granted: Boolean,
    step: SetupStep,
    onRunStep: (SetupStep) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!granted) {
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!granted) {
            OutlinedButton({ onRunStep(step) }) {
                Text(
                    stringResource(
                        if (step == SetupStep.MIC || step == SetupStep.NOTIFICATIONS)
                            R.string.action_grant else R.string.action_open_settings
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------- events

@Composable
private fun EventsCard(events: List<String>, modifier: Modifier = Modifier) {
    SectionCard(stringResource(R.string.events_title), outlined = true, modifier = modifier) {
        Text(
            events.joinToString("\n").ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                // This scroll container is inside the page's vertical scroll, so it needs a
                // finite height. Long lines can still be read by scrolling horizontally.
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        )
    }
}

// ---------------------------------------------------------------- shared card shell

@Composable
private fun SectionCard(
    title: String,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (outlined) MaterialTheme.colorScheme.surfaceContainerLowest
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}
