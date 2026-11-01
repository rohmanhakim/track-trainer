package io.github.rohmanhakim.tracktrainer.player

import io.github.rohmanhakim.tracktrainer.data.local.Preset
import kotlin.OptIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresetAutoSaverTest {

    private fun preset(
        trackId: Long = 1L,
        speed: Float = 1f,
        activeLoopPointId: Long? = null,
        loopEnabled: Boolean = false,
    ) = Preset(
        trackId = trackId,
        speed = speed,
        activeLoopPointId = activeLoopPointId,
        loopEnabled = loopEnabled,
    )

    @Test
    fun rapidChangesCoalesceIntoOneWriteWithLatestSnapshot() = runTest {
        val saved = mutableListOf<Preset>()
        val saver = PresetAutoSaver(this, debounceMs = 500) { saved += it }
        saver.schedule(preset(speed = 0.5f))
        saver.schedule(preset(speed = 0.75f))
        saver.schedule(preset(speed = 1.25f))
        advanceTimeBy(600)
        assertEquals(1, saved.size)
        assertEquals(1.25f, saved[0].speed, 0.001f)
    }

    @Test
    fun flushWritesImmediatelyAndCancelsTheDebounce() = runTest {
        val saved = mutableListOf<Preset>()
        val saver = PresetAutoSaver(this, debounceMs = 500) { saved += it }
        saver.schedule(preset(speed = 0.75f))
        saver.flush()
        assertEquals(1, saved.size)
        assertEquals(0.75f, saved[0].speed, 0.001f)
        advanceTimeBy(1000) // timer cancelled — no second write
        assertEquals(1, saved.size)
    }

    @Test
    fun flushWithNothingPendingWritesNothing() = runTest {
        val saved = mutableListOf<Preset>()
        val saver = PresetAutoSaver(this, debounceMs = 500) { saved += it }
        saver.flush()
        advanceTimeBy(1000)
        assertEquals(0, saved.size)
    }

    @Test
    fun trackSwitchWritesOldSnapshotBeforeNewOne() = runTest {
        val saved = mutableListOf<Preset>()
        val saver = PresetAutoSaver(this, debounceMs = 500) { saved += it }
        saver.schedule(preset(trackId = 1, speed = 0.5f))
        saver.schedule(preset(trackId = 2, speed = 1.5f)) // switch: track 1 must not drop
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals(1L, saved[0].trackId)
        advanceTimeBy(600)
        assertEquals(2, saved.size)
        assertEquals(2L, saved[1].trackId)
        assertEquals(1.5f, saved[1].speed, 0.001f)
    }

    @Test
    fun loopStateFieldsReachTheSnapshot() = runTest {
        val saved = mutableListOf<Preset>()
        val saver = PresetAutoSaver(this, debounceMs = 500) { saved += it }
        saver.schedule(preset(activeLoopPointId = 7, loopEnabled = true))
        advanceTimeBy(600)
        assertEquals(1, saved.size)
        assertEquals(7L, saved[0].activeLoopPointId)
        assertTrue(saved[0].loopEnabled)
    }
}