package com.ghostgramlabs.pettibox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Quick-pick reminder presets that map to wall-clock times relative to
 * "now." Calendar handles DST transitions for us — these all resolve at
 * the moment the user taps, not at app start.
 */
enum class ReminderPreset(val label: String) {
    TONIGHT("Tonight"),
    TOMORROW("Tomorrow"),
    WEEKEND("Weekend"),
    NEXT_WEEK("Next week"),
    CUSTOM("Pick a time…")
}

/**
 * Returns the wall-clock millis for a preset, or null for CUSTOM (caller
 * shows the picker).
 *
 * The hour/minute anchors are user-configurable in Settings: "Tonight" fires
 * at the evening time; "Tomorrow", "This weekend", and "Next week" fire at the
 * morning time. The defaults match the historical hard-coded values (9 AM /
 * 9 PM) so behaviour is unchanged until the user picks their own.
 */
fun ReminderPreset.toEpochMillis(
    now: Long = System.currentTimeMillis(),
    morningHour: Int = 9,
    morningMinute: Int = 0,
    eveningHour: Int = 21,
    eveningMinute: Int = 0
): Long? {
    if (this == ReminderPreset.CUSTOM) return null
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    when (this) {
        ReminderPreset.TONIGHT -> {
            cal.set(Calendar.HOUR_OF_DAY, eveningHour)
            cal.set(Calendar.MINUTE, eveningMinute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        ReminderPreset.TOMORROW -> {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, morningHour)
            cal.set(Calendar.MINUTE, morningMinute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        ReminderPreset.WEEKEND -> {
            cal.set(Calendar.HOUR_OF_DAY, morningHour)
            cal.set(Calendar.MINUTE, morningMinute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val daysUntilSaturday = ((Calendar.SATURDAY - dow) + 7) % 7
            when {
                // Sunday morning, before the morning time: still this weekend.
                dow == Calendar.SUNDAY && cal.timeInMillis > now -> Unit
                daysUntilSaturday == 0 && cal.timeInMillis <= now -> cal.add(Calendar.DAY_OF_MONTH, 7)
                else -> cal.add(Calendar.DAY_OF_MONTH, daysUntilSaturday)
            }
        }
        ReminderPreset.NEXT_WEEK -> {
            // Monday morning of the coming week. Never the same as "Tomorrow"
            // (on a Sunday it's the Monday after) or "Weekend" (a Saturday).
            cal.set(Calendar.HOUR_OF_DAY, morningHour)
            cal.set(Calendar.MINUTE, morningMinute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val daysUntilMonday = ((Calendar.MONDAY - cal.get(Calendar.DAY_OF_WEEK)) + 7) % 7
            cal.add(Calendar.DAY_OF_MONTH, if (daysUntilMonday <= 1) daysUntilMonday + 7 else daysUntilMonday)
        }
        ReminderPreset.CUSTOM -> Unit
    }
    return cal.timeInMillis
}

/**
 * Compact 2-column-card-friendly version of [formatReminderAt]. Drops
 * `:00` minutes, lowercases am/pm, and skips the leading "Today" so the
 * label fits as a footer pill on small cards (≈150 dp wide):
 *
 *   "9pm"           when same day
 *   "Tomorrow 9am"  when next day
 *   "Sat 10am"      within a week
 *   "Dec 3 9am"     beyond a week
 */
fun formatReminderCompact(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = cal.get(Calendar.YEAR) == tomorrowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == tomorrowCal.get(Calendar.DAY_OF_YEAR)
    val withinWeek = (epochMillis - now) in 0..(7L * 24 * 3600 * 1000)
    val minutes = cal.get(Calendar.MINUTE)
    val timeFmt = SimpleDateFormat(if (minutes == 0) "h a" else "h:mm a", Locale.getDefault())
    val timeStr = timeFmt.format(Date(epochMillis))
        .replace(" AM", "am")
        .replace(" PM", "pm")
    return when {
        sameDay -> timeStr
        isTomorrow -> "Tomorrow $timeStr"
        withinWeek -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis)) + " $timeStr"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis)) + " $timeStr"
    }
}

/**
 * Friendly preview of when a reminder will fire — e.g. "Sat 10:00 AM"
 * or "Tomorrow 9:00 AM" or "Dec 3, 9:00 AM" for things further out.
 */
fun formatReminderAt(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = cal.get(Calendar.YEAR) == tomorrowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == tomorrowCal.get(Calendar.DAY_OF_YEAR)
    val withinWeek = (epochMillis - now) in 0..(7L * 24 * 3600 * 1000)
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    return when {
        sameDay -> "Today " + timeFmt.format(Date(epochMillis))
        isTomorrow -> "Tomorrow " + timeFmt.format(Date(epochMillis))
        withinWeek -> SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(epochMillis))
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))
    }
}

