package io.github.rohmanhakim.tracktrainer.player

import io.github.rohmanhakim.tracktrainer.data.local.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debounced auto-save coordinator for per-track presets (P3-4).
 *
 * User-initiated changes call [schedule]; rapid changes (speed slider drags) coalesce
 * into one write after [debounceMs]. [flush] settles the pending snapshot immediately —
 * used on player release / ViewModel clear so the owner's last change is never lost.
 * Pure logic — no Android, Media3, or Room types; the write is a caller-supplied lambda.
 */
class PresetAutoSaver(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
    private val save: suspend (Preset) -> Unit,
) {
    private val lock = Any()
    private var pending: Preset? = null
    private var debounceJob: Job? = null

    /**
     * Records a user change. An unflushed snapshot for the same track is superseded
     * (coalescing); one for a *different* track is written immediately so switching
     * tracks can never drop the previous track's pending changes.
     */
    fun schedule(preset: Preset) {
        val superseded: Preset?
        synchronized(lock) {
            superseded = pending
            pending = preset
        }
        if (superseded != null && superseded.trackId != preset.trackId) {
            scope.launch { saveWithGuarantee(superseded) }
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            takePending()?.let { saveWithGuarantee(it) }
        }
    }

    /**
     * Writes the pending snapshot now, if any, and returns only once the write has
     * settled. Must not be called from the saver's own debounce coroutine. Safe to
     * call repeatedly; no-op when nothing is pending.
     */
    suspend fun flush() {
        val job = debounceJob
        debounceJob = null
        if (job != null) {
            job.cancel()
            job.join() // a debounce mid-write finishes before we consider flush done
        }
        takePending()?.let { saveWithGuarantee(it) }
    }

    private fun takePending(): Preset? = synchronized(lock) {
        val snapshot = pending
        pending = null
        snapshot
    }

    /** Once a snapshot is taken it must reach the store — never dropped mid-write. */
    private suspend fun saveWithGuarantee(preset: Preset) =
        withContext(NonCancellable) { save(preset) }
}