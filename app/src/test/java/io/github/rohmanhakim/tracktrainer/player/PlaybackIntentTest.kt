package io.github.rohmanhakim.tracktrainer.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentTest {
    @Test
    fun armedPlaybackShowsPauseIncludingTransientBuffering() {
        // The loop seam's seek to A passes through BUFFERING with playWhenReady=true —
        // intent stays armed, so the button must not blink to "Play".
        assertTrue(isPlayingIntent(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(isPlayingIntent(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
        assertTrue(isPlayingIntent(playWhenReady = true, playbackState = Player.STATE_IDLE))
    }

    @Test
    fun pausedShowsPlay() {
        assertFalse(isPlayingIntent(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(isPlayingIntent(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
        assertFalse(isPlayingIntent(playWhenReady = false, playbackState = Player.STATE_IDLE))
    }

    @Test
    fun endedShowsPlay() {
        // P1 regression guard: "Play" after the end must replay from the top.
        assertFalse(isPlayingIntent(playWhenReady = true, playbackState = Player.STATE_ENDED))
    }

    @Test
    fun countingShowsPauseEvenWhileFrozen() {
        // Decision 7 (T02): a count pauses the player on purpose — the intent must
        // stay armed so the transport shows Pause (tap cancels) and keep-screen-on holds.
        assertTrue(isPlayingIntent(playWhenReady = false, playbackState = Player.STATE_READY, counting = true))
        assertTrue(isPlayingIntent(playWhenReady = true, playbackState = Player.STATE_READY, counting = true))
        // Cancelled count: back to the paused truth.
        assertFalse(isPlayingIntent(playWhenReady = false, playbackState = Player.STATE_READY, counting = false))
    }
}