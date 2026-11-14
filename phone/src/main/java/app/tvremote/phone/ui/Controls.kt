package app.tvremote.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R
import app.tvremote.phone.atv.Keys
import kotlin.math.abs
import kotlin.math.sign

// Left and Right must not flip in right-to-left languages: they map to fixed TV keys.
/**
 * The D-pad ring: a panel disc with arrow keys and a yellow OK, proportioned like the design's 276dp ring
 * (68dp arrows, 100dp OK, 10dp from the rim) at any [diameter].
 */
@Composable
fun DPad(onKey: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, diameter: Dp = 276.dp) {
    val arrowSize = diameter * (68f / 276f)
    val okSize = diameter * (100f / 276f)
    val rim = diameter * (10f / 276f)
    val mid = (diameter - arrowSize) / 2
    val far = diameter - arrowSize - rim
    Box(modifier.size(diameter).alpha(if (enabled) 1f else 0.6f).background(Tok.Panel, CircleShape)) {
        val arrow = Modifier.size(arrowSize)
        IconKey(R.drawable.ic_chevron_up, "Up", { onKey(Keys.DPAD_UP) }, arrow.offset(mid, rim), repeat = true, enabled = enabled, iconSize = 28.dp)
        IconKey(R.drawable.ic_chevron_down, "Down", { onKey(Keys.DPAD_DOWN) }, arrow.offset(mid, far), repeat = true, enabled = enabled, iconSize = 28.dp)
        IconKey(R.drawable.ic_chevron_left, "Left", { onKey(Keys.DPAD_LEFT) }, arrow.offset(rim, mid), repeat = true, enabled = enabled, iconSize = 28.dp)
        IconKey(R.drawable.ic_chevron_right, "Right", { onKey(Keys.DPAD_RIGHT) }, arrow.offset(far, mid), repeat = true, enabled = enabled, iconSize = 28.dp)
        val okAt = (diameter - okSize) / 2
        PhysicalKey("OK", { onKey(Keys.DPAD_CENTER) }, Modifier.size(okSize).offset(okAt, okAt), enabled = enabled, primary = true) {
            Text("OK", fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if (enabled) Tok.Ground else Tok.Bone)
        }
    }
}

/** Swipe to move focus on the TV, tap for OK, long-press for Back. */
@Composable
fun Touchpad(onKey: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    TrailPad(
        hint = "Swipe to move · Tap to select\nHold to go back",
        enabled = enabled,
        modifier = modifier
            .semantics { contentDescription = "Touchpad. Swipe to move, tap to select, long-press to go back." },
        gestures = Modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        send(Keys.DPAD_CENTER)
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        send(Keys.BACK)
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // One focus move per 40dp of finger travel along the dominant axis.
                val step = 40.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(onDragStart = { dx = 0f; dy = 0f }) { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                    while (abs(dx) >= step || abs(dy) >= step) {
                        if (abs(dx) >= abs(dy)) {
                            send(if (dx > 0) Keys.DPAD_RIGHT else Keys.DPAD_LEFT)
                            dx -= sign(dx) * step
                            dy = 0f
                        } else {
                            send(if (dy > 0) Keys.DPAD_DOWN else Keys.DPAD_UP)
                            dy -= sign(dy) * step
                            dx = 0f
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
    )
}
