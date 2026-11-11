package io.github.rohmanhakim.tracktrainer.metronome

/**
 * Beat math for count-in clicks. Media-time first: ticks are offsets on the
 * media timeline, wall-clock instants are derived through [wallTimeNanos] — the
 * same table can drive Phase 4's boundary-anchored counts and Phase 5's
 * track-anchored grid (phase-4 grooming decision 3).
 */
object ClickSchedule {

    /** Used when `Preset.bpm` is unset (phase-4 grooming decision 1). */
    const val DEFAULT_BPM = 120f

    fun periodMs(bpm: Float): Float = 60000f / if (bpm > 0f) bpm else DEFAULT_BPM

    /** One tick of a count: a media-time instant plus whether it carries a numeral. */
    data class Tick(val mediaMs: Long, val isDownbeat: Boolean)

    /**
     * [ticks] instants at `i * periodMs` from the anchor; subdivision adds
     * half-beat off-beats strictly *between* main ticks — never after the last
     * one, which is the resume instant (phase-4 grooming decision 2).
     */
    fun countTicks(ticks: Int, periodMs: Float, subdivision: Boolean): List<Tick> {
        require(ticks >= 1) { "ticks must be >= 1, was $ticks" }
        require(periodMs > 0f) { "periodMs must be > 0, was $periodMs" }
        val main = (0 until ticks).map { Tick((it * periodMs).toLong(), isDownbeat = true) }
        if (!subdivision) return main
        val offBeats = (0 until ticks - 1).map {
            Tick(((it + 0.5f) * periodMs).toLong(), isDownbeat = false)
        }
        return (main + offBeats).sortedBy { it.mediaMs }
    }

    /**
     * Maps a media-time instant onto the absolute wall clock (the
     * `elapsedRealtimeNanos` base of 02-architecture §3.4): the media offset
     * from the anchor runs at [playbackSpeed] — at 0.5x one media second
     * occupies two wall seconds (phase-4 grooming decision 1).
     */
    fun wallTimeNanos(
        anchorWallNanos: Long,
        anchorMediaMs: Long,
        mediaMs: Long,
        playbackSpeed: Float,
    ): Long {
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        val mediaDeltaMs = (mediaMs - anchorMediaMs).toDouble() / speed
        return anchorWallNanos + (mediaDeltaMs * 1_000_000.0).toLong()
    }
}
