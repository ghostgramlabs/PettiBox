package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.ui.res.stringResource
import com.ghostgramlabs.pettibox.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.ui.theme.isLightTheme

@Composable
fun SearchField(
    value: String,
    placeholder: String = stringResource(R.string.search_what_looking),
    onValueChange: (String) -> Unit,
    onClear: () -> Unit = { onValueChange("") },
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // Light mode: a recessed warm well (surfaceVariant sits a touch deeper than
    // the page) with a soft hairline — reads like a slot pressed into the
    // paper rather than an outlined Material field. Dark keeps its raised look.
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
            .padding(horizontal = 18.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = scheme.primary)
        Box(Modifier.padding(start = 12.dp).weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = scheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.search_clear),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClear)
                    .padding(4.dp)
            )
        }
    }
}
