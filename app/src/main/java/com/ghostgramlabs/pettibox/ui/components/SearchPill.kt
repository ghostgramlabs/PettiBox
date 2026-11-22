package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.ui.res.stringResource

import com.ghostgramlabs.pettibox.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.ui.theme.isLightTheme

/**
 * A non-editable search "pill" that opens the search screen on tap.
 * Use this on screens where typing is not the primary action (e.g. Home);
 * it prevents the surface from stealing focus and avoids the keystroke-eats-
 * navigation bug we hit when [SearchField] was used as a navigation trigger.
 */
@Composable
fun SearchPill(
    placeholder: String = stringResource(R.string.search_search_anything_saved),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // Matches SearchField: a recessed warm well + soft hairline in light mode,
    // so the search affordance reads the same whether it's the live field or
    // this tap-to-open pill. Dark mode keeps the raised surface look.
    val light = isLightTheme()
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (light) scheme.surfaceVariant else scheme.surface)
            .border(
                width = if (light) 1.dp else 1.5.dp,
                color = if (light) scheme.outline.copy(alpha = 0.6f) else scheme.outline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = scheme.primary)
        Text(
            placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
