package io.github.rohmanhakim.tracktrainer.metronome

/**
 * Pure PCM layout math for a pre-written count burst: every tick's one-shot
 * sits at its absolute-wall-derived frame offset, silence in between, so tick
 * spacing is sample-exact once the track starts.
 */
object ClickBuffer {

    /** Frame offset of each tick measured from [anchorWallNanos] (must be ≥ 0). */
    fun offsetFrames(scheduledNanos: List<Long>, anchorWallNanos: Long, sampleRate: Int): LongArray {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        return LongArray(scheduledNanos.size) { i ->
            val deltaNs = scheduledNanos[i] - anchorWallNanos
            require(deltaNs >= 0) { "tick $i scheduled ${-deltaNs}ns before the anchor" }
            deltaNs * sampleRate / 1_000_000_000L
        }
    }

    /** Burst length: the last tick's offset plus its PCM tail. */
    fun totalFrames(offsetFrames: LongArray, pcmFrames: Int): Int {
        require(offsetFrames.isNotEmpty()) { "no ticks" }
        val last = offsetFrames.last() + pcmFrames
        require(last <= Int.MAX_VALUE) { "burst too long: $last frames" }
        return last.toInt()
    }

    /** Silence-filled burst buffer with [pcm] copied at every tick offset. */
    fun interleave(offsetFrames: LongArray, pcm: ShortArray, totalFrames: Int): ShortArray {
        val out = ShortArray(totalFrames)
        for (offset in offsetFrames) {
            pcm.copyInto(out, destinationOffset = offset.toInt())
        }
        return out
    }
}
