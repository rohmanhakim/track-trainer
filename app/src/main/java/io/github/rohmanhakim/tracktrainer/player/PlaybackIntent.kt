package io.github.rohmanhakim.tracktrainer.player

import androidx.media3.common.Player

/**
 * Whether playback is *armed*: the owner wants audio playing, even while the player
 * briefly buffers (initial load, or the loop seam's seek to A).
 *
 * The transport button and keep-screen-on derive from this intent instead of
 * [Player.isPlaying], which transiently drops to false on every buffering step —
 * that transient made the play/pause button blink at every loop seam.
 *
 * [counting] (T02, decision 7): during a count-in the player is deliberately paused
 * on the anchor frame, but the owner's intent is still "playing" — the transport
 * must keep showing Pause (a tap then cancels the count) and keep-screen-on must
 * hold for the whole count.
 */
fun isPlayingIntent(
    playWhenReady: Boolean,
    playbackState: Int,
    counting: Boolean = false,
): Boolean = counting || (playWhenReady && playbackState != Player.STATE_ENDED)