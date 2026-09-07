package com.desmond.gptwake.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.max

/**
 * What the indicator is currently saying. Several [com.desmond.gptwake.WakeController.State] values
 * collapse onto one mode because the user does not need to tell them apart.
 */
enum class IndicatorMode(
    /** Morph half-period in ms. Null means the shape holds still — no animation, no redraw. */
    val periodMs: Int?,
) {
    IDLE(null),
    LOADING(null),
    LISTENING(1400),
    LAUNCHING(600),
    VOICE(900),
    PAUSED(null),
    ERROR(null),
}

/**
 * The status hero: a shape that morphs between two [MaterialShapes] presets and is scaled by live
 * microphone amplitude, so the app's state is readable from across the room.
 *
 * The morph itself comes from `androidx.graphics:graphics-shapes` — material3 only supplies the
 * named polygons. Note that [Morph.toPath] returns an `android.graphics.Path`, not a Compose one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListeningIndicator(
    mode: IndicatorMode,
    /**
     * Smoothed 0..1 microphone level, passed as a lambda rather than a value: it updates ~12x/s and
     * a deferred read keeps that in the draw phase instead of recomposing the whole status card.
     */
    amplitude: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val target = when (mode) {
        IndicatorMode.IDLE -> scheme.outline
        IndicatorMode.LOADING -> scheme.tertiary
        IndicatorMode.LISTENING -> scheme.primary
        IndicatorMode.LAUNCHING -> scheme.tertiary
        IndicatorMode.VOICE -> scheme.primary
        IndicatorMode.PAUSED -> scheme.onSurfaceVariant
        IndicatorMode.ERROR -> scheme.error
    }

    if (mode == IndicatorMode.LOADING) {
        LoadingIndicator(modifier = modifier, color = target)
        return
    }

    // Morph a 9-lobed cookie into a sun, fitting the shared bounds to the canvas below.
    val morph = remember { Morph(start = MaterialShapes.Cookie9Sided, end = MaterialShapes.Sunny) }

    // Reused across frames so the draw phase allocates nothing. The Compose wrapper stays bound to
    // the same underlying android Path, so mutations show through.
    val androidPath = remember { android.graphics.Path() }
    val composePath = remember(androidPath) { androidPath.asComposePath() }
    val matrix = remember { android.graphics.Matrix() }

    val progress = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val period = mode.periodMs
            if (period == null) {
                // Settle and stop. Nothing is left running: this screen sits open on a device that is
                // listening all day.
                progress.animateTo(0f, tween(300))
            } else {
                while (true) {
                    progress.animateTo(1f, tween(period, easing = LinearEasing))
                    progress.animateTo(0f, tween(period, easing = LinearEasing))
                }
            }
        }
    }

    // Colour is effects motion, which is critically damped so hues never overshoot. Size is not
    // animated here — the level is already smoothed upstream, and animating it would add lag to the
    // one thing that should feel immediate.
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "indicatorColor",
    )
    val reactsToLevel = mode == IndicatorMode.LISTENING || mode == IndicatorMode.VOICE

    // MaterialShapes polygons are normalised into a 0..1 box, NOT centred on the origin, so the
    // path has to be fitted from its real bounds. calculateMaxBounds() gives the envelope across
    // the whole morph, which keeps the scale steady instead of breathing as the shape animates.
    val bounds = remember(morph) { morph.calculateMaxBounds() }

    Canvas(modifier) {
        val level = if (reactsToLevel) amplitude().coerceIn(0f, 1f) else 0f
        // Fills its box at rest so it reads as a deliberate mark rather than a dot floating in
        // space; the level only adds the last sliver.
        val base = if (mode == IndicatorMode.VOICE) 0.94f else 0.88f

        val boundsW = bounds[2] - bounds[0]
        val boundsH = bounds[3] - bounds[1]
        val scale = size.minDimension * (base + 0.06f * level) / max(boundsW, boundsH)

        androidPath.rewind()
        morph.toPath(progress.value, androidPath)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            size.width / 2f - (bounds[0] + boundsW / 2f) * scale,
            size.height / 2f - (bounds[1] + boundsH / 2f) * scale,
        )
        androidPath.transform(matrix)
        drawPath(composePath, color)
    }
}
