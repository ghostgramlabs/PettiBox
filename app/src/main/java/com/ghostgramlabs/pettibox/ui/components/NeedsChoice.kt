package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sync ids of saves edited on two devices and waiting for someone to pick a
 * version. Provided once at the top of the app, so any save card can mark
 * itself without each screen passing it along.
 */
val LocalNeedsChoice = compositionLocalOf<Set<String>> { emptySet() }

/** The amber of "needs your attention", the same as in the Chrome extension. */
val NeedsChoiceAmber = Color(0xFFE0A526)

/**
 * A softly pulsing dot: a save that needs a choice. A ring breathes out
 * around a steady centre, slow enough to notice without being frantic.
 */
@Composable
fun NeedsChoiceDot(modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val pulse = rememberInfiniteTransition(label = "needs-choice")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    val fade by pulse.animateFloat(
        initialValue = 0.55f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "fade"
    )
    Box(
        modifier.semantics { contentDescription = "Edited on 2 devices: needs your choice" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(size)
                .scale(ring)
                .background(NeedsChoiceAmber.copy(alpha = fade), CircleShape)
        )
        Box(Modifier.size(size).background(NeedsChoiceAmber, CircleShape))
    }
}
