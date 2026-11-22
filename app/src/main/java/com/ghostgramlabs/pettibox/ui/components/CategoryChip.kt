package com.ghostgramlabs.pettibox.ui.components

import com.ghostgramlabs.pettibox.util.AppText

import com.ghostgramlabs.pettibox.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.ui.theme.isLightTheme

/**
 * A category chip with a slight tilt so a row of chips looks
 * hand-placed, not aligned-by-grid. Selected chips fill with the category
 * color; unselected use a soft tint of it on the cream background.
 *
 * When [suggested] is true (and not selected), the chip gets a thicker
 * primary-colored border so it visually nudges the user without being
 * pre-selected — used by the Save sheet's URL/title heuristic.
 */
@Composable
fun CategoryChip(
    label: String,
    emoji: String?,
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    tilt: Float = 0f,
    suggested: Boolean = false,
    onClick: () -> Unit
) {
    // Light mode leans into a soft filled pastel tile with only a whisper of
    // an edge, so a row reads as collectible tiles rather than outlined
    // buttons. Dark mode keeps its original tint + visible hairline.
    val light = isLightTheme()
    val bg = if (selected) color else color.copy(alpha = if (light) 0.24f else 0.14f)
    val fg = if (selected && color.luminance() > 0.55f) {
        Color(0xFF2B2118)
    } else if (selected) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderWidth = when {
        selected -> 0.dp
        suggested -> 2.dp
        else -> 1.dp
    }
    // Light: a filled pastel tile with a gentle but real colored edge so it
    // reads as a tactile, collectible thing rather than a faint smudge — soft,
    // not the old hard 0.35 outline, but present enough to have a shape.
    val borderColor = when {
        selected -> Color.Transparent
        suggested -> MaterialTheme.colorScheme.primary
        light -> color.copy(alpha = 0.30f)
        else -> color.copy(alpha = 0.35f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .rotate(tilt)
            .background(bg, RoundedCornerShape(12.dp))
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .semantics {
                role = Role.Button
                this.selected = selected
                stateDescription = AppText.getString(
                    when {
                        selected -> R.string.browse_selected
                        suggested -> R.string.save_suggested
                        else -> R.string.nav_not_selected
                    }
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        if (!emoji.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (selected) Color.White.copy(alpha = 0.18f) else color.copy(alpha = 0.2f),
                        RoundedCornerShape(12.dp)
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg
        )
    }
}
