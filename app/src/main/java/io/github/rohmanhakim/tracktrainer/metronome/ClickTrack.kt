package io.github.rohmanhakim.tracktrainer.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Per-tick timing record; instants in the `elapsedRealtimeNanos` domain. */
data class TickMeasurement(
    val index: Int,
    val isDownbeat: Boolean,
    val scheduledNanos: Long,
    /** Burst start — the single play() on the pre-written track, shared by every tick. */
    val writtenNanos: Long,
    /** Null when the tick's onset wasn't observed before its window closed. */
    val presentedNanos: Long?,
)

/** Aggregate of one burst. */
data class BurstMeasurement(
    val ticks: List<TickMeasurement>,
    /** max |actual − scheduled| over consecutive presented tick pairs (the ±10 ms budget). */
    val maxAbsSpacingErrorMs: Float,
    val meanAbsSpacingErrorMs: Float,
    /** play() minus anchor: burst start lateness (identical for every tick). */
    val meanWriteLatenessMs: Float,
    /** Mean presentation−start: write→audible delay, the empirical output latency. */
    val meanPresentDelayMs: Float,
    val sampleRate: Int,
)

/**
 * One burst of clicks on a standalone `AudioTrack` — the D5 hybrid count-in
 * half of 02-architecture §3.4 (the player is paused during a count, so the
 * sink's processor chain is silent and clicks must live outside it).
 *
 * Scheduling strategy (phase-4 T01 "How", settled on the Redmi): the whole
 * burst — silence gaps included — is pre-written into one MODE_STATIC buffer
 * and started by a single `play()` at the absolute anchor, so tick spacing is
 * sample-exact and the only jitter is the play() call itself. Per-tick
 * streaming writes were tried first and failed on the device: AudioFlinger
 * removed the track for "BUFFER TIMEOUT" underruns between ticks and the
 * playback head never advanced (session log 2026-11-11).
 *
 * One track per burst, created/written/started/released inside [playBurst].
 */
