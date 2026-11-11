package io.github.rohmanhakim.tracktrainer.metronome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClickBufferTest {

    @Test
    fun offsetsConvertWallDeltasToFramesFromTheAnchor() {
        val anchor = 1_000_000_000L
        val walls = listOf(anchor, anchor + 500_000_000L, anchor + 1_000_000_000L)
        val offsets = ClickBuffer.offsetFrames(walls, anchor, sampleRate = 48_000)
        assertArrayEquals(longArrayOf(0L, 24_000L, 48_000L), offsets)
    }

    @Test
    fun offsetsIgnoreTheAbsoluteAnchorValue() {
        val anchor = 9_000_000_000_000L
        val walls = listOf(anchor, anchor + 500_000_000L)
        val offsets = ClickBuffer.offsetFrames(walls, anchor, sampleRate = 48_000)
        assertArrayEquals(longArrayOf(0L, 24_000L), offsets)
    }

    @Test
    fun totalFramesCoversLastTickPlusItsPcm() {
        val offsets = longArrayOf(0L, 24_000L)
        assertEquals(24_960, ClickBuffer.totalFrames(offsets, pcmFrames = 960))
    }

    @Test
    fun interleavePlacesPcmAtEveryOffsetWithSilenceBetween() {
        val pcm = shortArrayOf(1, 2, 3, 4)
        val offsets = longArrayOf(0L, 10L)
        val buffer = ClickBuffer.interleave(offsets, pcm, totalFrames = 14)
        assertEquals(14, buffer.size)
        assertArrayEquals(
            shortArrayOf(
                1, 2, 3, 4, 0, 0, 0, 0, 0, 0,
                1, 2, 3, 4,
            ),
            buffer,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTickScheduledBeforeTheAnchor() {
        ClickBuffer.offsetFrames(listOf(5L), anchorWallNanos = 10L, sampleRate = 48_000)
    }
}
