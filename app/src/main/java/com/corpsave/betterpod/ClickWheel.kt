package com.corpsave.betterpod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Degrees of finger travel around the wheel per "click". Smaller = faster scrolling. */
private const val STEP_DEGREES = 22f

private val WheelTop = Color(0xFF3A3A3E)
private val WheelBottom = Color(0xFF26262A)
private val WheelLabel = Color(0xFFB8B8BE)
private val CenterTop = Color(0xFF2C2C30)
private val CenterBottom = Color(0xFF1A1A1D)

/**
 * A touch scroll wheel.
 *  - Drag around the ring: each STEP_DEGREES of travel = one step (clockwise = +1).
 *  - Tap the ring: top = MENU, right = next, bottom = play/pause, left = previous.
 *  - Tap the center: select.
 *
 * How the spin works: we measure the finger's angle from the wheel's center (atan2), and
 * add up how far it has turned since the last event. Every full step fires onScroll.
 */
@Composable
fun ClickWheel(
    modifier: Modifier,
    haptics: Boolean,
    onScroll: (Int) -> Unit,
    onMenu: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSelect: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // The gesture code below starts once and keeps running; these always point at the
    // latest callbacks (current screen, current player) instead of the first ones.
    val scroll by rememberUpdatedState(onScroll)
    val menu by rememberUpdatedState(onMenu)
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val playPause by rememberUpdatedState(onPlayPause)
    val select by rememberUpdatedState(onSelect)
    val hapticsOn by rememberUpdatedState(haptics)
    fun tick() { if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Box(
        modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(WheelTop, WheelBottom)))
            .border(1.dp, Color(0x22FFFFFF), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    fun angleOf(p: Offset) = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()

                    val start = down.position
                    var lastAngle = angleOf(start)
                    var turned = 0f
                    var moved = false
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val a = angleOf(change.position)
                        var delta = a - lastAngle
                        if (delta > 180f) delta -= 360f   // crossed the ±180° seam
                        if (delta < -180f) delta += 360f
                        lastAngle = a
                        turned += delta
                        if ((change.position - start).getDistance() > 14f) moved = true
                        if (abs(turned) >= STEP_DEGREES) {
                            val steps = (turned / STEP_DEGREES).toInt()
                            turned -= steps * STEP_DEGREES
                            scroll(steps)
                            tick()
                        }
                        change.consume()
                    }

                    if (!moved) {
                        // A tap: which zone was it?
                        val dx = start.x - center.x
                        val dy = start.y - center.y
                        val radius = size.width / 2f
                        if (hypot(dx, dy) < radius * 0.36f) {
                            select()
                        } else {
                            val deg = angleOf(start) // 0 = right, 90 = bottom, ±180 = left, -90 = top
                            when {
                                deg in -135f..-45f -> menu()
                                deg in -45f..45f -> next()
                                deg in 45f..135f -> playPause()
                                else -> prev()
                            }
                        }
                        tick()
                    }
                }
            }
    ) {
        Text("MENU", color = WheelLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp))
        Text("⏮", color = WheelLabel, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp))
        Text("⏭", color = WheelLabel, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp))
        Text("⏯", color = WheelLabel, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))

        // Center select button
        Box(
            Modifier.align(Alignment.Center).fillMaxSize(0.36f)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(CenterTop, CenterBottom)))
                .border(1.dp, Color(0x1AFFFFFF), CircleShape)
        )
    }
}
