package io.github.rohmanhakim.tracktrainer.metronome

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputLatencyTest {

    @Test
    fun pendingFramesConvertToNanos() {
        // 2400 frames in flight at 48 kHz = 50 ms
        val ns = OutputLatency.estimateNs(
            framesWritten = 10_000,
            presentedFrame = 7_600,
            sampleRate = 48_000,
            framesPerBuffer = 480,
        )
        assertEquals(50_000_000L, ns)
    }

    @Test
    fun resultIsFlooredAtOneBufferQuantum() {
        // timestamp claims everything presented: the buffer quantum (480/48k = 10 ms) stands
        val ns = OutputLatency.estimateNs(
            framesWritten = 5_000,
            presentedFrame = 5_000,
            sampleRate = 48_000,
            framesPerBuffer = 480,
        )
        assertEquals(10_000_000L, ns)
    }

    @Test
    fun presentedAheadOfWriteCountClampsToZeroPending() {
        val ns = OutputLatency.estimateNs(
            framesWritten = 1_000,
            presentedFrame = 1_200,
            sampleRate = 48_000,
            framesPerBuffer = 480,
        )
        assertEquals(10_000_000L, ns)
    }

    @Test
    fun fallbackCoversFailedProbes() {
        assertEquals(50_000_000L, OutputLatency.FALLBACK_NS)
    }
}
