package io.github.rohmanhakim.tracktrainer.countin

import android.os.SystemClock
import android.util.Log
import io.github.rohmanhakim.tracktrainer.loop.LoopState
import io.github.rohmanhakim.tracktrainer.metronome.ClickSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.nanoseconds

/** Why a count started — decision 4 lists exactly these three triggers. */
enum class CountInTrigger {
    /** Play-from-stopped at 00:00 (incl. replay after STATE_ENDED). */
    PLAY_AT_0000,

    /** A loop seam restarted playback before A. */
    LOOP_SEAM,

    /** Play-from-stopped exactly at an armed loop's A. */
    PLAY_AT_LOOP_A,
}

/** Why a count was cancelled — surfaced in logcat for on-device verification. */
enum class CountCancelReason {
    TRANSPORT, SEEK, LOOP_OFF, COUNT_IN_OFF, TRACK_OPEN, RELEASE, BACKGROUND,
}

/**
 * Count-in machine (phase-4 D5 hybrid): `Idle|Go → Counting(n…1) → Go`, or back to
 * `Idle` on [CountInController.cancel]. One absolute time base (`elapsedRealtimeNanos`)
 * for clicks and resume — the player stays frozen on the frame at the anchor until
 * the final tick, which *is* the resume instant (decision 2).
 */
sealed interface CountInState {
    /** No count running. */
    data object Idle : CountInState

    data class Counting(
        /** What fired — decision 4. */
        val trigger: CountInTrigger,
        /** Index of the last presented main tick; −1 while the burst preps (decision A). */
        val tickIndex: Int,
        /** Total main ticks (numerals) — bars×4 or clicks, decision 5. */
        val count: Int,
        val subdivision: Boolean,
        /** Speed-scaled wall period between main ticks, ms — `(60000/bpm) ÷ speed`. */
        val periodMs: Float,
        val speed: Float,
        /** Actual burst start (`elapsedRealtimeNanos`); null until prep completes. */
        val startWallNanos: Long?,
        /** Per-main-tick wall instants (nominal from the anchor until [startWallNanos] is known, actual thereafter). */
        val tickWallNanos: List<Long>,
        /** Resume instant = final tick's wall instant (nominal until start is known). */
        val resumeWallNanos: Long,
    ) : CountInState

    /**
     * Final tick reached: playback resumed on the boundary. Persists until the next
     * [CountInController.start] rather than flipping straight to [Idle] — StateFlow
     * conflation would swallow a synchronous `Go → Idle`, leaving the future T03
     * overlay unable to observe the fade-out moment. `isCounting` is false in [Go].
     */
    data object Go : CountInState
}

/**
 * Count-in controller: boundary-anchored scheduling (decision 2), click spacing
 * `(60000/bpm) ÷ speed` on the wall clock (decision 1), burst compensation anchored
 * to the *actual* AudioTrack start (T02 decision A — see [CountInClicker.play]).
 *
 * Pure logic + injected clock — unit-testable with virtual time; no Compose/Media3.
 * Owns one coroutine per count ([countJob]) in [scope] (the ViewModel's lifecycle-
 * bound scope, same pattern as `LoopController`).
 */
