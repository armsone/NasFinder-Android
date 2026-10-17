package com.armsone.nasfinder.ui

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt

/** Material slider appearance, with speed-dependent relative dragging and no value jumps. */
@Composable
internal fun TrailerDurationSlider(value: Double, onValueChange: (Double) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var width by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    Slider(
        value = value.toFloat().coerceIn(1f, 7200f),
        onValueChange = { onValueChange(it.roundToInt().coerceIn(1, 7200).toDouble()) },
        valueRange = 1f..7200f,
        modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width }.focusRequester(focusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    val next = when (event.key) {
                        Key.DirectionLeft -> latestValue + if (rtl) 1 else -1
                        Key.DirectionRight -> latestValue + if (rtl) -1 else 1
                        Key.MoveHome -> 1.0
                        Key.MoveEnd -> 7200.0
                        else -> null
                    }
                    if (next != null) { latestChange(next.coerceIn(1.0, 7200.0)); true } else false
                }
            }
            .clearAndSetSemantics {
                contentDescription = "예고편 총길이"
                stateDescription = trailerLengthTitle(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat().coerceIn(1f, 7200f), 1f..7200f, 7198)
                setProgress { target ->
                    latestChange(target.roundToInt().coerceIn(1, 7200).toDouble())
                    true
                }
            }
            .pointerInput(width, density, rtl, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    focusRequester.requestFocus()
                    var previousX = down.position.x
                    val startX = previousX
                    var previousTime = down.uptimeMillis
                    var smoothedSpeed = 0.0
                    var dragging = false
                    var accumulated = latestValue
                    val trackWidth = (width - 20f * density).coerceAtLeast(1f)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val delta = change.position.x - previousX
                        val elapsed = (change.uptimeMillis - previousTime).coerceAtLeast(1)
                        if (!dragging && abs(change.position.x - startX) > touchSlop) {
                            dragging = true
                            accumulated = latestValue
                        }
                        if (dragging) {
                            change.consume()
                            val speed = abs(delta) / density * 1000.0 / elapsed
                            smoothedSpeed = if (smoothedSpeed == 0.0) speed else .7 * smoothedSpeed + .3 * speed
                            val fraction = ((smoothedSpeed - 80.0) / 520.0).coerceIn(0.0, 1.0)
                            val smoothGain = fraction * fraction * (3 - 2 * fraction)
                            val gain = .05 + .95 * smoothGain
                            accumulated = (accumulated + delta / trackWidth * 7199 * gain * if (rtl) -1 else 1).coerceIn(1.0, 7200.0)
                            latestChange(accumulated.roundToInt().toDouble())
                        }
                        previousX = change.position.x
                        previousTime = change.uptimeMillis
                        if (!change.pressed) {
                            if (!dragging) {
                                val position = ((change.position.x - 10f * density) / trackWidth).coerceIn(0f, 1f)
                                val fraction = if (rtl) 1f - position else position
                                latestChange((1 + fraction * 7199).roundToInt().coerceIn(1, 7200).toDouble())
                            }
                            break
                        }
                    }
                }
            },
    )
}
