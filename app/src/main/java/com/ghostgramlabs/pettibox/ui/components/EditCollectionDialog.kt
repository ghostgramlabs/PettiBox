package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.data.local.CategoryEntity

/**
 * Edit-a-collection popup. A bottom sheet (not a centred dialog) so it
 * matches the rest of the app's popups. Lives in `components` (not a screen)
 * because both Browse and Settings open it — duplicating it would mean the
 * rename / emoji / color UX drifts between the two surfaces.
 *
 * Starter collections are just as editable as user-created ones: seeding
 * runs once per install, so renames/deletes stick instead of being
 * re-asserted on the next launch.
 *
 * [onLockedChange] adds the "Lock this collection" switch; only Browse
 * passes it, since that's where a locked collection is reachable to unlock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCollectionDialog(
    category: CategoryEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, Long) -> Unit,
    onDelete: (() -> Unit)? = null,
    lockAvailable: Boolean = false,
    onLockedChange: ((Boolean) -> Unit)? = null
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var emoji by remember(category.id) { mutableStateOf(category.emoji) }
    var color by remember(category.id) { mutableStateOf(Color(category.colorHex)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Edit collection",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(28) },
                placeholder = { Text("Collection name") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text("Emoji", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CollectionEmojiSeeds) { e ->
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (e == emoji) color.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { emoji = e },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(e, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CollectionColorSeeds) { c ->
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c)
                            .border(
                                width = if (c == color) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { color = c }
                    )
                }
            }
            if (onLockedChange != null) {
                Spacer(Modifier.height(16.dp))
                LockRow(
                    locked = category.isLocked,
                    // Turning a lock off always works; turning one on needs
                    // a screen lock to unlock it with.
                    enabled = lockAvailable || category.isLocked,
                    onLockedChange = onLockedChange
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { onSave(name, emoji, color.toLongHex()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun LockRow(locked: Boolean, enabled: Boolean, onLockedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = enabled) { onLockedChange(!locked) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Lock this collection",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (enabled) {
                    "Hidden from Home, search, and everywhere else until you unlock it " +
                        "with your fingerprint, face, or phone PIN."
                } else {
                    "Set a screen lock on your phone to use this."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = locked, onCheckedChange = onLockedChange, enabled = enabled)
    }
}
