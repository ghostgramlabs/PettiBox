package com.ghostgramlabs.pettibox.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** An open-source component and the license file (in assets/licenses) it's under. */
private data class Component(val name: String, val note: String, val licenseFile: String)

private val COMPONENTS = listOf(
    Component("Tesseract OCR", "Apache License 2.0 · Copyright Google and contributors", "apache-2.0.txt"),
    Component("Tesseract4Android", "Apache License 2.0 · Copyright Adaptech s.r.o., Robert Pösel", "apache-2.0.txt"),
    Component("Tesseract language data (tessdata_fast)", "Apache License 2.0 · Copyright Google and contributors", "apache-2.0.txt"),
    Component("Leptonica", "Leptonica license (BSD-style) · Copyright Dan Bloomberg and contributors", "leptonica.txt"),
    Component("libpng", "PNG Reference Library License", "libpng.txt"),
    Component("libjpeg", "This software is based in part on the work of the Independent JPEG Group", "libjpeg.txt"),
    Component("jsoup", "MIT License · Copyright Jonathan Hedley", "jsoup-mit.txt"),
    Component("Readability4J", "Apache License 2.0 · Copyright dankito", "apache-2.0.txt"),
    Component("Android Jetpack (AndroidX), Room, WorkManager, Compose", "Apache License 2.0 · Copyright The Android Open Source Project", "apache-2.0.txt"),
    Component("Kotlin, kotlinx.coroutines", "Apache License 2.0 · Copyright JetBrains s.r.o.", "apache-2.0.txt"),
    Component("Dagger, Hilt", "Apache License 2.0 · Copyright The Dagger Authors", "apache-2.0.txt"),
    Component("Coil", "Apache License 2.0 · Copyright Coil Contributors", "apache-2.0.txt"),
)

/** Settings › About › Open-source licenses: each component, tap for its license text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val open = remember { mutableStateMapOf<String, String>() }
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
                    "Open-source licenses",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "PettiBox is built with these open-source components. Tap one to read its license. " +
                        "Text recognition also uses Google ML Kit, under Google's ML Kit terms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(COMPONENTS, key = { it.name }) { c ->
                val text = open[c.name]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (text != null) open.remove(c.name)
                            else open[c.name] = runCatching {
                                context.assets.open("licenses/${c.licenseFile}").bufferedReader().use { it.readText() }
                            }.getOrDefault("")
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                    Text(c.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (!text.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
