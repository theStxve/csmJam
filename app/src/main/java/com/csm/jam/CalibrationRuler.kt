package com.csm.jam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val Accent = Color(0xFF1DB954)

/**
 * Horizontal "infinite" ruler for the audio offset: the scale follows the finger 1:1
 * ([stepMs] per tick, 5 ms by default – fine enough by ear, fast enough for Bluetooth delays),
 * a fling keeps gliding with momentum, every 25 ms gives a light haptic tick.
 */
@Composable
fun CalibrationRuler(
    valueMs: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minMs: Int = -2000,
    maxMs: Int = 2000,
    stepMs: Int = 5
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val tickSpacingPx = with(density) { 9.dp.toPx() }
    val pxPerMs = tickSpacingPx / stepMs
    fun snap(v: Float) = (Math.round(v / stepMs) * stepMs).toFloat()
    val labelSizePx = with(density) { 11.sp.toPx() }
    val scope = rememberCoroutineScope()
    val position = remember {
        Animatable(valueMs.toFloat()).apply { updateBounds(minMs.toFloat(), maxMs.toFloat()) }
    }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(valueMs)

    // Value changed from outside (text field, ±10 buttons, reset): jump there unless the user is scrolling
    LaunchedEffect(valueMs) {
        if (!position.isRunning && position.value.roundToInt() != valueMs) position.snapTo(valueMs.toFloat())
    }
    // Report scrolled values (on the step grid) + a light haptic tick every 25 ms
    LaunchedEffect(Unit) {
        var lastMark = floor(position.value / 25f).toInt()
        snapshotFlow { snap(position.value).toInt() }.collect { v ->
            val mark = floor(v / 25f).toInt()
            if (mark != lastMark) {
                lastMark = mark
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            if (v != currentValue) currentOnValueChange(v)
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        scope.launch { position.stop() }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        // Finger to the left → scale moves left → value increases
                        scope.launch { position.snapTo(position.value - dragAmount / pxPerMs) }
                    },
                    onDragEnd = {
                        val velocityMsPerSec = -tracker.calculateVelocity().x / pxPerMs
                        scope.launch {
                            if (abs(velocityMsPerSec) > 60f) {
                                position.animateDecay(velocityMsPerSec, exponentialDecay(frictionMultiplier = 0.8f))
                            }
                            // Settle on the step grid
                            position.animateTo(snap(position.value))
                        }
                    },
                    onDragCancel = {
                        scope.launch { position.animateTo(snap(position.value)) }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val value = position.value
            val centerX = size.width / 2f
            val bottom = size.height - 10.dp.toPx()
            val halfRangeMs = (centerX / pxPerMs).toInt() + stepMs
            val first = (floor((value - halfRangeMs) / stepMs).toInt()) * stepMs
            val last = (ceil((value + halfRangeMs) / stepMs).toInt()) * stepMs

            for (ms in first..last step stepMs) {
                if (ms < minMs || ms > maxMs) continue
                val x = centerX + (ms - value) * pxPerMs
                // Fade towards the edges so the scale looks endless
                val edge = 1f - (abs(x - centerX) / centerX).coerceIn(0f, 1f)
                val alpha = (0.15f + 0.85f * edge * edge)
                val (height, width) = when {
                    ms % 50 == 0 -> 30.dp.toPx() to 2.dp.toPx()
                    ms % 25 == 0 -> 20.dp.toPx() to 1.5.dp.toPx()
                    else -> 10.dp.toPx() to 1.dp.toPx()
                }
                val color = if (ms == 0) Accent else Color.White
                drawLine(
                    color = color.copy(alpha = alpha * if (ms % 25 == 0) 0.9f else 0.45f),
                    start = Offset(x, bottom),
                    end = Offset(x, bottom - height),
                    strokeWidth = width,
                    cap = StrokeCap.Round
                )
                if (ms % 50 == 0) {
                    labelPaint.textSize = labelSizePx
                    labelPaint.color = (if (ms == 0) Accent else Color.White).copy(alpha = alpha * 0.8f).toArgb()
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(
                            if (ms > 0) "+$ms" else "$ms",
                            x,
                            bottom - 36.dp.toPx(),
                            labelPaint
                        )
                    }
                }
            }

            // Center needle
            drawLine(
                brush = Brush.verticalGradient(listOf(Accent.copy(alpha = 0f), Accent)),
                start = Offset(centerX, 6.dp.toPx()),
                end = Offset(centerX, size.height - 4.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
