package io.github.rohmanhakim.tracktrainer.countin

import android.util.Log
import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import io.github.rohmanhakim.tracktrainer.metronome.ClickSchedule
import io.github.rohmanhakim.tracktrainer.metronome.ClickTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Audio half of a count: fires one pre-written burst of clicks (D5 hybrid — the
 * player is paused during a count, so the sink's processor chain is silent).
 *
 * Implemented over [ClickTrack] (T01); interface exists so the controller can be
 * unit-tested with a fake clock and no AudioTrack.
 */
interface CountInClicker {
    /**
     * Starts a burst anchored at [anchorWallNanos] and suspends until its
     * AudioTrack actually started, returning that wall instant.
     *
     * The returned instant is the compensation base (T02 decision A): `playBurst`
     * builds the buffer before `play()`, so ticks land at `start + offsets` — the
     * controller anchors every tick and the resume to the *actual* start, not the
     * nominal anchor, keeping resume sample-aligned with the final tick regardless
     * of prep lateness (~480–800 ms measured on the Redmi).
     */
    suspend fun play(
        ticks: List<ClickSchedule.Tick>,
        anchorWallNanos: Long,
        anchorMediaMs: Long,
        playbackSpeed: Float,
        sound: ClickSound,
        volume: Float,
    ): Long

    /** Stops any running burst, releasing its AudioTrack mid-burst if necessary. */
    fun stop()
}

/**
 * [ClickTrack] adapter. The burst (including T01's measurement tail) runs in
 * [scope] — a lifecycle-bound scope owned by the player ViewModel, same pattern
 * as `LoopController`; [stop] is how the owner-facing cancel path releases audio.
 */
class TrackCountInClicker(
    private val scope: CoroutineScope,
    private val clickTrack: ClickTrack = ClickTrack(),
) : CountInClicker {

    private var burstJob: Job? = null
    private var startSignal: CompletableDeferred<Long>? = null

    override suspend fun play(
        ticks: List<ClickSchedule.Tick>,
        anchorWallNanos: Long,
        anchorMediaMs: Long,
        playbackSpeed: Float,
        sound: ClickSound,
        volume: Float,
    ): Long {
        stop() // replace any previous burst (its audio is long done; tail is measurement)
        val started = CompletableDeferred<Long>()
        startSignal = started
        burstJob = scope.launch {
            try {
                clickTrack.playBurst(
                    ticks,
                    anchorWallNanos,
                    anchorMediaMs,
                    playbackSpeed,
                    sound,
                    volume,
                    onStart = { playAt -> started.complete(playAt) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Audio-stack failures must degrade the count, never crash the app
                // (viewModelScope's SupervisorJob would otherwise hit the uncaught
                // handler): the controller sees the failure at await and resumes
                // without clicks.
                Log.e(TAG, "click burst failed", e)
                started.completeExceptionally(e)
            }
        }
        return started.await()
    }

    override fun stop() {
        burstJob?.cancel()
        burstJob = null
        // Defensive: nobody else awaits this deferred (cancel() stops the awaiting
        // count job first), but a pending await must never be able to hang.
        startSignal?.cancel()
        startSignal = null
    }

    private companion object {
        private const val TAG = "CountIn"
    }
}
