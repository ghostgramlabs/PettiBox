package com.ghostgramlabs.pettibox.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoveDown
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity

/**
 * Long-press menu for a save card. Same actions you'd find in the
 * detail-screen top bar, but reachable without opening the item — for
 * the most common "I want to quickly archive this from the grid" case.
 *
 * "Move to collection" expands inline as a chip row instead of opening
 * yet another picker — keeps the interaction inside one sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionSheet(
    item: SaveItemEntity,
    categories: List<CategoryEntity>,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleArchive: () -> Unit,
    onRemind: () -> Unit,
    onMoveTo: (String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var showMove by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this save?") },
            text = {
                Text(
                    "It moves to Recently deleted (in Browse) for 30 days, so you can still bring it back." +
                        if (item.isArchived) "" else " Want it out of the way but kept? Tap \"Archive instead\"."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                    onDismiss()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    if (!item.isArchived) {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            onToggleArchive()
                            onDismiss()
                        }) { Text("Archive instead") }
                    }
                }
            },
            shape = RoundedCornerShape(24.dp)
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
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp)
        ) {
            Text(
                item.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2
            )
            Spacer(Modifier.height(12.dp))

            ActionRow(
                icon = if (item.isPinned) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                title = if (item.isPinned) "Unpin" else "Pin",
                onClick = { onTogglePin(); onDismiss() }
            )
            ActionRow(
                icon = if (item.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                title = if (item.isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = { onToggleFavorite(); onDismiss() }
            )
            ActionRow(
                icon = Icons.Rounded.AccessTime,
                title = if (item.remindAt != null) "Change reminder" else "Remind me",
                onClick = { onRemind(); onDismiss() }
            )
            ActionRow(
                icon = if (item.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                title = if (item.isArchived) "Unarchive" else "Archive",
                onClick = { onToggleArchive(); onDismiss() }
            )
            // Copy actions — grab the link or the note text without opening
            // the save. Shown only when there's something to copy.
            if (!item.url.isNullOrBlank()) {
                ActionRow(
                    icon = Icons.Rounded.Link,
                    title = "Copy link",
                    onClick = {
                        clipboard.setText(AnnotatedString(item.url.orEmpty()))
                        Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
            }
            if (!item.notes.isNullOrBlank()) {
                ActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = "Copy note",
                    onClick = {
                        clipboard.setText(AnnotatedString(item.notes.orEmpty()))
                        Toast.makeText(ctx, "Note copied", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
            }
            ActionRow(
                icon = Icons.Rounded.MoveDown,
                title = "Move to collection",
                onClick = { showMove = !showMove }
            )
            if (showMove) {
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    contentPadding = PaddingValues(start = 32.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it.id }) { c ->
                        val selected = item.categoryId == c.id
                        val tilt = if (c.sortOrder % 2 == 0) -2f else 1.5f
                        CategoryChip(
                            label = c.name,
                            emoji = c.emoji,
                            color = Color(c.colorHex),
                            selected = selected,
                            tilt = tilt,
                            onClick = {
                                onMoveTo(if (selected) null else c.id)
                                onDismiss()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            ActionRow(
                icon = Icons.Rounded.Delete,
                title = "Delete",
                tint = MaterialTheme.colorScheme.error,
                onClick = { showDeleteConfirm = true }
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = tint
        )
    }
}
