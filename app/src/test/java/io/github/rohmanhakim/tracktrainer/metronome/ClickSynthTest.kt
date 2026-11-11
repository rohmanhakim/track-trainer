package io.github.rohmanhakim.tracktrainer.metronome

import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickSynthTest {
    private val synth = ClickSynth(sampleRate = 48_000)

    @Test
    fun bufferLengthMatchesDurationAtSampleRate() {
        val buf = synth.synth(ClickSound.CLICK, volume = 1f, durationMs = 20)
        assertEquals(960, buf.size)
    }

    @Test
    fun buffersAreAudibleAtFullVolume() {
        for (sound in ClickSound.entries) {
            val peak = synth.synth(sound, 1f).maxOf { abs(it.toInt()) }
            assertTrue("$sound peak was $peak", peak > 8000)
        }
    }

    @Test
    fun buffersStartAndEndAtZeroToAvoidSelfClicks() {
        for (sound in ClickSound.entries) {
            val buf = synth.synth(sound, 1f)
            assertEquals("$sound first sample", 0, buf.first().toInt())
            assertEquals("$sound last sample", 0, buf.last().toInt())
        }
    }

    @Test
    fun volumeScalesTheWaveform() {
        val fullPeak = synth.synth(ClickSound.CLICK, 1f).maxOf { abs(it.toInt()) }
        val quietPeak = synth.synth(ClickSound.CLICK, 0.25f).maxOf { abs(it.toInt()) }
        assertEquals(fullPeak * 0.25f, quietPeak.toFloat(), fullPeak * 0.01f)
    }

    @Test
    fun zeroVolumeIsSilence() {
        val buf = synth.synth(ClickSound.BEEP, 0f)
        assertTrue(buf.all { it == 0.toShort() })
    }

    @Test
    fun clickAndBeepAreDifferentWaveforms() {
        val click = synth.synth(ClickSound.CLICK, 1f)
        val beep = synth.synth(ClickSound.BEEP, 1f)
        assertFalse(click.contentEquals(beep))
    }

    @Test
    fun sampleRateDrivesBufferLength() {
        val buf = ClickSynth(24_000).synth(ClickSound.CLICK, 1f, durationMs = 20)
        assertEquals(480, buf.size)
    }
}
