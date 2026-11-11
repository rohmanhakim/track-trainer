package io.github.rohmanhakim.tracktrainer.countin

import io.github.rohmanhakim.tracktrainer.loop.LoopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decision-4 matrix: fire on play-from-stopped at 00:00 / armed A; never mid-track. */
class CountInTriggersTest {

    private val loopArmedAt5k = LoopState(positionA = 5_000L, positionB = 20_000L, loopEnabled = true)
    private val loopDisarmedAt5k = LoopState(positionA = 5_000L, positionB = 20_000L, loopEnabled = false)

    @Test
    fun playAtZeroCounts() {
        assertEquals(
            CountInTrigger.PLAY_AT_0000,
            CountInTriggers.onPlayFromStopped(0L, LoopState()),
        )
        // Within epsilon of 00:00 still counts (decision 4: ≤100 ms).
        assertEquals(
            CountInTrigger.PLAY_AT_0000,
            CountInTriggers.onPlayFromStopped(100L, LoopState()),
        )
    }

    @Test
    fun resumeFromPauseMidTrackPlainsPlays() {
        assertNull(CountInTriggers.onPlayFromStopped(101L, LoopState()))
        assertNull(CountInTriggers.onPlayFromStopped(10_000L, loopArmedAt5k))
    }

    @Test
    fun playExactlyAtAWithLoopArmedCounts() {
        assertEquals(
            CountInTrigger.PLAY_AT_LOOP_A,
            CountInTriggers.onPlayFromStopped(5_000L, loopArmedAt5k),
        )
        // Epsilon applies to A too ("exactly at A", ≤100 ms).
        assertEquals(
            CountInTrigger.PLAY_AT_LOOP_A,
            CountInTriggers.onPlayFromStopped(5_100L, loopArmedAt5k),
        )
        assertEquals(
            CountInTrigger.PLAY_AT_LOOP_A,
            CountInTriggers.onPlayFromStopped(4_900L, loopArmedAt5k),
        )
    }

    @Test
    fun nearButNotAtAPlainsPlays() {
        assertNull(CountInTriggers.onPlayFromStopped(5_101L, loopArmedAt5k))
        assertNull(CountInTriggers.onPlayFromStopped(4_899L, loopArmedAt5k))
    }

    @Test
    fun atAWithoutArmedLoopPlainsPlays() {
        assertNull(CountInTriggers.onPlayFromStopped(5_000L, loopDisarmedAt5k))
    }

    @Test
    fun zeroWinsOverArmedAWhenTheyOverlap() {
        assertEquals(
            CountInTrigger.PLAY_AT_0000,
            CountInTriggers.onPlayFromStopped(0L, LoopState(positionA = 0L, positionB = 5_000L, loopEnabled = true)),
        )
    }

    @Test
    fun unsetACannotTriggerAtA() {
        // 00:00 doesn't need A (branch one), but mid-track with A unset stays plain play.
        assertEquals(
            CountInTrigger.PLAY_AT_0000,
            CountInTriggers.onPlayFromStopped(0L, LoopState(positionA = -1L, loopEnabled = true)),
        )
        assertNull(CountInTriggers.onPlayFromStopped(900L, LoopState(positionA = -1L, loopEnabled = true)))
    }
}