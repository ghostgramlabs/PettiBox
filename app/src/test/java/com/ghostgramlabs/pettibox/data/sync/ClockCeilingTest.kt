package com.ghostgramlabs.pettibox.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D03 recheck: stamps never pass the ceiling saved before the writes, whatever the wall clock does. */
class ClockCeilingTest {
    @Test fun a_wall_clock_jump_past_the_ceiling_only_counts_up() {
        var wall = 1_000L
        val clock = HybridClock(500L) { wall }
        clock.ceiling = 1_100L
        assertEquals(1_000L, clock.next())
        wall = 50_000L // a slow write, or the clock set forward
        val stamps = (1..50).map { clock.next() }
        assertTrue(stamps.all { it <= 1_100L })
        assertEquals((1_001L..1_050L).toList(), stamps)
    }

    @Test(expected = IllegalStateException::class)
    fun running_out_of_reserved_stamps_stops_rather_than_outrun_the_saved_clock() {
        val clock = HybridClock(100L) { 100L }
        clock.ceiling = 102L
        repeat(3) { clock.next() }
    }

    @Test fun without_a_ceiling_it_follows_the_wall_clock() {
        var wall = 10L
        val clock = HybridClock(0L) { wall }
        assertEquals(10L, clock.next())
        wall = 5_000L
        assertEquals(5_000L, clock.next())
    }
}