private fun formatDateOnly(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val sameDay = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    val isTomorrow = cal.get(Calendar.YEAR) == tomorrowCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == tomorrowCal.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today"
        isTomorrow -> "Tomorrow"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(epochMillis))
    }
}

private fun formatTimeOnly(hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/**
 * Exposes the user's configured morning/evening reminder anchors to the
 * shared [ReminderPickerSheet], so every entry point (save sheet, home,
 * detail, search, browse) resolves presets against the same Settings values
 * without each call site having to thread them through.
 */
@HiltViewModel
class ReminderTimePrefsViewModel @Inject constructor(
    prefs: ReminderPreferences
) : ViewModel() {
    val morning: StateFlow<ReminderTime> = prefs.morningTime.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReminderTime(ReminderPreferences.DEFAULT_MORNING_HOUR, 0)
    )
    val evening: StateFlow<ReminderTime> = prefs.eveningTime.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReminderTime(ReminderPreferences.DEFAULT_EVENING_HOUR, 0)
    )
}

/**
 * Bottom sheet that offers the four preset reminder times plus a
 * "Custom…" entry. Each row taps through directly to a one-tap save,
 * with haptic confirmation; the Custom row routes to a separate sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPickerSheet(
    currentRemindAt: Long?,
    onPick: (Long?) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
    timePrefs: ReminderTimePrefsViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val morning by timePrefs.morning.collectAsStateWithLifecycle()
    val evening by timePrefs.evening.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                "Remind me",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "PettiBox will ask for notification permission when needed. If exact alarms are off, reminders may arrive a little later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (currentRemindAt != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Currently set for " + formatReminderAt(currentRemindAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            // Drop "Tonight" once the evening time has already passed:
            // toEpochMillis would roll it to tomorrow night while the label
            // still says "Tonight", so the row would read "Tonight → Tomorrow
            // 9:00 PM" — exactly the "what time will this fire?" confusion
            // we're trying to remove.
            val now = remember { System.currentTimeMillis() }
            val presets = remember(morning, evening) {
                ReminderPreset.entries.filter { preset ->
                    if (preset != ReminderPreset.TONIGHT) return@filter true
                    val at = preset.toEpochMillis(
                        now, morning.hour, morning.minute, evening.hour, evening.minute
                    ) ?: return@filter true
                    sameDay(at, now)
                }
            }
            presets.forEach { preset ->
                val previewMillis = remember(preset, morning, evening) {
                    preset.toEpochMillis(
                        now, morning.hour, morning.minute, evening.hour, evening.minute
                    )
                }
                val isCustom = preset == ReminderPreset.CUSTOM
                ReminderRow(
                    label = preset.label,
                    subtitle = previewMillis?.let { formatReminderAt(it) } ?: "Choose a date & time",
                    isCustom = isCustom,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isCustom) onCustom() else onPick(previewMillis)
                    }
                )
            }
            if (currentRemindAt != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPick(null)
                }) {
                    Text("Clear reminder", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ReminderRow(
    label: String,
    subtitle: String,
    isCustom: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // Custom row reads as a "go somewhere" affordance (dashed outline +
    // chevron) so it's visually distinct from the one-tap presets. Without
    // this the row looks identical to "Tonight" / "Tomorrow" but behaves
    // differently — opens another sheet instead of saving — which is
    // exactly the kind of mismatch that makes the picker feel buggy.
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
        .clip(RoundedCornerShape(14.dp))
        .then(
            if (isCustom) Modifier.border(
                1.dp,
                scheme.outline,
                RoundedCornerShape(14.dp)
            ) else Modifier
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        Icon(
            if (isCustom) Icons.Rounded.CalendarMonth else Icons.Rounded.AccessTime,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            // The exact fire time is the answer to "when will this go off?" —
            // show it in the accent color at full body size on the presets so
            // it reads as the row's real value, not a faint afterthought. The
            // Custom row has no time yet, so it stays a muted instruction.
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCustom) FontWeight.Normal else FontWeight.SemiBold
                ),
                color = if (isCustom) scheme.onSurfaceVariant else scheme.primary
            )
        }
        if (isCustom) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Custom reminder picker. Date and Time live on the SAME screen as two
 * tappable rows — not separate tabs — so the user cannot tap "Set" while
 * the Time row is still on a silent default.
 *
 * The Time row starts in an explicitly **unset** state and announces it
 * ("Tap to set") in the error color. "Set reminder" stays disabled until
 * the user has explicitly opened the time picker and confirmed a time.
 * This is the precise failure mode of the prior tabbed sheet: the user
 * picked a date, hit Set, and the reminder fired at a default
 * (current-hour + 1, minute 0) they never chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderCustomSheet(
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialDate = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    // DatePicker uses UTC midnight internally; pre-seed UTC midnight of
    // the user's local "today" so the calendar opens on the right day.
    val initialUtcDay = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(
                initialDate.get(Calendar.YEAR),
                initialDate.get(Calendar.MONTH),
                initialDate.get(Calendar.DAY_OF_MONTH),
                0, 0, 0
            )
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtcDay)
    // Time picker initial values exist for the *picker UI* only — we
    // gate "Set reminder" on whether the user has explicitly confirmed
    // the time via [timeConfirmed], so the picker's initial state never
    // silently leaks into the reminder.
    val nowCal = remember { Calendar.getInstance() }
    val timeState = rememberTimePickerState(
        initialHour = nowCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = 0,
        is24Hour = false
    )
    var timeConfirmed by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current

    val combined: Long? by remember {
        derivedStateOf {
            val d = dateState.selectedDateMillis ?: return@derivedStateOf null
            if (!timeConfirmed) return@derivedStateOf null
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = d }
            Calendar.getInstance().apply {
                set(
                    utc.get(Calendar.YEAR),
                    utc.get(Calendar.MONTH),
                    utc.get(Calendar.DAY_OF_MONTH),
                    timeState.hour,
                    timeState.minute,
                    0
                )
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
    val isFuture by remember {
        derivedStateOf { (combined ?: 0L) > System.currentTimeMillis() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        // Vertical scroll so the sheet's content is always fully reachable
        // even on small phones / landscape — the previous fixed
        // heightIn(max = 560.dp) clipped the bottom of the sheet when the
        // user's system font scale was bumped up. Dropping the max height
        // and adding scroll lets the sheet expand or scroll as needed.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Custom reminder",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            // Live preview — primary color when both fields are confirmed
            // AND in the future. When the time is missing, the message is
            // explicit so the user can't accidentally proceed.
            val previewText = when {
                !timeConfirmed -> "Pick a time below to enable Set"
                combined == null -> "Pick a date and time"
                !isFuture -> "That moment has already passed — pick a future time"
                else -> "We'll nudge you " + formatReminderAt(combined!!)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!timeConfirmed || !isFuture) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    previewText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = when {
                        !timeConfirmed -> MaterialTheme.colorScheme.error
                        !isFuture -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            Spacer(Modifier.height(16.dp))

            FieldRow(
                icon = Icons.Rounded.CalendarMonth,
                label = "Date",
                value = dateState.selectedDateMillis?.let {
                    // Display the picked day in the user's local calendar.
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = it
                    }
                    val localShown = Calendar.getInstance().apply {
                        set(
                            utc.get(Calendar.YEAR),
                            utc.get(Calendar.MONTH),
                            utc.get(Calendar.DAY_OF_MONTH),
                            0, 0, 0
                        )
                        set(Calendar.MILLISECOND, 0)
                    }
                    formatDateOnly(localShown.timeInMillis)
                } ?: "Tap to pick",
                placeholderMissing = false,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showDateDialog = true
                }
            )
            Spacer(Modifier.height(10.dp))
            FieldRow(
                icon = Icons.Rounded.AccessTime,
                label = "Time",
                value = if (timeConfirmed) formatTimeOnly(timeState.hour, timeState.minute)
                    else "Tap to set",
                placeholderMissing = !timeConfirmed,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showTimeDialog = true
                }
            )

            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = timeConfirmed && isFuture,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        combined?.let(onConfirm)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Set reminder", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showDateDialog) {
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(onClick = { showDateDialog = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    }

    if (showTimeDialog) {
        // Force fully-expanded so the TimePicker (≈260 dp tall) never
        // lands in the half-collapsed sheet state. Without this the user
        // could only see the title and the top half of the clock face
        // and had to drag the sheet up before tapping "Set time".
        val timeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTimeDialog = false },
            sheetState = timeSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            // Vertical scroll guards against the TimePicker overflowing
            // on small / landscape screens — the clock face alone is
            // ~260 dp, and with title + action row + nav padding the
            // sheet would clip its bottom buttons on a 600 dp viewport.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Pick a time",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timeState)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { showTimeDialog = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            timeConfirmed = true
                            showTimeDialog = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Set time", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/**
 * One of the two stacked rows in the custom-reminder sheet. Renders the
 * field's current value as a big tappable target so it reads as "this is
 * what's selected" rather than "tap this control." When the field is
 * still in its unset state, the value flips to the error color — the
 * single most important affordance, because the prior version let the
 * user proceed without ever noticing the time was on a silent default.
 */
@Composable
private fun FieldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    placeholderMissing: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = if (placeholderMissing) scheme.error else scheme.outline
    val valueColor = if (placeholderMissing) scheme.error else scheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                if (placeholderMissing) 1.5.dp else 1.dp,
                borderColor,
                RoundedCornerShape(14.dp)
            )
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = valueColor
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