class ClickTrack(
    private val synth: ClickSynth = ClickSynth(),
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val logTag: String = "ClickTrack",
) {

    /**
     * Plays [ticks] anchored at [anchorWallNanos] and measures what happened.
     * Cancelling the calling coroutine releases the track mid-burst.
     */
    suspend fun playBurst(
        ticks: List<ClickSchedule.Tick>,
        anchorWallNanos: Long,
        anchorMediaMs: Long,
        playbackSpeed: Float,
        sound: ClickSound,
        volume: Float,
    ): BurstMeasurement {
        require(ticks.isNotEmpty()) { "ticks must not be empty" }
        val scheduled = ticks.map {
            ClickSchedule.wallTimeNanos(anchorWallNanos, anchorMediaMs, it.mediaMs, playbackSpeed)
        }
        val sampleRate = pickSampleRate()
        val pcm = if (synth.sampleRate == sampleRate) {
            synth.synth(sound, volume)
        } else {
            ClickSynth(sampleRate).synth(sound, volume)
        }
        val offsets = ClickBuffer.offsetFrames(scheduled, anchorWallNanos, sampleRate)
        val totalFrames = ClickBuffer.totalFrames(offsets, pcm.size)
        val buffer = ClickBuffer.interleave(offsets, pcm, totalFrames)
        val track = buildStaticTrack(sampleRate, totalFrames)
        // Off the main thread: cold-start frame work otherwise delays play() by
        // hundreds of ms (seen on the Redmi with per-tick scheduling).
        return withContext(Dispatchers.Default) {
            try {
                // MODE_STATIC starts at STATE_NO_STATIC_DATA (2) and flips to
                // STATE_INITIALIZED only once the buffer is written — reject
                // only the truly uninitialized case.
                if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                    error("AudioTrack failed to initialize (static, $totalFrames frames, state=${track.state})")
                }
                writeFully(track, buffer)
                awaitDeadline(anchorWallNanos)
                track.play()
                val playAt = clockNanos()
                val startLatenessMs = ((playAt - anchorWallNanos) / 1e6).toFloat()
                Log.d(logTag, "burst started: frames=$totalFrames start=+${"%.1f".format(startLatenessMs)}ms")
                val measurements = ticks.mapIndexed { i, tick ->
                    val presented = awaitPresentation(
                        track,
                        offsets[i],
                        windowUntilMs(scheduled, i, clockNanos()),
                    )
                    TickMeasurement(
                        index = i,
                        isDownbeat = tick.isDownbeat,
                        scheduledNanos = scheduled[i],
                        writtenNanos = playAt,
                        presentedNanos = presented,
                    )
                }
                val burst = summarize(measurements, sampleRate, startLatenessMs)
                logBurst(burst, playbackSpeed, sound, startLatenessMs)
                burst
            } finally {
                track.release()
            }
        }
    }

    private fun buildStaticTrack(sampleRate: Int, totalFrames: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_GAME: fast-track eligible in audio policy on this
                    // device — a ~5 ms mix period instead of deep-buffer ~47 ms,
                    // which is what head-position observation quantizes to.
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(totalFrames * 2)
            .build()
            .also { Log.d(logTag, "static track built: state=${it.state} frames=$totalFrames") }

    private fun pickSampleRate(): Int {
        for (rate in intArrayOf(48_000, 44_100)) {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return rate
        }
        error("no supported mono PCM16 sample rate")
    }

    private suspend fun awaitDeadline(deadlineNanos: Long) {
        while (true) {
            val remainingNs = deadlineNanos - clockNanos()
            if (remainingNs <= 0) return
            // Coarse slices first, 1 ms slices near the deadline: Android delay()
            // granularity keeps the final overshoot in the low milliseconds.
            delay((remainingNs / 1_000_000L).coerceIn(1L, 20L))
        }
    }

    private fun writeFully(track: AudioTrack, buffer: ShortArray): Int {
        var written = 0
        while (written < buffer.size) {
            val n = track.write(buffer, written, buffer.size - written)
            require(n > 0) { "AudioTrack.write failed: $n" }
            written += n
        }
        return written
    }

    /**
     * Waits until this tick's onset frame has passed, then computes its true
     * presentation instant from `getTimestamp`'s (frame, nanotime) pair —
     * precise to ~1 ms, unlike head-position polling which quantizes to the
     * ~47 ms deep-buffer mix period (measured on the Redmi). The pair's
     * nanotime is CLOCK_MONOTONIC; [clockNanos] reconciles it with the
     * scheduling clock. Valid because a static burst renders continuously —
     * getTimestamp only goes invalid across *underruns*, which is what broke
     * the per-tick streaming attempt. Head-poll (quantized) is the fallback.
     */
    private suspend fun awaitPresentation(
        track: AudioTrack,
        targetFrame: Long,
        timeoutMs: Long,
    ): Long? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val timestamp = AudioTimestamp()
        var polls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            if (track.getTimestamp(timestamp) && timestamp.framePosition > targetFrame) {
                val clockOffset = clockNanos() - System.nanoTime()
                val deltaFrames = timestamp.framePosition - targetFrame
                return timestamp.nanoTime + clockOffset - deltaFrames * 1_000_000_000L / track.sampleRate
            }
            if (polls > 100 && track.playbackHeadPosition.toLong() > targetFrame) {
                Log.w(logTag, "timestamp unavailable — head-poll fallback for frame $targetFrame")
                return clockNanos()
            }
            if (polls == 0 || polls % 500 == 0) {
                Log.d(logTag, "poll#$polls target=$targetFrame head=${track.playbackHeadPosition} state=${track.playState}")
            }
            polls++
            delay(2)
        }
        Log.w(logTag, "presentation timeout target=$targetFrame head=${track.playbackHeadPosition} state=${track.playState}")
        return null
    }

    /**
     * Window to observe tick i's onset: until the next tick's wall instant —
     * nothing is pending (the buffer is pre-written), so overshooting the next
     * wall is harmless — floored at 250 ms to outlast output latency,
     * 1.5 s for the final tick.
     */
    private fun windowUntilMs(scheduled: List<Long>, index: Int, nowNanos: Long): Long {
        val endNanos = scheduled.getOrNull(index + 1) ?: (nowNanos + 1_500_000_000L)
        return ((endNanos - nowNanos) / 1_000_000L).coerceIn(250L, 5_000L)
    }

    private fun summarize(
        measurements: List<TickMeasurement>,
        sampleRate: Int,
        startLatenessMs: Float,
    ): BurstMeasurement {
        val spacingErrors = measurements.zipWithNext { a, b ->
            val pa = a.presentedNanos ?: return@zipWithNext null
            val pb = b.presentedNanos ?: return@zipWithNext null
            ((pb - pa) - (b.scheduledNanos - a.scheduledNanos)) / 1_000_000.0
        }.filterNotNull()
        val presentDelay = measurements.mapNotNull { m ->
            m.presentedNanos?.let { (it - m.scheduledNanos) / 1e6 }
        }
        return BurstMeasurement(
            ticks = measurements,
            maxAbsSpacingErrorMs = spacingErrors.maxOfOrNull { kotlin.math.abs(it) }?.toFloat() ?: 0f,
            meanAbsSpacingErrorMs = if (spacingErrors.isEmpty()) {
                0f
            } else {
                spacingErrors.map { kotlin.math.abs(it) }.average().toFloat()
            },
            meanWriteLatenessMs = startLatenessMs,
            meanPresentDelayMs = if (presentDelay.isEmpty()) 0f else presentDelay.average().toFloat(),
            sampleRate = sampleRate,
        )
    }

    private fun logBurst(
        burst: BurstMeasurement,
        speed: Float,
        sound: ClickSound,
        startLatenessMs: Float,
    ) {
        Log.i(
            logTag,
            "burst: n=${burst.ticks.size} sound=$sound speed=$speed rate=${burst.sampleRate}Hz " +
                "start=+${"%.1f".format(startLatenessMs)}ms",
        )
        for (m in burst.ticks) {
            val label = if (m.isDownbeat) "down" else "off "
            val presentedMs = m.presentedNanos?.let { (it - m.scheduledNanos) / 1e6 }
            val chainMs = m.presentedNanos?.let { (it - m.writtenNanos) / 1e6 }
            Log.i(
                logTag,
                "tick ${m.index} [$label] sched=${m.scheduledNanos} " +
                    "present=${presentedMs?.let { "+${"%.1f".format(it)}ms" } ?: "n/a"} " +
                    "chain=${chainMs?.let { "+${"%.1f".format(it)}ms" } ?: "n/a"}",
            )
        }
        val chains = burst.ticks.mapNotNull { m ->
            m.presentedNanos?.let { (it - m.writtenNanos) / 1e6 }
        }
        Log.i(
            logTag,
            "burst summary: spacing error max=${"%.1f".format(burst.maxAbsSpacingErrorMs)}ms " +
                "mean=${"%.1f".format(burst.meanAbsSpacingErrorMs)}ms " +
                "(budget ±10ms); start lateness=${"%.1f".format(startLatenessMs)}ms; " +
                "chain mean=${"%.1f".format(if (chains.isEmpty()) 0.0 else chains.average())}ms",
        )
    }
}
