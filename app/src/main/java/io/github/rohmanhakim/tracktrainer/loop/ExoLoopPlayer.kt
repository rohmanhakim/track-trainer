package io.github.rohmanhakim.tracktrainer.loop

import androidx.media3.exoplayer.ExoPlayer

/**
 * Adapts Media3 [ExoPlayer] to [LoopPlayer] so the loop controller stays Media3-free.
 *
 * [onLoopSeek] fires whenever the controller issues a seek (boundary seam or arm-align):
 * these resolve with `CLOSEST_SYNC`, so the landing is unknown up front — the owner uses
 * the callback to correct the A label to the real restart point once it lands.
 */
class ExoLoopPlayer(
    private val player: ExoPlayer,
    private val onLoopSeek: ((Long) -> Unit)? = null,
) : LoopPlayer {
    override val currentPositionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.coerceAtLeast(0)
    override val isPlaying: Boolean get() = player.isPlaying
    override fun seekTo(positionMs: Long) {
        onLoopSeek?.invoke(positionMs)
        player.seekTo(positionMs)
    }
    override val volume: Float get() = player.volume
    override fun setVolume(volume: Float) { player.volume = volume }
}
