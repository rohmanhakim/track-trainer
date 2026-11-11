package io.github.rohmanhakim.tracktrainer.metronome

import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic PCM one-shots for click sounds. Pure Kotlin — no android.media —
 * so unit tests can assert on the samples directly.
 *
 * Every envelope starts and ends at zero amplitude: a buffer that stops
 * mid-waveform clicks audibly at its own tail (the "no waveform self-clicks"
 * requirement of phase-4 T01).
 */
class ClickSynth(val sampleRate: Int = 48_000) {

    init {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
    }

    /**
     * One click as mono 16-bit PCM, scaled by [volume] (0..1).
     * CLICK = damped 2 kHz transient; BEEP = 880 Hz soft tone.
     */
    fun synth(
        sound: ClickSound,
        volume: Float,
        durationMs: Int = DEFAULT_DURATION_MS,
    ): ShortArray {
        val n = sampleRate * durationMs / 1000
        require(n >= 2) { "durationMs=$durationMs too short at $sampleRate Hz" }
        val amplitude = volume.coerceIn(0f, 1f) * Short.MAX_VALUE
        val last = n - 1
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val wave = when (sound) {
                ClickSound.CLICK -> {
                    // tau = 12 ms so the transient dies well inside the buffer,
                    // plus a 2 ms ramp-out that pins the final sample to zero.
                    val envelope = exp(-t / 0.012) * fadeOutFactor(i, last, sampleRate, 2)
                    sin(2.0 * PI * CLICK_HZ * t) * envelope
                }
                ClickSound.BEEP -> {
                    // raised-cosine hump: zero attack and release by construction
                    sin(PI * i.toDouble() / last) * sin(2.0 * PI * BEEP_HZ * t)
                }
            }
            out[i] = (wave * amplitude).roundToInt().toShort()
        }
        return out
    }

    private fun fadeOutFactor(i: Int, last: Int, sampleRate: Int, fadeMs: Int): Double {
        val fadeSamples = (sampleRate * fadeMs / 1000).coerceAtLeast(1)
        if (i <= last - fadeSamples) return 1.0
        return (last - i).toDouble() / fadeSamples
    }

    companion object {
        const val DEFAULT_DURATION_MS = 20
        private const val CLICK_HZ = 2000.0
        private const val BEEP_HZ = 880.0
    }
}
