package io.github.rohmanhakim.tracktrainer.loop

import androidx.media3.exoplayer.ExoPlayer

/** Adapts Media3 [ExoPlayer] to [LoopPlayer] so the loop controller stays Media3-free. */
class ExoLoopPlayer(private val player: ExoPlayer) : LoopPlayer {
    override val currentPositionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.coerceAtLeast(0)
    override val isPlaying: Boolean get() = player.isPlaying
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override val volume: Float get() = player.volume
    override fun setVolume(volume: Float) { player.volume = volume }
}
