package io.github.rohmanhakim.tracktrainer.countin

import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import io.github.rohmanhakim.tracktrainer.metronome.ClickSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.nanoseconds

/**
 * T02 acceptance (unit level): trigger fires freeze the player, ticks are spaced
 * `(60000/bpm) ÷ speed`, resume lands EXACTLY on the final tick — including after
 * prep lateness shifts the burst (decision A compensation) — and every cancel path
 * stops clicks without ever resuming. Virtual time; clock and player are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountInControllerTest {

    private class FakePlayer(private val clockNanos: () -> Long) : CountInPlayer {
        /** ("pause"|"play", wall instant) in call order. */
        val events = mutableListOf<Pair<String, Long>>()
        val playCount: Int get() = events.count { it.first == "play" }
        override fun pause() {
            events.add("pause" to clockNanos())
        }

        override fun play() {
            events.add("play" to clockNanos())
        }
    }

    private class FakeClicker : CountInClicker {
        /** Simulated buffer-prep lateness before the AudioTrack starts (decision A). */
        var startDelayNanos = 0L
        /** When set, [play] suspends this long instead of returning (cancel-during-prep). */
        var prepStallsNanos: Long? = null
        var failWith: RuntimeException? = null
        var stopCalls = 0
        var lastTicks: List<ClickSchedule.Tick> = emptyList()
        var lastAnchorMediaMs = 0L

        override suspend fun play(
            ticks: List<ClickSchedule.Tick>,
            anchorWallNanos: Long,
            anchorMediaMs: Long,
            playbackSpeed: Float,
            sound: ClickSound,
            volume: Float,
        ): Long {
            lastTicks = ticks
            lastAnchorMediaMs = anchorMediaMs
            failWith?.let { throw it }
            prepStallsNanos?.let { delay(it.nanoseconds) }
            return anchorWallNanos + startDelayNanos
        }

        override fun stop() {
            stopCalls++
        }
    }

    private fun TestScope.newController(
        clicker: FakeClicker,
        player: FakePlayer,
        config: CountInConfig = HardcodedCountInConfig(),
        speed: Float = 1f,
    ) = CountInController(
        scope = this,
        clicker = clicker,
        player = player,
        config = { config },
        playbackSpeed = { speed },
        clockNanos = { testScheduler.currentTime * 1_000_000L },
    )

    private fun TestScope.fakePlayer() = FakePlayer { testScheduler.currentTime * 1_000_000L }

    @Test
    fun countFreezesAndResumesExactlyOnFinalTick() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, anchorMediaMs = 0))
        // Decision 2: frozen before any tick, schedule exposed for the T03 overlay.
        assertEquals(listOf("pause" to 0L), player.events)
        val initial = controller.state.value as CountInState.Counting
        assertEquals(4, initial.count)
        assertEquals(500f, initial.periodMs)
        assertEquals(-1, initial.tickIndex)
        assertNull(initial.startWallNanos)

        runCurrent() // burst "starts" instantly (no prep delay) → first tick presents
        val counting = controller.state.value as CountInState.Counting
        assertEquals(0L, counting.startWallNanos)
        assertEquals(
            listOf(0L, 500_000_000L, 1_000_000_000L, 1_500_000_000L),
            counting.tickWallNanos,
        )
        assertEquals(1_500_000_000L, counting.resumeWallNanos)
        assertEquals(0, counting.tickIndex)

        advanceTimeBy(499) // ticks at 500/1000…
        assertEquals(0, (controller.state.value as CountInState.Counting).tickIndex)
        advanceTimeBy(2)
        assertEquals(1, (controller.state.value as CountInState.Counting).tickIndex)
        advanceTimeBy(500)
        assertEquals(2, (controller.state.value as CountInState.Counting).tickIndex)
        advanceTimeBy(499) // not yet the final tick → still frozen
        assertEquals(2, (controller.state.value as CountInState.Counting).tickIndex)
        assertEquals(0, player.playCount)

        advanceTimeBy(2) // final tick = resume instant
        assertEquals(listOf("pause" to 0L, "play" to 1_500_000_000L), player.events)
        assertEquals(CountInState.Go, controller.state.value)
        assertFalse(controller.isCounting)
    }
    @Test
    fun prepLatenessShiftsClicksAndResumeTogether() = runTest {
        // Decision A: the burst's AudioTrack starts 500 ms after the anchor (T01
        // measured ~480–800 ms); ticks and resume must all shift by it so the final
        // tick and player.play() stay sample-aligned.
        val player = fakePlayer()
        val clicker = FakeClicker().apply { startDelayNanos = 500_000_000L }
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, anchorMediaMs = 0))
        runCurrent()
        val counting = controller.state.value as CountInState.Counting
        assertEquals(
            listOf(500_000_000L, 1_000_000_000L, 1_500_000_000L, 2_000_000_000L),
            counting.tickWallNanos,
        )
        assertEquals(2_000_000_000L, counting.resumeWallNanos)

        advanceTimeBy(1_999) // NOT the nominal anchor+1500
        assertEquals(0, player.playCount)
        advanceTimeBy(2) // actual start (500) + 3·period (1500)
        assertEquals(listOf("pause" to 0L, "play" to 2_000_000_000L), player.events)
        assertEquals(CountInState.Go, controller.state.value)
    }

    @Test
    fun speedScalesWallPeriod() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player, speed = 0.5f)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, anchorMediaMs = 0))
        runCurrent()
        val counting = controller.state.value as CountInState.Counting
        assertEquals(1_000f, counting.periodMs) // (60000/120) ÷ 0.5
        assertEquals(3_000_000_000L, counting.resumeWallNanos) // 3 × 1000 ms
        advanceTimeBy(2_999)
        assertEquals(0, player.playCount)
        advanceTimeBy(2)
        assertEquals(3_000_000_000L, player.events.last().second)
    }

    @Test
    fun barsModeUsesFourBeatsPerBar() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(
            clicker, player,
            config = HardcodedCountInConfig(countInBars = 1, countInClicks = 1), // bars wins
        )

        assertTrue(controller.start(CountInTrigger.LOOP_SEAM, anchorMediaMs = 5_000))
        runCurrent()
        assertEquals(4, (controller.state.value as CountInState.Counting).count)
        assertEquals(6_500L, clicker.lastTicks.last().mediaMs) // A 5000 + 3·500 media-ms
        advanceTimeBy(1_501)
        assertEquals(1, player.playCount)
    }

    @Test
    fun clicksModeClampsToEight() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player, config = HardcodedCountInConfig(countInClicks = 99))

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        runCurrent()
        assertEquals(8, (controller.state.value as CountInState.Counting).count)
        advanceTimeBy(3_501) // 7 periods
        assertEquals(1, player.playCount)
    }
    @Test
    fun subdivisionAddsHalfBeatsButResumeStaysOnLastMainTick() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player, config = HardcodedCountInConfig(subdivision = true))

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        runCurrent()
        // 4 main ticks + 3 off-beats strictly between them — never after the last.
        assertEquals(7, clicker.lastTicks.size)
        assertEquals(
            listOf(0L, 250L, 500L, 750L, 1_000L, 1_250L, 1_500L),
            clicker.lastTicks.map { it.mediaMs },
        )
        assertEquals(1_500_000_000L, (controller.state.value as CountInState.Counting).resumeWallNanos)
        advanceTimeBy(1_501)
        assertEquals(1, player.playCount)
    }

    @Test
    fun disabledNeverFires() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player, config = HardcodedCountInConfig(enabled = false))

        assertFalse(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        assertFalse(controller.isEnabled)
        assertTrue(player.events.isEmpty())
        assertEquals(CountInState.Idle, controller.state.value)
        advanceTimeBy(10_000)
        assertEquals(0, player.playCount) // caller is expected to plain-play instead
    }

    @Test
    fun startWhileCountingIsRejected() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        assertFalse(controller.start(CountInTrigger.LOOP_SEAM, 0))
        assertEquals(1, player.events.count { it.first == "pause" })
        advanceTimeBy(1_501)
        assertEquals(1, player.playCount) // exactly one count ran
    }

    @Test
    fun cancelStopsClicksAndNeverResumes() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.LOOP_SEAM, 5_000))
        runCurrent()
        advanceTimeBy(600) // mid-count (ticks 0 and 1 done)
        controller.cancel(CountCancelReason.TRANSPORT)

        assertEquals(1, clicker.stopCalls)
        assertEquals(CountInState.Idle, controller.state.value)
        assertFalse(controller.isCounting)
        advanceUntilIdle()
        assertEquals(0, player.playCount) // frozen stays frozen
        assertEquals(listOf("pause"), player.events.map { it.first })
    }
    @Test
    fun cancelDuringPrepReleasesBurstWithoutResume() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker().apply { prepStallsNanos = 50_000_000_000L }
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        runCurrent() // run() is now suspended inside clicker.play (long prep)
        advanceTimeBy(100)
        controller.cancel(CountCancelReason.SEEK)

        assertEquals(1, clicker.stopCalls)
        assertEquals(CountInState.Idle, controller.state.value)
        advanceUntilIdle()
        assertEquals(0, player.playCount)
    }

    @Test
    fun burstFailureDegradesToPlainPlayback() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker().apply { failWith = RuntimeException("AudioTrack init failed") }
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        runCurrent()
        // Not frozen forever: playback resumes without clicks (decision-A failure path).
        assertEquals(listOf("pause", "play"), player.events.map { it.first })
        assertEquals(CountInState.Go, controller.state.value)
        assertFalse(controller.isCounting)
    }

    @Test
    fun goAllowsTheNextCount() = runTest {
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_0000, 0))
        advanceTimeBy(1_501)
        assertEquals(CountInState.Go, controller.state.value)
        assertFalse(controller.isCounting)

        assertTrue(controller.start(CountInTrigger.LOOP_SEAM, 0)) // from Go
        advanceTimeBy(1_501)
        assertEquals(2, player.playCount)
    }

    @Test
    fun twentyConsecutiveSeamCountsShareOneSchedule() = runTest {
        // The In-scope "≥20 consecutive seam counts with identical spacing": every
        // iteration must produce the same burst start (+fixed prep) and resume
        // (+1500 ms schedule) — the unit twin of the 33-seam on-device soak.
        val player = fakePlayer()
        val clicker = FakeClicker().apply { startDelayNanos = 60_000_000L }
        val controller = newController(clicker, player)

        repeat(20) {
            val anchor = testScheduler.currentTime * 1_000_000L
            assertTrue(controller.start(CountInTrigger.LOOP_SEAM, anchorMediaMs = 7_000))
            runCurrent()
            val counting = controller.state.value as CountInState.Counting
            val start = anchor + 60_000_000L // actual burst start (fixed prep)
            assertEquals(start, counting.startWallNanos)
            // Actual (post-prep) instants: burst start + the unchanged 500 ms spacing.
            assertEquals(
                listOf(start, start + 500_000_000L, start + 1_000_000_000L, start + 1_500_000_000L),
                counting.tickWallNanos,
            )
            advanceTimeBy(1_561) // 60 prep + 3×500 schedule + margin
            assertEquals(CountInState.Go, controller.state.value)
            assertEquals(anchor + 1_560_000_000L, player.events.last().second)
        }
        assertEquals(20, player.playCount)
    }

    @Test
    fun anchorMediaOffsetDoesNotShiftWallSchedule() = runTest {
        // Trigger (c): count anchored at A = 60 s — wall instants depend on the
        // media DELTA from the anchor, never on the absolute media position.
        val player = fakePlayer()
        val clicker = FakeClicker()
        val controller = newController(clicker, player)

        assertTrue(controller.start(CountInTrigger.PLAY_AT_LOOP_A, anchorMediaMs = 60_000))
        runCurrent()
        assertEquals(
            listOf(60_000L, 60_500L, 61_000L, 61_500L),
            clicker.lastTicks.map { it.mediaMs },
        )
        assertEquals(60_000L, clicker.lastAnchorMediaMs)
        advanceTimeBy(1_501)
        assertEquals(1_500_000_000L, player.events.last().second)
    }
}
