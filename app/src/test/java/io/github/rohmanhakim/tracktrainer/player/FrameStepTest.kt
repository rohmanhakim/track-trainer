package io.github.rohmanhakim.tracktrainer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStepTest {
    @Test
    fun frameDurationMs_knownFrameRate() {
        assertEquals(33L, frameDurationMs(30f)) // ~30 fps
        assertEquals(16L, frameDurationMs(60f)) // ~60 fps (truncated toward zero)
        assertEquals(40L, frameDurationMs(25f)) // ~25 fps
    }

    @Test
    fun frameDurationMs_unknownOrZeroFallsBackTo33ms() {
        assertEquals(33L, frameDurationMs(0f))
        assertEquals(33L, frameDurationMs(-1f))
    }
}