class CountInController(
    private val scope: CoroutineScope,
    private val clicker: CountInClicker,
    private val player: CountInPlayer,
    private val config: () -> CountInConfig,
    private val playbackSpeed: () -> Float = { 1f },
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val _state = MutableStateFlow<CountInState>(CountInState.Idle)
    val state: StateFlow<CountInState> = _state.asStateFlow()

    private var countJob: Job? = null

    val isCounting: Boolean get() = _state.value is CountInState.Counting

    /** Live master switch — callers (seam collector) early-out when count-in is off. */
    val isEnabled: Boolean get() = config().enabled

    /**
     * Runs a count anchored at media position [anchorMediaMs]. Returns true when the
     * count started (the player is now frozen); false when it didn't fire — disabled,
     * already counting, or a degenerate tick count — and the caller should just play.
     */
    fun start(trigger: CountInTrigger, anchorMediaMs: Long): Boolean {
        if (isCounting) return false
        val cfg = config()
        if (!cfg.enabled) return false
        // Decision 5: bars > 0 ⇒ bars mode (4/4, N bars = N×4 beats), else clicks.
        val mainTicks = if (cfg.countInBars > 0) {
            cfg.countInBars * BEATS_PER_BAR
        } else {
            cfg.countInClicks.coerceIn(1, MAX_CLICKS)
        }
        val speed = playbackSpeed()
        val periodMediaMs = ClickSchedule.periodMs(cfg.bpm) // media time: 60000/bpm
        val wallPeriodMs = periodMediaMs / speed // wall: decision 1 (speed-scaled)
        // Media-time ticks shifted onto the anchor's position; ClickTrack maps them
        // to wall time via wallTimeNanos(..., speed), keeping Phase 5's API shape.
        val ticks = ClickSchedule.countTicks(mainTicks, periodMediaMs, cfg.subdivision)
            .map { it.copy(mediaMs = it.mediaMs + anchorMediaMs) }
        val anchor = clockNanos()
        Log.d(
            TAG,
            "count start: trigger=$trigger n=$mainTicks wallPeriod=${wallPeriodMs}ms " +
                "speed=$speed anchorMedia=${anchorMediaMs}ms anchorWall=$anchor",
        )
        player.pause() // decision 2: frozen on the frame at A / 00:00 until the final tick
        _state.value = CountInState.Counting(
            trigger = trigger,
            tickIndex = -1,
            count = mainTicks,
            subdivision = cfg.subdivision,
            periodMs = wallPeriodMs,
            speed = speed,
            startWallNanos = null,
            tickWallNanos = (0 until mainTicks).map { anchor + perTickOffsetNanos(it, wallPeriodMs) },
            resumeWallNanos = anchor + finalTickOffsetNanos(mainTicks, wallPeriodMs),
        )
        countJob = scope.launch { run(ticks, anchor, anchorMediaMs, speed, cfg) }
        return true
    }

    /** Aborts a running count: stops clicks mid-burst, leaves the player paused at the anchor. */
    fun cancel(reason: CountCancelReason) {
        val current = _state.value
        if (current !is CountInState.Counting) return
        Log.d(TAG, "count cancel: reason=$reason tickIndex=${current.tickIndex}")
        countJob?.cancel()
        countJob = null
        clicker.stop()
        _state.value = CountInState.Idle
    }

    private suspend fun run(
        ticks: List<ClickSchedule.Tick>,
        anchor: Long,
        anchorMediaMs: Long,
        speed: Float,
        cfg: CountInConfig,
    ) {
        // Decision A: anchor every wall instant to when the burst actually started,
        // so prep lateness shifts clicks and resume *together* (spacing untouched).
        val startNanos = try {
            clicker.play(ticks, anchor, anchorMediaMs, speed, cfg.clickSound, cfg.clickVolume)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No clicks audible — degrade to plain playback rather than freezing the
            // frame forever (mirrors OutputLatency's fallback philosophy).
            Log.e(TAG, "count skipped: click burst failed to start", e)
            player.play()
            _state.value = CountInState.Go
            return
        }
        val state = _state.value as? CountInState.Counting ?: return // cancelled meanwhile
        // Burst offsets are measured from the nominal anchor; shift them onto the
        // actual start so heard ticks and the resume stay aligned by construction.
        val ticksWall = state.tickWallNanos.map { startNanos + (it - anchor) }
        val resumeNanos = startNanos + (state.resumeWallNanos - anchor)
        Log.i(
            TAG,
            "burst started: lateness=+${(startNanos - anchor) / 1_000_000}ms " +
                "resumeIn=${(resumeNanos - startNanos) / 1_000_000}ms",
        )
        _state.update {
            (it as? CountInState.Counting)?.copy(
                startWallNanos = startNanos,
                tickWallNanos = ticksWall,
                resumeWallNanos = resumeNanos,
            ) ?: it
        }
        // Ticker: one delay per main tick from *now*, so an overshoot shifts only the
        // late tick — never the schedule's absolute base (non-cumulative).
        for (i in ticksWall.indices) {
            delayUntil(ticksWall[i])
            _state.update { s ->
                (s as? CountInState.Counting)?.copy(tickIndex = i) ?: s
            }
        }
        // Final tick = resume instant (decision 2): play on the boundary, then Go.
        Log.i(TAG, "count done: resumed on tick ${state.count}/${state.count}")
        player.play()
        _state.value = CountInState.Go
    }

    private fun perTickOffsetNanos(index: Int, wallPeriodMs: Float): Long =
        (index * wallPeriodMs * 1_000_000.0).toLong()

    private fun finalTickOffsetNanos(mainTicks: Int, wallPeriodMs: Float): Long =
        perTickOffsetNanos(mainTicks - 1, wallPeriodMs)

    /** Sleeps until [nanos] on the injected clock; no-op when the instant already passed. */
    private suspend fun delayUntil(nanos: Long) {
        val remaining = nanos - clockNanos()
        if (remaining > 0) delay(remaining.nanoseconds)
    }

    private companion object {
        private const val TAG = "CountIn"
        private const val BEATS_PER_BAR = 4 // 4/4; time signature is Phase 5
        private const val MAX_CLICKS = 8 // preset range 1–8 (decision 5)
    }
}


/** Minimal player surface a count needs — no Media3/Compose types (LoopPlayer pattern). */
interface CountInPlayer {
    /** Freeze playback on the frame at the anchor. */
    fun pause()
    /** Resume playback — called exactly at the final tick. */
    fun play()
}

/**
 * Pure trigger predicates (decision 4): fire only (a) play-from-stopped at 00:00,
 * (c) play-from-stopped at an armed loop's A — never resume-from-pause mid-track.
 */
object CountInTriggers {
    /** "At 00:00 / at A" tolerance; phase-4 T02 suggests ≤100 ms for seam detection. */
    const val EPSILON_MS = 100L

    /** The trigger for a play-from-stopped tap at [positionMs], or null for plain play. */
    fun onPlayFromStopped(positionMs: Long, loop: LoopState): CountInTrigger? = when {
        positionMs <= EPSILON_MS -> CountInTrigger.PLAY_AT_0000
        loop.loopEnabled && loop.positionA >= 0 &&
            abs(positionMs - loop.positionA) <= EPSILON_MS -> CountInTrigger.PLAY_AT_LOOP_A
        else -> null
    }
}
