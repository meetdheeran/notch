package com.meetdheeran.notch.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.meetdheeran.notch.core.Haptics

/** OxygenOS-feeling motion: soft springs with a little give, nothing that bounces like a toy. */
object Motion {
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 520f)
    fun <T> panel(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 320f)
    val Ease = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
    fun <T> fade(ms: Int = 220) = tween<T>(ms, easing = Ease)
}

/** Press feedback: the control dips a touch and springs back, with a tick. */
fun Modifier.pressable(scaleDown: Float = 0.94f, haptic: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleDown else 1f, Motion.snappy(), label = "press")
    val ctx = LocalContext.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null) {
            if (haptic) Haptics.tick(ctx)
            onClick()
        }
}
