package io.github.rohmanhakim.tracktrainer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSeekTest {
    @Test
    fun userSeekOwnsTargetUntilSettled() {
        val seek = PendingSeek()
        assertFalse(seek.inFlight)
        seek.onUserSeek(12_345L)
        assertTrue(seek.inFlight)
        assertEquals(12_345L, seek.targetMs)
    }

    @Test
    fun settleWithMatchingLandingResolvesUserSeek() {
        val seek = PendingSeek()
        seek.onUserSeek(5_000L)
        assertEquals(PendingSeek.Settled(5_000L, wasLoopSeek = false), seek.onSeekSettled(5_000L))
        assertFalse(seek.inFlight)
    }

    @Test
    fun settleOfSupersededSeekKeepsNewerTarget() {
        // seek-to-t1 lands after the owner already authored t2 — t2 is still in flight.
        val seek = PendingSeek()
        seek.onUserSeek(2_000L)
        seek.onUserSeek(3_000L)
        assertNull(seek.onSeekSettled(2_000L))
        assertEquals(3_000L, seek.targetMs)
        assertEquals(PendingSeek.Settled(3_000L, wasLoopSeek = false), seek.onSeekSettled(3_000L))
        assertFalse(seek.inFlight)
    }

    @Test
    fun loopSeekSettlesAtWhateverLandingItGot() {
        // CLOSEST_SYNC lands on the keyframe, not the requested position — that landing is
        // exactly the real restart point the A label must correct to.
        val seek = PendingSeek()
        seek.onLoopSeek()
        assertTrue(seek.inFlight)
        assertEquals(PendingSeek.Settled(307_000L, wasLoopSeek = true), seek.onSeekSettled(307_000L))
        assertFalse(seek.inFlight)
    }

    @Test
    fun userSeekSupersedesLoopSeek() {
        val seek = PendingSeek()
        seek.onLoopSeek()
        seek.onUserSeek(1_000L)
        // A loop landing arriving mid-user-seek no longer resolves the newer user seek.
        assertNull(seek.onSeekSettled(2_000L))
        assertEquals(1_000L, seek.targetMs)
    }

    @Test
    fun neverAuthoredSeekIsIgnored() {
        val seek = PendingSeek()
        assertNull(seek.onSeekSettled(1_000L))
        assertFalse(seek.inFlight)
    }

    @Test
    fun resetDropsBothKinds() {
        val seek = PendingSeek()
        seek.onUserSeek(1_000L)
        seek.reset()
        assertFalse(seek.inFlight)
        assertNull(seek.targetMs)

        seek.onLoopSeek()
        seek.reset()
        assertFalse(seek.inFlight)
    }
}