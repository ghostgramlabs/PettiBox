package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.data.local.CategoryEntity

/**
 * Past this many collections a sideways chip strip stops being findable,
 * so pickers switch to the searchable list ([CollectionPickerSheet], and
 * the Save sheet's own finder).
 */
const val COLLECTION_FINDER_THRESHOLD = 8

/** Name or emoji match, the same rule the Save sheet and Browse use. */
fun CategoryEntity.matchesCollectionQuery(query: String): Boolean {
    val q = query.trim()
    return q.isBlank() || name.contains(q, ignoreCase = true) || emoji.contains(q)
}

/**
 * Searchable "Move to collection" picker for when there are too many
 * collections for a chip strip. The current collection sits on top, then
 * your own collections, then starters, each alphabetical. Tapping the
 * current one takes the save out of it, matching the chip strips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    categories: List<CategoryEntity>,
    currentCategoryId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Move to collection"
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(categories, currentCategoryId, query) {
        categories
            .filter { it.matchesCollectionQuery(query) }
            .sortedWith(
                compareBy(
                    { if (it.id == currentCategoryId) 0 else 1 },
                    { if (it.userCreated) 0 else 1 },
                    { it.name.lowercase() }
                )
            )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Find collection") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear collection filter")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            if (visible.isEmpty()) {
                Text(
                    "No collection matches \"${query.trim()}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(visible, key = { it.id }) { c ->
                    val selected = c.id == currentCategoryId
                    CollectionRow(
                        name = c.name,
                        emoji = c.emoji,
                        color = Color(c.colorHex),
                        selected = selected,
                        onClick = {
                            onPick(if (selected) null else c.id)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/**
 * A full-width, tappable collection row for searchable pickers. The
 * selected row fills with a tint of the collection color and shows a
 * check. Easier to hit than a chip in a horizontal strip, and the check
 * makes the current pick unambiguous.
 */
@Composable
fun CollectionRow(
    name: String,
    emoji: String?,
    color: Color,
    selected: Boolean,
    suggested: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = when {
        selected -> color
        suggested -> scheme.primary
        else -> scheme.outline.copy(alpha = 0.4f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) color.copy(alpha = 0.16f) else scheme.surface)
            .border(
                width = if (selected || suggested) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .size(34.dp)
                .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!emoji.isNullOrBlank()) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        } else if (suggested) {
            Text(
                "Suggested",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary
            )
        }
    }
}
