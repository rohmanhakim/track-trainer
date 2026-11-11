package io.github.rohmanhakim.tracktrainer.metronome.spike

import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import io.github.rohmanhakim.tracktrainer.metronome.ClickSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackAnalysisTest {

    private val rate = 48_000
    private val click = ClickSynth(rate).synth(ClickSound.CLICK, 1f)

    /** Silence with clicks planted at the given ms offsets. */
    private fun recording(clickAtMs: List<Long>, totalMs: Long = 3_000): ShortArray {
        val pcm = ShortArray(rate * totalMs.toInt() / 1000)
        for (ms in clickAtMs) {
            click.copyInto(pcm, destinationOffset = rate * ms.toInt() / 1000)
        }
        return pcm
    }

    @Test
    fun detectsPlantedClicksAtTheirSampleOffsets() {
        val pcm = recording(listOf(500, 1_000, 1_500, 2_000))
        val onsets = LoopbackAnalysis.detectOnsets(pcm, rate)
        assertEquals(4, onsets.size)
        val expected = listOf(500, 1_000, 1_500, 2_000).map { it * rate / 1000 }
        for ((i, e) in expected.withIndex()) {
            // onset window is 2.5 ms: detection lands within one window of the plant
            assertTrue("onset $i = ${onsets[i]} vs $e", onsets[i] - e in 0..rate * 5 / 1000)
        }
    }

    @Test
    fun silentRecordingProducesNoOnsets() {
        assertTrue(LoopbackAnalysis.detectOnsets(ShortArray(rate), rate).isEmpty())
    }

    @Test
    fun constantLatencyKeepsSpacingWithinBudget() {
        // clicks sound 30 ms late (constant chain) — spacing must stay exact
        val scheduled = listOf(0L, 500L, 1_000L, 1_500L)
        val onsets = scheduled.map { it + 30 }
        val a = LoopbackAnalysis.analyzeBurst(onsets, scheduled, playAtMs = -500)
        assertTrue(a.withinBudget)
        assertTrue(a.spacingErrorMaxMs <= 10f)
        assertEquals(4, a.matchedOnsets)
        assertEquals(530f, a.firstChainMs!!)
    }

    @Test
    fun driftIsDetected() {
        val scheduled = listOf(0L, 500L, 1_000L, 1_500L)
        val onsets = listOf(30L, 545L, 1_060L, 1_590L) // gaps 515, 515, 530
        val a = LoopbackAnalysis.analyzeBurst(onsets, scheduled, playAtMs = null)
        assertTrue(a.spacingErrorMaxMs > 10f)
        assertTrue(!a.withinBudget)
    }

    @Test
    fun missingOnsetIsReportedAsCountMismatch() {
        val scheduled = listOf(0L, 500L, 1_000L, 1_500L)
        val onsets = listOf(30L, 530L, 1_530L) // tick 2 lost
        val a = LoopbackAnalysis.analyzeBurst(onsets, scheduled, playAtMs = null)
        assertEquals(3, a.matchedOnsets)
        assertTrue(!a.withinBudget)
    }

    @Test
    fun onsetsOutsideTheBurstWindowAreIgnored() {
        val scheduled = listOf(0L, 500L, 1_000L, 1_500L)
        // noise far before the burst must not enter the window
        val onsets = listOf(-5_000L, 30L, 530L, 1_030L, 1_530L, 9_000L)
        val a = LoopbackAnalysis.analyzeBurst(onsets, scheduled, playAtMs = null)
        assertEquals(4, a.matchedOnsets)
        assertTrue(a.withinBudget)
    }
}
