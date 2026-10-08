package com.ghostgramlabs.pettibox.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.pettibox.data.sync.SyncInterval
import com.ghostgramlabs.pettibox.data.sync.SyncStatus
import com.ghostgramlabs.pettibox.data.util.TimeFormat

/**
 * Settings › "Sync between devices". Off: what it does and one button. On:
 * where things stand, the interval, devices, and how to stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncSettingsCard(
    status: SyncStatus,
    devices: Map<String, Pair<String, Long>>,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onSyncNow: () -> Unit,
    onReconnect: () -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onReview: (keepMine: Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var confirmOff by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(16.dp)
    ) {
        Text(
            "Sync between devices",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = scheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        if (!status.enabled) {
            Text(
                "Keep the same library on your phones, tablets and the PettiBox Chrome extension. " +
                    "It all goes through your own Google Drive, in a \"PettiBox Sync\" folder; nothing passes through anyone else's servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onTurnOn, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Turn on sync", fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        // Where things stand.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sync, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.progress.isNotBlank() -> status.progress
                        status.lastSyncAt > 0 -> "Last synced ${TimeFormat.relative(status.lastSyncAt)}"
                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
                if (status.accountEmail.isNotBlank()) {
                    Text(
                        "Google account: ${status.accountEmail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = onSyncNow,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("Sync now", fontWeight = FontWeight.Bold) }
        }
        if (status.lastError.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            if (status.needsReconnect) {
                TextButton(onClick = onReconnect) { Text("Reconnect Google Drive", fontWeight = FontWeight.Bold) }
            }
        }

        // Another device wants to take many saves off the shelf.
        if (status.pendingReview > 0) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.errorContainer.copy(alpha = 0.5f))
                    .padding(12.dp)
            ) {
                Text(
                    "Another device wants to remove or archive ${status.pendingReview} saves",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
                Text(
                    "Sync paused until you decide. Apply moves them to Recently deleted or Archive here too " +
                        "(a backup is made first). Keep mine puts them back on every device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReview(true) }, shape = RoundedCornerShape(12.dp)) {
                        Text("Keep mine", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onReview(false) }, shape = RoundedCornerShape(12.dp)) {
                        Text("Apply")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Sync in the background", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncInterval.entries.forEach { interval ->
                IntervalChip(interval.label, selected = status.interval == interval) { onInterval(interval) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "PettiBox also syncs when you open it and about a minute after you change something.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Photos and files on Wi-Fi only", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                Text(
                    "Text always syncs. Photos, PDFs and files use your Drive storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Switch(checked = status.filesOnWifiOnly, onCheckedChange = onWifiOnly)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Devices, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("This device: ${status.deviceName}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                Text("How your other devices see this one", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            TextButton(onClick = { renaming = true }) { Text("Rename") }
        }
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Your other devices", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            devices.entries.sortedByDescending { it.value.second }.forEach { (id, info) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 32.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.first.ifBlank { "Unnamed device" }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        if (info.second > 0) {
                            Text(
                                "Changed ${TimeFormat.relative(info.second)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { removing = id to info.first }) { Text("Remove") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Changes reach your other devices the next time they sync. Edits made on two devices at once are both kept: " +
                "open the save to pick one. A delete always passes through Recently deleted first.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        TextButton(onClick = { confirmOff = true }) {
            Text("Turn off sync", color = scheme.error, fontWeight = FontWeight.Bold)
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off sync on this device?") },
            text = { Text("It stops sending and receiving changes. Nothing is deleted, here or in Google Drive, and your other devices keep syncing.") },
            confirmButton = {
                TextButton(onClick = { confirmOff = false; onTurnOff() }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(24.dp)
        )
    }
    removing?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${name.ifBlank { "this device" }}?") },
            text = { Text("Removes its copy from Google Drive, for a phone you no longer use. Your library stays on your other devices. If it syncs again, it comes back.") },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemoveDevice(id) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            shape = RoundedCornerShape(24.dp)
        )
    }
    if (renaming) {
        RenameDeviceSheet(current = status.deviceName, onDone = { name ->
            renaming = false
            if (name != null) onRename(name)
        })
    }
}

@Composable
private fun IntervalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(label, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDeviceSheet(current: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    ModalBottomSheet(
        onDismissRequest = { onDone(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Text(
                "Name this device",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                placeholder = { Text("e.g. Work phone") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDone(null) }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }, shape = RoundedCornerShape(12.dp)) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
