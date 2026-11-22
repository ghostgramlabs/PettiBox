package com.ghostgramlabs.pettibox.ui.screens.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.ghostgramlabs.pettibox.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import com.ghostgramlabs.pettibox.data.sync.SyncPreferences
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
    onTurnOff: (removeMine: Boolean) -> Unit,
    onDeleteSynced: () -> Unit,
    onSyncNow: () -> Unit,
    onSendFiles: () -> Unit,
    onReconnect: () -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onReview: (keepMine: Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var confirmOff by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
            stringResource(R.string.sync_sync_between_devices),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = scheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        if (!status.enabled) {
            Text(
                stringResource(R.string.sync_keep_same_library_phones_tablets),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onTurnOn, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sync_turn_sync), fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        // Where things stand.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sync, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // What needs doing comes before a reassuring "Last synced".
                    when {
                        status.progress.isNotBlank() -> status.progress
                        status.needsReconnect -> stringResource(R.string.sync_reconnect_google_drive_keep_syncing)
                        status.pendingReview > 0 -> stringResource(R.string.sync_waiting_choice_below)
                        status.lastSyncAt > 0 -> stringResource(R.string.sync_last_synced, TimeFormat.relative(status.lastSyncAt))
                        else -> stringResource(R.string.sync_not_synced_yet)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
                if (status.accountEmail.isNotBlank()) {
                    Text(
                        stringResource(R.string.sync_google_account, status.accountEmail),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = onSyncNow,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(stringResource(R.string.sync_sync_now), fontWeight = FontWeight.Bold) }
        }
        if (status.lastError.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            if (status.needsReconnect) {
                TextButton(onClick = onReconnect) { Text(stringResource(R.string.sync_reconnect_google_drive), fontWeight = FontWeight.Bold) }
            }
        }
        // Photos and files, said separately from the text sync: a problem if
        // there is one, else where they stand, and a way to send them now.
        // Sync now sends them too; only while they wait (on mobile data with
        // Wi-Fi only on, or a low battery) is there a one-off way round it.
        val waiting = when (status.filesWaiting) {
            SyncPreferences.WAIT_WIFI -> stringResource(R.string.sync_photos_files_waiting_wi_fi) to stringResource(R.string.sync_send_mobile_data)
            SyncPreferences.WAIT_BATTERY -> stringResource(R.string.sync_photos_files_waiting_battery_charge) to stringResource(R.string.sync_send_anyway)
            else -> null
        }
        val filesLine = status.filesProblem.ifBlank { waiting?.first ?: status.filesStatus }
        if (filesLine.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                filesLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.filesProblem.isNotBlank()) scheme.error else scheme.onSurfaceVariant
            )
        }
        // Only while they're actually waiting: a one-off way to send them now.
        if (waiting != null) {
            TextButton(
                onClick = onSendFiles,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(waiting.second, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                    pluralStringResource(R.plurals.sync_pending_review, status.pendingReview, status.pendingReview),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
                Text(
                    stringResource(R.string.sync_changes_other_devices_wait_until),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Usually the person did this on their other device, so applying is the main choice.
                    Button(onClick = { onReview(false) }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.sync_apply_here_too), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onReview(true) }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.sync_put_them_back))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sync_sync_background), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncInterval.entries.forEach { interval ->
                IntervalChip(interval.label, selected = status.interval == interval) { onInterval(interval) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.sync_while_pettibox_open_changes_go),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sync_photos_files_wi_fi_only), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                Text(
                    stringResource(R.string.sync_off_photos_files_sync_any),
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
                Text(stringResource(R.string.sync_device, status.deviceName), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                Text(stringResource(R.string.sync_how_other_devices_see_one), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.sync_rename)) }
        }
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sync_other_devices), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            devices.entries.sortedByDescending { it.value.second }.forEach { (id, info) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 32.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.first.ifBlank { stringResource(R.string.sync_unnamed_device) }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        if (info.second > 0) {
                            Text(
                                stringResource(R.string.sync_last_synced, TimeFormat.relative(info.second)),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { removing = id to info.first }) { Text(stringResource(R.string.settings_remove)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.sync_where_library_lives_phone_other),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmOff = true }) {
                Text(stringResource(R.string.sync_turn_off_sync), color = scheme.error, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.sync_delete_synced_data), color = scheme.error)
            }
        }
    }

    if (confirmOff) {
        var removeMine by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.sync_turn_off_sync_device)) },
            text = {
                Column {
                    Text(stringResource(R.string.sync_phone_stops_sending_receiving_changes))
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { removeMine = !removeMine }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = removeMine, onCheckedChange = { removeMine = it })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sync_also_remove_phones_copy_google), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.sync_phone_youre_giving_away_no),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmOff = false; onTurnOff(removeMine) }) { Text(stringResource(R.string.sync_turn_off), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.settings_cancel)) } },
            shape = RoundedCornerShape(24.dp)
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sync_delete_synced_data_google_drive)) },
            text = {
                Text(
                    stringResource(R.string.sync_deletes_sync_data_pettibox_folder)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteSynced() }) {
                    Text(stringResource(R.string.sync_delete_drive), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_cancel)) } },
            shape = RoundedCornerShape(24.dp)
        )
    }
    removing?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.sync_remove_device_title, name.ifBlank { stringResource(R.string.sync_this_device) })) },
            text = { Text(stringResource(R.string.sync_removes_its_copy_google_drive)) },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemoveDevice(id) }) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.settings_cancel)) } },
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
                stringResource(R.string.sync_name_device),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sync_e_g_work_phone)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.settings_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.settings_save), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
