package com.ghostgramlabs.pettibox.ui.screens.settings

import androidx.compose.ui.res.stringResource
import com.ghostgramlabs.pettibox.R

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
fun ocrLanguagesSummary(res: android.content.res.Resources, on: Set<String>): String {
    val names = OcrLanguages.all.filter { it.code in on }.map { languageName(it.code) }
    return if (names.isEmpty()) res.getString(R.string.ocr_languages_latin_only)
    else res.getString(R.string.ocr_languages_latin_plus, names.joinToString(res.getString(R.string.list_separator)))
}

/** A language's name in the app's language ("Russian", "Russisch", "Русский"). */
fun languageName(code: String): String {
    val locale = java.util.Locale.getDefault()
    val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale)
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
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
                    stringResource(R.string.settings_languages_images),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ocr_english_spanish_portuguese_french_german),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ocr_each_one_downloads_once_1),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                if (rereading) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ocr_reading_saved_images_pdfs_again),
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
                            languageName(lang.code).let { name -> if (name == lang.nativeName) name else "$name · ${lang.nativeName}" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface
                        )
                        val isFailed = lang.code in failed
                        val status = when {
                            !checked -> null
                            ready == null -> null
                            lang.code in ready.orEmpty() -> stringResource(R.string.ocr_ready)
                            isFailed -> stringResource(R.string.ocr_couldnt_download)
                            !online -> stringResource(R.string.ocr_waiting_internet)
                            else -> stringResource(R.string.ocr_downloading)
                        }
                        if (status != null) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (status) {
                                    stringResource(R.string.ocr_ready) -> scheme.primary
                                    stringResource(R.string.ocr_couldnt_download) -> scheme.error
                                    else -> scheme.onSurfaceVariant
                                }
                            )
                        }
                        if (checked && isFailed && lang.code !in ready.orEmpty()) {
                            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.ocr_try_again)) }
                        }
                    }
                    Switch(checked = checked, onCheckedChange = { onToggle(lang.code, it) })
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
