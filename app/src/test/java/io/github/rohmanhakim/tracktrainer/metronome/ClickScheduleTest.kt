package io.github.rohmanhakim.tracktrainer.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickScheduleTest {

    @Test
    fun periodMsMapsBpmToSixtyThousandOverBpm() {
        assertEquals(1000f, ClickSchedule.periodMs(60f), 0.001f)
        assertEquals(500f, ClickSchedule.periodMs(120f), 0.001f)
        assertEquals(1000f / 3f, ClickSchedule.periodMs(180f), 0.01f)
    }

    @Test
    fun periodMsFallsBackToDefaultBpmWhenUnset() {
        assertEquals(500f, ClickSchedule.periodMs(0f), 0.001f)
        assertEquals(500f, ClickSchedule.periodMs(-10f), 0.001f)
    }

    @Test
    fun countTicksGeneratesNTicksFromTheAnchor() {
        val ticks = ClickSchedule.countTicks(4, 500f, subdivision = false)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), ticks.map { it.mediaMs })
        assertTrue(ticks.all { it.isDownbeat })
    }

    @Test
    fun subdivisionAddsHalfBeatOffsBetweenMainTicksOnly() {
        val ticks = ClickSchedule.countTicks(4, 500f, subdivision = true)
        assertEquals(
            listOf(0L, 250L, 500L, 750L, 1000L, 1250L, 1500L),
            ticks.map { it.mediaMs },
        )
        assertFalse(ticks[1].isDownbeat)
        // the resume tick stays the last one — no off-beat after it
        assertTrue(ticks.last().isDownbeat)
        assertEquals(3, ticks.count { !it.isDownbeat })
    }

    @Test
    fun singleTickCountHasNoOffBeat() {
        val ticks = ClickSchedule.countTicks(1, 500f, subdivision = true)
        assertEquals(1, ticks.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTickCount() {
        ClickSchedule.countTicks(0, 500f, subdivision = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositivePeriod() {
        ClickSchedule.countTicks(4, 0f, subdivision = false)
    }

    @Test
    fun wallTimeRunsMediaOffsetAtPlaybackSpeed() {
        val anchor = 1_000_000_000L
        assertEquals(
            anchor + 500_000_000L,
            ClickSchedule.wallTimeNanos(anchor, 0L, 500L, 1f),
        )
        // 0.5x: a media half-second occupies a full wall second
        assertEquals(
            anchor + 1_000_000_000L,
            ClickSchedule.wallTimeNanos(anchor, 0L, 500L, 0.5f),
        )
        // 1.5x: 500 media ms -> 333.33 wall ms
        assertEquals(
            anchor + 333_333_333L,
            ClickSchedule.wallTimeNanos(anchor, 0L, 500L, 1.5f),
        )
    }

    @Test
    fun wallTimeAnchorsOnNonZeroMediaPosition() {
        val anchor = 77_000_000_000L
        assertEquals(
            anchor + 500_000_000L,
            ClickSchedule.wallTimeNanos(anchor, 4000L, 4500L, 1f),
        )
    }

    @Test
    fun wallTimeTreatsNonPositiveSpeedAsNormalRate() {
        val anchor = 42L
        assertEquals(
            anchor + 500_000_000L,
            ClickSchedule.wallTimeNanos(anchor, 0L, 500L, 0f),
        )
    }
}
