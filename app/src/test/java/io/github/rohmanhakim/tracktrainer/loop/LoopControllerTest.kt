package io.github.rohmanhakim.tracktrainer.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.OptIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LoopControllerTest {
    private class FakeLoopPlayer : LoopPlayer {
        var positionMs: Long = 0
        override val currentPositionMs: Long
            get() { positionReads++; return positionMs }
        override val durationMs: Long get() = 100_000
        var isPlayingValue: Boolean = false
        override val isPlaying: Boolean get() = isPlayingValue
        val seekToCalls = mutableListOf<Long>()
        override fun seekTo(positionMs: Long) {
            seekToCalls.add(positionMs)
            this.positionMs = positionMs // seek lands
        }
        private var _volume: Float = 1f
        override val volume: Float get() = _volume
        override fun setVolume(volume: Float) { _volume = volume }
        var positionReads: Int = 0
    }

    @Test
    fun seeksBackToAWhenReachingBAndStaysLooped() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 5000
            advanceTimeBy(41) // poll starts at 0, tick at 40 → detects B, seeks to A
            assertEquals(listOf(1000L), player.seekToCalls)
            assertTrue(controller.state.value.loopEnabled)

            player.positionMs = 5000
            advanceTimeBy(41) // next tick → seeks again (loop-until-off)
            assertEquals(listOf(1000L, 1000L), player.seekToCalls)

            controller.setLoopEnabled(false)
            player.positionMs = 5000
            advanceTimeBy(41) // loop off → no seek
            assertEquals(listOf(1000L, 1000L), player.seekToCalls)

            controller.detach()
        }
    }

    @Test
    fun seamMutesThenRestoresVolume() = runBlocking {
        runTest {
            val player = FakeLoopPlayer().apply { setVolume(0.7f) }
            val controller = LoopController(this, seamFadeMs = 5)
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 5000
            advanceTimeBy(42) // poll starts at 0, tick at 40 → seek to A, mute volume
            assertEquals(0f, player.volume)
            advanceTimeBy(5) // restore job fires at +5 ms
            assertEquals(0.7f, player.volume)

            controller.detach()
        }
    }

    @Test
    fun invalidBoundsNeverLoop() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            controller.attach(player)
            controller.setA(5000)
            controller.setB(1000) // A > B → invalid
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 9000
            advanceTimeBy(41) // tick → invalid bounds, no seek
            assertTrue(player.seekToCalls.isEmpty())

            controller.detach()
        }
    }

    @Test
    fun pausedAtBDoesNotSeek() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            // isPlayingValue stays false

            player.positionMs = 5000
            advanceTimeBy(41) // tick → not playing, no seek
            assertTrue(player.seekToCalls.isEmpty())

            controller.detach()
        }
    }

    @Test
    fun pollCadenceIsAround40ms() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            controller.attach(player)
            controller.setLoopEnabled(false) // polls only read position
            player.positionMs = 1234

            advanceTimeBy(1100) // ~27 ticks over 1100 ms
            // 1000 ms / 40 ms ≈ 25 polls; allow scheduling slack (30–50 ms cadence → 20–33/s)
            assertTrue(player.positionReads in 20..33)

            controller.detach()
        }
    }

    @Test
    fun seamOverrunIsWithinTolerance() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 5030 // 30 ms past B at poll time → overrun = 30 ms ≤ 100 ms
            advanceTimeBy(41) // tick at 40 → detects B, seeks to A

            // Seam fired: seek to A happened, overrun (5030 - 5000 = 30) is within tolerance
            assertEquals(listOf(1000L), player.seekToCalls)

            controller.detach()
        }
    }

    @Test
    fun seamEventIsEmittedForEachSeam() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            val events = mutableListOf<SeamEvent>()
            val collector = launch { controller.seamEvents.collect { events += it } }
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 5030
            advanceTimeBy(41) // tick at 40 → seam with 30 ms overrun
            runCurrent()

            assertEquals(1, events.size)
            assertEquals(5030L, events[0].boundaryPositionMs)
            assertEquals(1000L, events[0].seekToMs)
            assertEquals(30L, events[0].overrunMs)

            collector.cancel()
            controller.detach()
        }
    }

    @Test
    fun seamEventsAreNotDroppedWhileCollectorIsBusy() = runBlocking {
        runTest {
            val player = FakeLoopPlayer()
            val controller = LoopController(this, pollIntervalMs = 40)
            val events = mutableListOf<SeamEvent>()
            // Collector takes 200 ms per event — busier than the 40 ms poll. A
            // zero-capacity SharedFlow + tryEmit silently drops here (the on-device
            // "0 seam events" bug); the buffered flow must deliver both.
            val collector = launch {
                controller.seamEvents.collect {
                    events += it
                    delay(200)
                }
            }
            controller.attach(player)
            controller.setA(1000)
            controller.setB(5000)
            controller.setLoopEnabled(true)
            player.isPlayingValue = true

            player.positionMs = 5000
            advanceTimeBy(41) // seam 1 at tick 40 → collector busy until ~240
            player.positionMs = 5000
            advanceTimeBy(41) // seam 2 at tick 80 → collector still busy → must not drop
            advanceTimeBy(300) // let the collector drain the buffer

            assertEquals(listOf(5000L, 5000L), events.map { it.boundaryPositionMs })

            collector.cancel()
            controller.detach()
        }
    }
}
