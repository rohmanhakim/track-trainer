package io.github.rohmanhakim.tracktrainer.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.data.repo.TrackHealthChecker
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the player screen renders: one track's transport state. */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Non-null = playback can't run (dead source, decode failure) — surfaced, never a crash. */
    val error: String? = null,
)

/**
 * Owns the ExoPlayer instance (T05 requirement): it outlives composition so rotation
 * keeps playback (P1-4), and is torn down on back-navigation / ViewModel clearing so
 * no audio leaks (T05 "How").
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
    private val healthChecker: TrackHealthChecker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    /** Null until a track is open; guards the rotation re-entry of [openTrack]. */
    private var openedTrackId: Long? = null
    private var positionJob: Job? = null
    private var playerListener: Player.Listener? = null

    /**
     * Opens a track and starts playback. Idempotent for the current track: the screen's
     * LaunchedEffect re-fires after rotation and that must not restart playback (P1-4).
     */
    fun openTrack(trackId: Long) {
        if (trackId == openedTrackId && _player.value != null) return
        viewModelScope.launch {
            _uiState.update { PlayerUiState(isLoading = true, title = it.title) }
            val track = repository.getById(trackId)
            if (track == null) {
                fail("Track not found")
                return@launch
            }
            _uiState.update { it.copy(title = track.title) }
            // Health check on open (T04's How): this is the first code that really
            // re-reads the URI — a dead grant/file must surface here as an error,
            // never as a SecurityException inside ExoPlayer.
            if (healthChecker.findMissing(listOf(track)).isNotEmpty()) {
                fail("File missing — re-link it in the library")
                return@launch
            }
            startPlayer(trackId, track.sourceRef, track.title, track.durationMs)
        }
    }

    fun togglePlayPause() {
        val player = _player.value ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            // At STATE_ENDED playWhenReady is already true, so play() alone is a
            // no-op — restart explicitly so "Play" after the end replays from the top.
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = _player.value ?: return
        val durationMs = _uiState.value.durationMs
        val target = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        player.seekTo(target)
        // Update the label now — the 250 ms poll would make every seek look laggy (P1-2).
        _uiState.update { it.copy(positionMs = target) }
    }

    /** Pause without tearing down — used when the app leaves the foreground. */
    fun pause() {
        _player.value?.pause()
    }

    /** Back-navigation teardown: stop audio and free the player; a later open builds fresh. */
    fun releasePlayer() {
        releaseInternal()
        openedTrackId = null
        _uiState.value = PlayerUiState(isLoading = false)
    }

    override fun onCleared() {
        releaseInternal()
    }

    private fun startPlayer(trackId: Long, sourceRef: String, title: String, durationMs: Long) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(context)
            // Take audio focus so a call or another app pauses us instead of two
            // sounds fighting; no MediaSession until Phase 7, this is the cheap half.
            .setAudioAttributes(audioAttributes, true)
            .build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback failed for $sourceRef", error)
                fail("Can't play this file")
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(sourceRef))
        player.prepare()
        player.playWhenReady = true

        openedTrackId = trackId
        playerListener = listener
        _player.value = player
        _uiState.update { PlayerUiState(isLoading = false, title = title, durationMs = durationMs) }
        startPositionPolling(player)
    }

    /**
     * Position/duration feed for the seek bar — runs on the player's own (main) thread.
     * Every ~5 s while playing it also logs decoder throughput: drop counters are the
     * in-app assert for playback smoothness (AV1/emulator investigation, T05 follow-up).
     */
    // DecoderCounters is @UnstableApi in Media3; opt-in is scoped here — this debug
    // read is the only unstable-API usage in the ViewModel.
    @OptIn(markerClass = [UnstableApi::class])
    private fun startPositionPolling(player: ExoPlayer) {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            var ticks = 0
            var lastTotalFrames = 0
            while (isActive) {
                val duration = player.duration // C.TIME_UNSET (negative) until known
                _uiState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (duration > 0L) duration else it.durationMs,
                    )
                }
                if (++ticks % DECODER_LOG_TICKS == 0 && player.isPlaying) {
                    // Media3 1.11: getVideoDecoderCounters() → nullable DecoderCounters
                    // (VideoDecoderCounters no longer exists); ensureUpdated() syncs the
                    // decoder-thread counters before we read them.
                    player.videoDecoderCounters?.let { counters ->
                        counters.ensureUpdated()
                        val rendered = counters.renderedOutputBufferCount
                        val windowSec = DECODER_LOG_TICKS * POSITION_POLL_MS / 1000.0
                        Log.d(
                            TAG,
                            "decoder: ~${(rendered - lastTotalFrames) / windowSec} fps" +
                                " dropped=${counters.droppedBufferCount}" +
                                " skipped=${counters.skippedOutputBufferCount}" +
                                " maxConsecutiveDrop=${counters.maxConsecutiveDroppedBufferCount}" +
                                " renderedTotal=$rendered",
                        )
                        lastTotalFrames = rendered
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun fail(message: String) {
        releaseInternal()
        openedTrackId = null
        _uiState.update { PlayerUiState(isLoading = false, error = message, title = it.title) }
    }

    private fun releaseInternal() {
        positionJob?.cancel()
        positionJob = null
        _player.value?.let { player -> playerListener?.let(player::removeListener); player.release() }
        playerListener = null
        _player.value = null
    }

    private companion object {
        private const val TAG = "TrackTrainer"
        private const val POSITION_POLL_MS = 250L
        private const val DECODER_LOG_TICKS = 20 // 20 × 250 ms ≈ 5 s between decoder logs
    }
}
