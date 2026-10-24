package com.ghostgramlabs.pettibox.ui.components

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReminderPresetTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun tonightUsesTodayWhenEveningHasNotPassed() {
        val now = utcMillis(year = 2026, month = Calendar.MAY, day = 20, hour = 12)

        val result = ReminderPreset.TONIGHT.toEpochMillis(now, eveningHour = 21)

        assertEquals(
            utcMillis(year = 2026, month = Calendar.MAY, day = 20, hour = 21),
            result
        )
    }

    @Test
    fun tonightRollsToTomorrowWhenEveningPassed() {
        val now = utcMillis(year = 2026, month = Calendar.MAY, day = 20, hour = 22)

        val result = ReminderPreset.TONIGHT.toEpochMillis(now, eveningHour = 21)

        assertEquals(
            utcMillis(year = 2026, month = Calendar.MAY, day = 21, hour = 21),
            result
        )
    }

    @Test
    fun tomorrowUsesConfiguredMorningTime() {
        val now = utcMillis(year = 2026, month = Calendar.MAY, day = 20, hour = 22)

        val result = ReminderPreset.TOMORROW.toEpochMillis(
            now = now,
            morningHour = 8,
            morningMinute = 30
        )

        assertEquals(
            utcMillis(year = 2026, month = Calendar.MAY, day = 21, hour = 8, minute = 30),
            result
        )
    }

    @Test
    fun onSaturdayAfternoonWeekendAndNextWeekDiffer() {
        // Sat Oct 24 2026, after the morning time.
        val now = utcMillis(year = 2026, month = Calendar.OCTOBER, day = 24, hour = 13)

        assertEquals(
            utcMillis(year = 2026, month = Calendar.OCTOBER, day = 31, hour = 9),
            ReminderPreset.WEEKEND.toEpochMillis(now)
        )
        assertEquals(
            utcMillis(year = 2026, month = Calendar.OCTOBER, day = 26, hour = 9),
            ReminderPreset.NEXT_WEEK.toEpochMillis(now)
        )
    }

    @Test
    fun nextWeekIsNeverTheSameAsTomorrow() {
        // Sun Oct 25 2026: tomorrow is Monday, so "Next week" is the Monday after.
        val now = utcMillis(year = 2026, month = Calendar.OCTOBER, day = 25, hour = 13)

        assertEquals(
            utcMillis(year = 2026, month = Calendar.NOVEMBER, day = 2, hour = 9),
            ReminderPreset.NEXT_WEEK.toEpochMillis(now)
        )
    }

    @Test
    fun nextWeekFromMidweekIsTheComingMonday() {
        // Wed Oct 21 2026.
        val now = utcMillis(year = 2026, month = Calendar.OCTOBER, day = 21, hour = 13)

        assertEquals(
            utcMillis(year = 2026, month = Calendar.OCTOBER, day = 26, hour = 9),
            ReminderPreset.NEXT_WEEK.toEpochMillis(now)
        )
    }

    @Test
    fun weekendOnSundayMorningIsToday() {
        // Sun Oct 25 2026, before 9 AM: still this weekend.
        val early = utcMillis(year = 2026, month = Calendar.OCTOBER, day = 25, hour = 7)
        assertEquals(
            utcMillis(year = 2026, month = Calendar.OCTOBER, day = 25, hour = 9),
            ReminderPreset.WEEKEND.toEpochMillis(early)
        )
        // Sunday afternoon: next Saturday.
        val late = utcMillis(year = 2026, month = Calendar.OCTOBER, day = 25, hour = 13)
        assertEquals(
            utcMillis(year = 2026, month = Calendar.OCTOBER, day = 31, hour = 9),
            ReminderPreset.WEEKEND.toEpochMillis(late)
        )
    }

    @Test
    fun customReturnsNullSoCallerCanOpenPicker() {
        assertEquals(null, ReminderPreset.CUSTOM.toEpochMillis())
    }

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
