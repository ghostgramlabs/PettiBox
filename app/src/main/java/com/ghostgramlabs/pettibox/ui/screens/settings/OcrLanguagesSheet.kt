package com.ghostgramlabs.pettibox.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.data.ocr.OcrLanguages
import kotlinx.coroutines.delay

/** Short line for the Settings row: what's read now. */
fun ocrLanguagesSummary(on: Set<String>): String {
    val names = OcrLanguages.all.filter { it.code in on }.map { it.name }
    return if (names.isEmpty()) "English and other Latin-alphabet languages"
    else "English and Latin-alphabet languages, plus " + names.joinToString(", ")
}

/**
 * Which languages are read in images and PDFs. Latin-alphabet languages
 * always are; each other one is a switch, with whether it's ready yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrLanguagesSheet(
    on: Set<String>,
    rereading: Boolean,
    readyNow: suspend () -> Set<String>,
    /** Languages whose download gave up after several tries. */
    failed: Set<String>,
    isOnline: () -> Boolean,
    onRetry: () -> Unit,
    onToggle: (code: String, on: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var ready by remember { mutableStateOf<Set<String>?>(null) }
    var online by remember { mutableStateOf(true) }
    // Downloads finish in the background; check again while the sheet is open.
    LaunchedEffect(on) {
        while (true) {
            ready = runCatching { readyNow() }.getOrNull() ?: ready
            online = isOnline()
            delay(3_000)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            item {
                Text(
                    "Languages in images",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "English, Spanish, Portuguese, French, German, Indonesian, Vietnamese, Turkish and other " +
                        "Latin-alphabet languages are always read. Turn on the others you save text in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Each one downloads once (1–5 MB) and then works offline. Text recognition runs on your phone; if you turn on sync or Drive backup, your files are also copied to your Google Drive. " +
                        "Turning one on reads your saved images and PDFs again, so older saves are found too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                if (rereading) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reading your saved images and PDFs again…",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = scheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            items(OcrLanguages.all, key = { it.code }) { lang ->
                val checked = lang.code in on
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(lang.code, !checked) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${lang.name} · ${lang.nativeName}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface
                        )
                        val isFailed = lang.code in failed
                        val status = when {
                            !checked -> null
                            ready == null -> null
                            lang.code in ready.orEmpty() -> "Ready"
                            isFailed -> "Couldn't download"
                            !online -> "Waiting for internet"
                            else -> "Downloading…"
                        }
                        if (status != null) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (status) {
                                    "Ready" -> scheme.primary
                                    "Couldn't download" -> scheme.error
                                    else -> scheme.onSurfaceVariant
                                }
                            )
                        }
                        if (checked && isFailed && lang.code !in ready.orEmpty()) {
                            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
                        }
                    }
                    Switch(checked = checked, onCheckedChange = { onToggle(lang.code, it) })
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
