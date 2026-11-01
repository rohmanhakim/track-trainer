package io.github.rohmanhakim.tracktrainer.loop

import kotlin.OptIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.job

/** One observed seam event: the player passed B and the controller seeked back to A. */
data class SeamEvent(
    /** Position (ms) where B was detected (includes poll overrun past B). */
    val boundaryPositionMs: Long,
    /** The seek target (A). */
    val seekToMs: Long,
    /** How far past B the poll detected the boundary (bounded by poll interval). */
    val overrunMs: Long,
)

/** Snapshot of loop state exposed to UI / presets. Read-only. */
data class LoopState(
    /** Start position (ms, inclusive). -1 = unset. */
    val positionA: Long = -1L,
    /** End position (ms, exclusive). -1 = unset. */
    val positionB: Long = -1L,
    /** Whether repetition is currently armed. */
    val loopEnabled: Boolean = false,
    /** The [LoopPoint.id] driving these bounds, if any. */
    val activeLoopPointId: Long? = null,
)

/** Minimal player surface the controller needs — no Media3/Compose types. */
interface LoopPlayer {
    val currentPositionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    fun seekTo(positionMs: Long)
    val volume: Float
    fun setVolume(volume: Float)
}

/**
 * A-B loop engine: polls position ~40 ms and seeks to A when passing B.
 *
 * Loops until the owner toggles off (no repeat counts). The seam is masked with a
 * best-effort ~5 ms volume mute around the seek to A. Arming the loop while the
 * playhead is outside [A, B) aligns it to A immediately (see [alignToAOnArm]).
 *
 * Pure logic — unit-testable with a fake clock; no Compose, no Media3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopController(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 40L,
    private val seamFadeMs: Long = 5L,
) {
    private val _state = MutableStateFlow(LoopState())
    val state: StateFlow<LoopState> = _state.asStateFlow()

    /**
     * Emits one [SeamEvent] each time the loop seeks back to A. Buffered so a busy
     * collector never loses events — a zero-capacity SharedFlow's tryEmit silently
     * drops whenever the collector isn't ready at that instant.
     */
    private val _seamEvents = MutableSharedFlow<SeamEvent>(extraBufferCapacity = 16)
    val seamEvents: Flow<SeamEvent> = _seamEvents.asFlow()

    private var player: LoopPlayer? = null
    private var pollJob: Job? = null
    private var restoreJob: Job? = null

    /** Binds [player] and starts the position poll. Replaces any previous player. */
    fun attach(player: LoopPlayer) {
        detach()
        this.player = player
        pollJob = pollLoop()
    }

    /** Stops polling and releases the player reference. */
    fun detach() {
        pollJob?.cancel()
        pollJob = null
        restoreJob?.cancel()
        restoreJob = null
        player = null
    }

    fun setA(positionMs: Long) = _state.update { it.copy(positionA = positionMs.coerceAtLeast(0)) }
    fun setB(positionMs: Long) = _state.update { it.copy(positionB = positionMs.coerceAtLeast(0)) }
    /** Unsets loop point A (no-op when already unset). */
    fun clearA() = _state.update { it.copy(positionA = -1L) }
    /** Unsets loop point B (no-op when already unset). */
    fun clearB() = _state.update { it.copy(positionB = -1L) }
    fun setActiveLoopPoint(id: Long?) = _state.update { it.copy(activeLoopPointId = id) }

    fun setLoopEnabled(enabled: Boolean) {
        if (!enabled) restoreJob?.cancel()
        _state.update { it.copy(loopEnabled = enabled) }
        if (enabled) alignToAOnArm()
    }

    /**
     * Arming while the playhead is outside [A, B) jumps straight to A so repetition starts
     * immediately — whether armed before A or already past B (which used to play out the
     * rest of the track before a catch-up seam). Emits no [SeamEvent]: an arm-time seek is
     * not a loop-boundary crossing, and seam-deviation stats must only measure real B→A
     * passes.
     */
    private fun alignToAOnArm() {
        val current = player ?: return
        val s = _state.value
        if (s.positionB <= s.positionA) return
        val pos = current.currentPositionMs
        if (pos < s.positionA || pos >= s.positionB) {
            seekToA(current)
        }
    }

    fun clear() {
        setLoopEnabled(false)
        _state.update { LoopState() }
    }

    private fun pollLoop(): Job = scope.launch {
        while (isActive) {
            delay(pollIntervalMs)
            val current = player ?: break
            val s = _state.value
            val pos = current.currentPositionMs // read once per tick — stable value
            if (
                s.loopEnabled &&
                s.positionB > s.positionA &&
                current.isPlaying &&
                pos >= s.positionB
            ) {
                val overrunMs = pos - s.positionB
                val event = SeamEvent(pos, s.positionA, overrunMs)
                // tryEmit can only fail if the 16-slot buffer overflows; fall back to a
                // suspending emit rather than silently dropping the seam record.
                if (!_seamEvents.tryEmit(event)) {
                    scope.launch { _seamEvents.emit(event) }
                }
                seekToA(current)
            }
        }
    }

    /** Mute → seek to A → restore volume after the seam-fade window to mask the pop. */
    private fun seekToA(player: LoopPlayer) {
        val previousVolume = player.volume
        player.setVolume(0f)
        player.seekTo(_state.value.positionA)
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(seamFadeMs)
            player.setVolume(previousVolume)
        }
    }
}
