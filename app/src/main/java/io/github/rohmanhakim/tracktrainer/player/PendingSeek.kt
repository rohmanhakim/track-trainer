package io.github.rohmanhakim.tracktrainer.player

/**
 * Tracks in-flight seeks until the player confirms where they landed.
 *
 * `ExoPlayer.seekTo` only posts the seek to the playback thread: `currentPosition`
 * keeps returning the pre-seek position until the resulting update round-trips back
 * (Media3 1.11, `ExoPlayerImpl.getCurrentPosition`). Reading it inside that window is
 * what made Set A/B capture a point far from the timestamp on screen — so while a seek
 * is in flight it owns the displayed playhead and the player's own position is
 * distrusted.
 *
 * Two kinds of seek:
 *  - user ([onUserSeek]) — `SeekParameters.EXACT`, requested == landing; the settle must
 *    match the newest target so a superseded seek can't free a newer one still in flight.
 *  - loop seek-to-A ([onLoopSeek]) — `CLOSEST_SYNC`, landing ≠ request by design; the
 *    landing *is* the answer — the real restart point the A label corrects to.
 *
 * Pure logic — unit-testable; no Media3/Compose.
 */
class PendingSeek {
    /** Resolved landing of the seek a discontinuity settled, tagged by what it resolved. */
    data class Settled(val landedMs: Long, val wasLoopSeek: Boolean)

    /** Newest user seek target not yet confirmed landed; null = no user seek in flight. */
    var targetMs: Long? = null
        private set

    private var loopSeekInFlight = false

    /** True while any seek is in flight — the player's own position can't be trusted. */
    val inFlight: Boolean get() = targetMs != null || loopSeekInFlight

    fun onUserSeek(targetMs: Long) {
        this.targetMs = targetMs
        loopSeekInFlight = false
    }

    /** The loop's seek-to-A (CLOSEST_SYNC) was issued — its landing is unknown up front. */
    fun onLoopSeek() {
        targetMs = null
        loopSeekInFlight = true
    }

    /**
     * A seek discontinuity landed at [landedMs]. Returns the settled landing or null when
     * it doesn't resolve the in-flight seek — e.g. a superseded user seek arriving while a
     * newer target is still pending.
     */
    fun onSeekSettled(landedMs: Long): Settled? {
        if (loopSeekInFlight) {
            loopSeekInFlight = false
            return Settled(landedMs, wasLoopSeek = true)
        }
        if (targetMs != landedMs) return null
        targetMs = null
        return Settled(landedMs, wasLoopSeek = false)
    }

    /** Player teardown / track open — no seek from the old session carries over. */
    fun reset() {
        targetMs = null
        loopSeekInFlight = false
    }
}