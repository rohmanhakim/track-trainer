package io.github.rohmanhakim.tracktrainer.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/**
 * Output-latency probe: how long a written frame takes to become audible.
 * The T03 overlay compensates by this much so numerals flip when the click is
 * *heard* (02-architecture §3.4); the owner's nudge control fine-tunes around it.
 *
 * A failed probe falls back to [FALLBACK_NS] (~50 ms) — the count-in stays
 * usable, just less precisely aligned (risk R2).
 */
object OutputLatency {

    const val TAG = "OutputLatency"

    /** Used whenever the probe cannot produce a measurement. */
    const val FALLBACK_NS = 50_000_000L

    private const val PROBE_TIMEOUT_MS = 500L

    @Volatile
    private var cachedNanos: Long? = null

    /**
     * Frames between the write head and what the DAC has presented, converted
     * to nanos and floored at one output-buffer quantum — some pipelines hand
     * out a timestamp that reads slightly ahead of reality.
     */
    fun estimateNs(
        framesWritten: Long,
        presentedFrame: Long,
        sampleRate: Int,
        framesPerBuffer: Int,
    ): Long {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        require(framesPerBuffer >= 0) { "framesPerBuffer must be >= 0" }
        val pendingFrames = (framesWritten - presentedFrame).coerceAtLeast(0)
        val pendingNs = pendingFrames * 1_000_000_000L / sampleRate
        val quantumNs = framesPerBuffer * 1_000_000_000L / sampleRate
        return maxOf(pendingNs, quantumNs)
    }

    /**
     * Plays a short near-silent burst and reads one timestamp off it.
     * Returns null when the pipeline won't hand out a timestamp.
     */
    fun probe(context: Context): Long? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48_000
        val framesPerBuffer = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull() ?: 0
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufBytes <= 0) {
            Log.w(TAG, "probe: no supported output config (rate=$sampleRate)")
            return null
        }
        // One written quantum keeps the probe short while giving the timestamp
        // enough frames to be valid.
        val quantumFrames = framesPerBuffer.coerceAtLeast(256)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBufBytes, quantumFrames * 2 * 2))
            .build()
        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "probe: track failed to initialize")
                return null
            }
            track.play()
            val silence = ShortArray(quantumFrames)
            var written = 0
            while (written < silence.size) {
                val n = track.write(silence, written, silence.size - written)
                if (n <= 0) return null
                written += n
            }
            val timestamp = AudioTimestamp()
            val deadline = SystemClock.uptimeMillis() + PROBE_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (track.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                    val ns = estimateNs(written.toLong(), timestamp.framePosition, sampleRate, framesPerBuffer)
                    Log.i(TAG, "probe: latency=${ns / 1e6}ms rate=$sampleRate framesPerBuffer=$framesPerBuffer")
                    return ns
                }
                SystemClock.sleep(5)
            }
            Log.w(TAG, "probe: timed out waiting for a timestamp")
            null
        } catch (e: Exception) {
            Log.w(TAG, "probe failed", e)
            null
        } finally {
            track.release()
        }
    }

    /** Fresh measurement (the T01 3-run stability check), [FALLBACK_NS] on failure. */
    fun measure(context: Context): Long = probe(context) ?: FALLBACK_NS

    /** Session-cached value — read once per app session (T01 "How"). */
    fun sessionNanos(context: Context): Long =
        cachedNanos ?: (probe(context) ?: FALLBACK_NS).also { cachedNanos = it }
}
