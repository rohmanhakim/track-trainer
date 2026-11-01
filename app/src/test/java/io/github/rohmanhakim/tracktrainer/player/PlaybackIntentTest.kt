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
}