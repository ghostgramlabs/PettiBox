package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import com.ghostgramlabs.pettibox.ui.theme.BeautyPink
import com.ghostgramlabs.pettibox.ui.theme.FinanceEmerald
import com.ghostgramlabs.pettibox.ui.theme.FitnessIndigo
import com.ghostgramlabs.pettibox.ui.theme.HomePurple
import com.ghostgramlabs.pettibox.ui.theme.MusicCoral
import com.ghostgramlabs.pettibox.ui.theme.ReadLaterSky
import com.ghostgramlabs.pettibox.ui.theme.RecipeOrange
import com.ghostgramlabs.pettibox.ui.theme.StyleAmber
import com.ghostgramlabs.pettibox.ui.theme.TravelTeal

val CollectionEmojiSeeds = listOf(
    "📦", "🛒", "🍳", "✈️", "💪",
    "👗", "🏡", "🧴", "📖", "💰",
    "🎵", "💡", "🎬", "💼", "❤️",
    "📚", "⭐", "🎨", "🐾", "🌱",
    "🧠", "☕", "📷", "🎮", "🧘"
)

val CollectionColorSeeds = listOf(
    RecipeOrange, TravelTeal, FitnessIndigo, StyleAmber, HomePurple,
    BeautyPink, ReadLaterSky, FinanceEmerald, MusicCoral
)

data class NewCollection(val name: String, val emoji: String, val colorHex: Long)

/**
 * Create-a-collection popup. A bottom sheet (not a centred dialog) so it
 * matches the rest of the app's popups — the Add chooser, reminder picker,
 * quick actions — and the "tap Add → name it" flow stays in one consistent
 * surface that slides up from the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCollectionDialog(
    initialName: String = "",
    onCreate: (NewCollection) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName.take(28)) }
    var emoji by remember { mutableStateOf(CollectionEmojiSeeds.first()) }
    var color by remember { mutableStateOf(CollectionColorSeeds.first()) }
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
                "New collection",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(28) },
                placeholder = { Text("Name it (e.g. Weekend reads)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text("Pick an emoji", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(CollectionEmojiSeeds) { e ->
                    val selected = e == emoji
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) color.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = color,
                                shape = CircleShape
                            )
                            .clickable { emoji = e },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(e, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Pick a color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CollectionColorSeeds) { c ->
                    val selected = c == color
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape
                            )
                            .clickable { color = c }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onCreate(
                            NewCollection(
                                name = name.trim(),
                                emoji = emoji,
                                colorHex = color.toLongHex()
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Create", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun Color.toLongHex(): Long {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
