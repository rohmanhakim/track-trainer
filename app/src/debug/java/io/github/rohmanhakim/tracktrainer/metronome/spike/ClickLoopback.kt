package io.github.rohmanhakim.tracktrainer.metronome.spike

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Acoustic verification for T01. The Redmi's AIDL HAL reports no live
 * `getTimestamp` position and audio policy refuses fast tracks, so
 * head-position observation is quantized to the ~47 ms deep-buffer mix
 * period — the mic path is sample-clocked and measures the real spacing
 * end-to-end (DAC → air → mic). P5-3 uses the same recording technique.
 */
object LoopbackAnalysis {

    /** One burst's acoustic check: onsets matched to its scheduled instants. */
    data class BurstAcoustics(
        val expectedTicks: Int,
        val matchedOnsets: Int,
        val spacingErrorMaxMs: Float,
        val spacingErrorMeanMs: Float,
        /** Onset of the first tick minus play() — output latency + acoustic path. */
        val firstChainMs: Float?,
    ) {
        val withinBudget: Boolean
            get() = matchedOnsets == expectedTicks && spacingErrorMaxMs <= 10f
    }

    /**
     * Onset frames of loud transients: 2.5 ms window energy of first
     * differences (high-passes room DC), threshold a sixteenth of the peak
     * (clicks dominate a controlled recording), refractory gap so one click
     * cannot trigger twice.
     */
    fun detectOnsets(pcm: ShortArray, sampleRate: Int, refractoryMs: Long = 80): List<Int> {
        if (pcm.isEmpty() || sampleRate <= 0) return emptyList()
        val win = (sampleRate * 25 / 1000).coerceAtLeast(1)
        val hop = (sampleRate / 1000).coerceAtLeast(1)
        if (pcm.size < win + hop) return emptyList()
        val windowCount = (pcm.size - win) / hop + 1
        val energies = LongArray(windowCount)
        for (i in 0 until windowCount) {
            val start = i * hop
            var e = 0L
            for (j in start until start + win - 1) {
                val d = pcm[j + 1].toInt() - pcm[j].toInt()
                e += d.toLong() * d
            }
            energies[i] = e
        }
        val maxEnergy = energies.maxOrNull() ?: 0
        if (maxEnergy <= 0) return emptyList()
        val threshold = maxEnergy / 16
        val refractoryHops = (refractoryMs * sampleRate / 1000 / hop).toInt().coerceAtLeast(1)
        val coarse = mutableListOf<Int>()
        var lastHit = -refractoryHops
        for (i in energies.indices) {
            if (energies[i] >= threshold && i - lastHit >= refractoryHops) {
                coarse.add(i * hop)
                lastHit = i
            }
        }
        // The energy window starts up to 25 ms before the click; back-scan the
        // window for the transient's first strong sample to pin the true onset.
        return coarse.map { start -> refineOnset(pcm, start, win) }
    }

    private fun refineOnset(pcm: ShortArray, coarse: Int, win: Int): Int {
        val to = (coarse + win).coerceAtMost(pcm.size - 1)
        var peak = 0
        for (i in coarse until to) {
            val d = kotlin.math.abs(pcm[i + 1].toInt() - pcm[i].toInt())
            if (d > peak) peak = d
        }
        if (peak == 0) return coarse
        val limit = peak / 4
        for (i in coarse until to) {
            if (kotlin.math.abs(pcm[i + 1].toInt() - pcm[i].toInt()) >= limit) return i
        }
        return coarse
    }

    /**
     * Matches detected onsets (ms since recording start) to one burst's
     * scheduled instants (same base) and compares consecutive gaps. A count
     * mismatch is reported honestly as matched != expected.
     */
    fun analyzeBurst(
        onsetsMs: List<Long>,
        scheduledMs: List<Long>,
        playAtMs: Long?,
    ): BurstAcoustics {
        val expected = scheduledMs.size
        if (expected == 0) return BurstAcoustics(0, 0, 0f, 0f, null)
        val windowStart = scheduledMs.first() - 250
        val windowEnd = scheduledMs.last() + 400
        val matched = onsetsMs.filter { it in windowStart..windowEnd }.sorted()
        val firstChain = if (playAtMs != null && matched.isNotEmpty()) {
            (matched.first() - playAtMs).toFloat()
        } else {
            null
        }
        if (matched.size != expected || matched.size < 2) {
            return BurstAcoustics(expected, matched.size, 0f, 0f, firstChain)
        }
        val errors = matched.zipWithNext { a, b -> (b - a) }
            .zip(scheduledMs.zipWithNext { a, b -> b - a }) { actual, sched ->
                kotlin.math.abs(actual - sched)
            }
        return BurstAcoustics(
            expectedTicks = expected,
            matchedOnsets = matched.size,
            spacingErrorMaxMs = errors.max().toFloat(),
            spacingErrorMeanMs = errors.average().toFloat(),
            firstChainMs = firstChain,
        )
    }
}

/**
 * Concurrent mic capture: AudioRecord's internal buffer only holds ~200 ms,
 * so a reader coroutine drains it into chunks for the whole burst/soak.
 */
class MicRecorder(private val sampleRate: Int = 48_000) {

    private var record: AudioRecord? = null
    private var readerJob: Job? = null
    private val chunks = mutableListOf<ShortArray>()

    /** False when the mic is missing or RECORD_AUDIO isn't granted. */
    fun start(scope: CoroutineScope): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return false
            // MIC first: the most direct path; VOICE_RECOGNITION applies
            // noise-suppression that can suppress the click transients.
            val rec = micSource(minBuf) ?: voiceSource(minBuf) ?: return false
            record = rec
            chunks.clear()
            rec.startRecording()
            readerJob = scope.launch(Dispatchers.IO) {
                val buf = ShortArray(sampleRate / 10) // 100 ms
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    chunks += buf.copyOf(n)
                }
            }
            true
        } catch (e: SecurityException) {
            Log.w("ClickSpike", "mic not available", e)
            false
        }
    }

    private fun voiceSource(minBuf: Int): AudioRecord? = trySource(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        minBuf,
    )

    private fun micSource(minBuf: Int): AudioRecord? = trySource(
        MediaRecorder.AudioSource.MIC,
        minBuf,
    )

    private fun trySource(source: Int, minBuf: Int): AudioRecord? {
        val rec = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate / 5 * 2),
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
        rec.release()
        return null
    }

    /** Stops capture and returns everything recorded (null if never started). */
    suspend fun stop(): ShortArray? {
        val rec = record ?: return null
        record = null
        try {
            rec.stop()
        } catch (e: IllegalStateException) {
            // never started — fall through to release
        }
        readerJob?.join()
        readerJob = null
        rec.release()
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        chunks.clear()
        return out
    }
}
