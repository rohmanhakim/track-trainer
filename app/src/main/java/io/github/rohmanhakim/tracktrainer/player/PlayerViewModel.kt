package io.github.rohmanhakim.tracktrainer.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.cache.CacheBookkeeper
import io.github.rohmanhakim.tracktrainer.cache.CacheKeyFactory
import io.github.rohmanhakim.tracktrainer.data.local.LoopPoint
import io.github.rohmanhakim.tracktrainer.data.local.Preset
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.repo.LoopRepository
import io.github.rohmanhakim.tracktrainer.data.repo.PresetRepository
import io.github.rohmanhakim.tracktrainer.data.repo.TrackHealthChecker
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import io.github.rohmanhakim.tracktrainer.loop.ExoLoopPlayer
import io.github.rohmanhakim.tracktrainer.loop.LoopController
import io.github.rohmanhakim.tracktrainer.loop.LoopState
import io.github.rohmanhakim.tracktrainer.youtube.Extractor
import io.github.rohmanhakim.tracktrainer.youtube.ExtractionException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What the player screen renders: one track's transport state. */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    /**
     * Transport/keep-screen-on flag: user intent (see [isPlayingIntent]) rather than the
     * player's transient `isPlaying`, which dips to false whenever the player buffers —
     * including every loop-seam seek to A (that dip made the button blink).
     */
    val isPlayingIntent: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Current playback speed (session-level; 0.25×–2×). */
    val speed: Float = 1f,
    /** Current A-B loop state, exposed by [loopController]. */
    val loopState: LoopState = LoopState(),
    /** Saved loop points for the open track. */
    val loopPoints: List<LoopPoint> = emptyList(),
    /** Non-null = playback can't run (dead source, decode failure) — surfaced, never a crash. */
    val error: PlayerError? = null,
)

/** Why playback can't run — surfaced in the UI, never a crash. */
sealed interface PlayerError {
    val message: String
}

/** Decode or source failure unrelated to a stale URL. */
object PlaybackFailed : PlayerError {
    override val message = "Can't play this file"
}

/**
 * Direct URL can't be served: the cache entry is gone and/or the URL expired (P2-8).
 * Carries the track id so the UI can offer a re-paste action that targets this row.
 */
data class UrlExpired(val trackId: Long) : PlayerError {
    override val message = "URL expired — paste a new one"
}

/** YouTube extraction failed — offer fallback ladder hints (direct URL paste / local import). */
data class ExtractionFailed(val trackId: Long) : PlayerError {
    override val message = "Couldn't play this YouTube video. Try a direct media URL or import a local file instead."
}

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
    private val loopRepository: LoopRepository,
    private val presetRepository: PresetRepository,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val bookkeeper: CacheBookkeeper,
    private val cache: SimpleCache,
    private val extractor: Extractor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    /** Null until a track is open; guards the rotation re-entry of [openTrack]. */
    private var openedTrackId: Long? = null
    /** Cache key of the currently open track (null for local `content://` playback). */
    private var currentCacheKey: String? = null
    /** Session-level speed; reapplied to each fresh ExoPlayer in [startPlayer], preserved across tracks. */
    private var currentSpeed = 1f
    /** One re-extraction attempt per open (prevents loops on YOUTUBE playback failure). */
    private var reextractAttempted = false
    private var positionJob: Job? = null
    /** Observes the open track's saved loop points; cancelled on each [openTrack]. */
    private var loopPointsJob: Job? = null
    private var playerListener: Player.Listener? = null
    /** Loop engine; outlives a single ExoPlayer so A/B survive player rebuilds (rotation). */
    private val loopController = LoopController(viewModelScope)
    /** Debounced preset auto-save; settled on release/clear so no change is lost (P3-4). */
    private val presetAutoSaver = PresetAutoSaver(viewModelScope) { presetRepository.upsert(it) }
    /** True while a preset restore is being applied — restore is not a user change. */
    private var applyingPreset = false

    init {
        // Log every loop seam so P3-2 deviation can be measured on-device via logcat.
        viewModelScope.launch {
            loopController.seamEvents.collect { event ->
                Log.d(
                    TAG,
                    "loop seam: boundary=${event.boundaryPositionMs} ms " +
                        "overrun=${event.overrunMs} ms → seekTo A=${event.seekToMs} ms",
                )
            }
        }
        // Reflect loop controller state (A/B, loop on/off, active point) in the UI.
        viewModelScope.launch {
            loopController.state.collect { state ->
                _uiState.update { it.copy(loopState = state) }
            }
        }
    }

    /**
     * Opens a track and starts playback. Idempotent for the current track: the screen's
     * LaunchedEffect re-fires after rotation and that must not restart playback (P1-4).
     */
    fun openTrack(trackId: Long) {
        if (trackId == openedTrackId && _player.value != null) return
        reextractAttempted = false
        loopPointsJob?.cancel()
        viewModelScope.launch {
            // Settle any pending preset write from the previous track before this one starts.
            presetAutoSaver.flush()
            _uiState.update { it.copy(isLoading = true, title = it.title, loopPoints = emptyList()) }
            val track = repository.getById(trackId)
            if (track == null) {
                fail(PlaybackFailed)
                return@launch
            }
            _uiState.update { it.copy(title = track.title) }
            // Stale A/B from a previously open track must not leak here (or into this
            // track's preset); restorePreset applies this track's saved preset below.
            loopController.clear()
            // Observe this track's saved loop points so the UI can list/restore them (P3-1, P3-4).
            loopPointsJob = viewModelScope.launch {
                loopRepository.observeLoopPoints(track.id).collect { points ->
                    _uiState.update { it.copy(loopPoints = points) }
                }
            }
            // Health check on open (T04's How): this is the first code that really
            // re-reads the URI — a dead grant/file must surface here as an error,
            // never as a SecurityException inside ExoPlayer.
            if (healthChecker.findMissing(listOf(track)).isNotEmpty()) {
                fail(PlaybackFailed)
                return@launch
            }
            // YOUTUBE: cache hit ⇒ zero extraction, play from stored streamUrl.
            // Cache miss / stale URL ⇒ extract a fresh progressive URL, then play.
            if (track.sourceType == SourceType.YOUTUBE) {
                val cacheKey = cacheKeyFor(track)
                if (cacheKey != null && withContext(Dispatchers.IO) { isTrackCached(cacheKey) }) {
                    Log.d(TAG, "cache hit — zero extraction for YouTube track $trackId (key=$cacheKey)")
                    startPlayer(track)
                    restorePreset(track.id)
                    return@launch
                }
                val resolved = withContext(Dispatchers.IO) { extractor.resolve(track.sourceRef) }
                val updatedTrack = track.copy(
                    streamUrl = resolved.progressiveUrl,
                    itag = resolved.itag,
                    needsRefresh = false,
                )
                withContext(Dispatchers.IO) { repository.update(updatedTrack) }
                startPlayer(updatedTrack)
                restorePreset(track.id)
                return@launch
            }
            // P2-8: a direct track whose cached copy is gone can never play — prompt
            // re-paste instead of attempting playback into a dead (expired) URL.
            val cacheKey = cacheKeyFor(track)
            if (
                track.sourceType == SourceType.DIRECT &&
                track.needsRefresh &&
                cacheKey != null &&
                !withContext(Dispatchers.IO) { isTrackCached(cacheKey) }
            ) {
                fail(UrlExpired(track.id))
                return@launch
            }
            startPlayer(track)
            restorePreset(track.id)
        }
    }

    fun togglePlayPause() {
        val player = _player.value ?: return
        // Branch on intent, not isPlaying: a tap during the seam's transient buffering
        // must pause, not silently keep playing.
        if (isPlayingIntent(player.playWhenReady, player.playbackState)) {
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

    /**
     * Set playback speed (session-level, survives pause/play). Applied live to the player;
     * if the player isn't built yet the stored value is applied in [startPlayer].
     */
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 2.0f)
        currentSpeed = clamped
        _uiState.update { it.copy(speed = clamped) }
        schedulePresetSave()
        val player = _player.value ?: return
        player.setPlaybackParameters(PlaybackParameters(clamped))
    }

    /**
     * Sets loop point A to the current playhead position (tap to set / move).
     * Manual A/B edits drop the active loop point: the bounds no longer match the
     * named point, so preset restore must not resurrect its stale bounds.
     */
    fun setLoopA() {
        val player = _player.value ?: return
        loopController.setActiveLoopPoint(null)
        loopController.setA(player.currentPosition)
        schedulePresetSave()
    }

    /** Sets loop point B to the current playhead position (tap to set / move). See [setLoopA]. */
    fun setLoopB() {
        val player = _player.value ?: return
        loopController.setActiveLoopPoint(null)
        loopController.setB(player.currentPosition)
        schedulePresetSave()
    }

    fun clearLoopA() {
        loopController.setActiveLoopPoint(null)
        loopController.clearA()
        schedulePresetSave()
    }

    fun clearLoopB() {
        loopController.setActiveLoopPoint(null)
        loopController.clearB()
        schedulePresetSave()
    }

    /** Toggles loop repetition on/off (loop-until-off). */
    fun toggleLoop() {
        val s = loopController.state.value
        loopController.setLoopEnabled(!s.loopEnabled)
        schedulePresetSave()
    }

    /** Saves the current A/B as a new named loop point (always creates; never overwrites). */
    suspend fun saveLoopPoint(label: String) {
        val s = loopController.state.value
        if (s.positionA < 0L || s.positionB <= s.positionA) return
        val trackId = openedTrackId ?: return
        val point = LoopPoint(trackId = trackId, label = label, positionA = s.positionA, positionB = s.positionB)
        val id = loopRepository.add(point)
        loopController.setActiveLoopPoint(id)
        loopController.setLoopEnabled(true)
        schedulePresetSave()
    }

    /** Loads a saved loop point's A/B into the controller and marks it active. */
    suspend fun selectLoopPoint(id: Long) {
        val point = loopRepository.getById(id) ?: return
        loopController.setA(point.positionA)
        loopController.setB(point.positionB)
        loopController.setActiveLoopPoint(id)
        loopController.setLoopEnabled(true)
        schedulePresetSave()
    }

    suspend fun renameLoopPoint(id: Long, label: String) {
        val point = loopRepository.getById(id) ?: return
        loopRepository.update(point.copy(label = label))
    }

    suspend fun deleteLoopPoint(id: Long) {
        val point = loopRepository.getById(id) ?: return
        val s = loopController.state.value
        if (s.activeLoopPointId == id) {
            loopController.setActiveLoopPoint(null)
            loopController.setLoopEnabled(false)
            schedulePresetSave()
        }
        loopRepository.remove(point)
    }

    /** Persists the current speed + loop state for the open track (debounced, P3-4). */
    private fun schedulePresetSave() {
        if (applyingPreset) return // restore is not a user change — never write it back
        val trackId = openedTrackId ?: return
        val s = loopController.state.value
        presetAutoSaver.schedule(
            Preset(
                trackId = trackId,
                activeLoopPointId = s.activeLoopPointId,
                loopEnabled = s.loopEnabled,
                speed = currentSpeed,
            ),
        )
    }

    /**
     * Applies the track's saved preset once the player is up (P3-4): preset wins over the
     * session-level speed; no preset ⇒ the session-level speed is the starting default and
     * no row is written for untouched tracks. An armed active loop point starts playback at
     * A (alignToAOnArm — same semantics as selectLoopPoint). Unsaved ad-hoc A/B bounds are
     * not in the V1 schema, so with no resolvable loop point the loop comes up off.
     */
    private suspend fun restorePreset(trackId: Long) {
        val preset = presetRepository.getByTrack(trackId)
        applyingPreset = true
        try {
            setSpeed(preset?.speed ?: currentSpeed)
            val point = preset?.activeLoopPointId?.let { loopRepository.getById(it) }
            if (preset != null && point != null) {
                loopController.setA(point.positionA)
                loopController.setB(point.positionB)
                loopController.setActiveLoopPoint(point.id)
                loopController.setLoopEnabled(preset.loopEnabled)
            } else {
                loopController.setLoopEnabled(false)
            }
        } finally {
            applyingPreset = false
        }
    }

    /**
     * Steps ±1 frame while paused (P3-5, F10). Uses `SeekParameters.EXACT` so a single
     * frame isn't snapped to the nearest sync frame, then restores `CLOSEST_SYNC` used by
     * the loop seam seeks. Falls back to ~33 ms (30 fps) when frame rate is unknown (R14).
     */
    fun frameStep(forward: Boolean) {
        val player = _player.value ?: return
        // Intent gate: player.isPlaying is transiently false during the seam seek's
        // buffering — a step tap in that window must not sneak a seek into a live loop.
        if (isPlayingIntent(player.playWhenReady, player.playbackState)) return
        val durationMs = _uiState.value.durationMs
        val current = player.currentPosition
        val frameRate = player.videoFormat?.frameRate ?: 0f
        val frameMs = frameDurationMs(frameRate)
        var target = if (forward) current + frameMs else current - frameMs
        target = if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(target)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        _uiState.update { it.copy(positionMs = target) }
        Log.d(TAG, "frame step: forward=$forward from=$current to=$target frameMs=$frameMs")
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
        // viewModelScope is already cancelled at this point; block briefly on one small
        // Room upsert rather than lose the owner's pending preset change (P3-4).
        runBlocking(Dispatchers.IO) { presetAutoSaver.flush() }
    }

    private fun startPlayer(track: Track) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(context)
            // http(s) items are served through the disk cache (read-through: streams while
            // writing). Local SAF references play untouched — P1 regression guard.
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            // Seek-to-A (loop seam) and bar seeks land on sync frames (P3-2).
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Take audio focus so a call or another app pauses us instead of two
            // sounds fighting; no MediaSession until Phase 7, this is the cheap half.
            .setAudioAttributes(audioAttributes, true)
            .build()
        // Apply the session speed to the fresh player (default 1× if never changed).
        player.setPlaybackParameters(PlaybackParameters(currentSpeed))
        // Bind the loop engine to this player instance; A/B state lives on the
        // controller and survives player rebuilds (rotation).
        loopController.attach(ExoLoopPlayer(player))
        // http(s) items are served through the disk cache (read-through: streams while
        // writing). Local SAF references play untouched — P1 regression guard.
        currentCacheKey = cacheKeyFor(track)
        // YouTube: serve the resolved progressive streamUrl through the cache (never the
        // watch URL — R6). LOCAL/DIRECT play sourceRef untouched (P1/P2-8 regression guard).
        val mediaUri = if (track.sourceType == SourceType.YOUTUBE) track.streamUrl ?: track.sourceRef else track.sourceRef
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .apply { currentCacheKey?.let { setCustomCacheKey(it) } }
            .build()
        val listener = object : Player.Listener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, state: Int) {
                // Intent, not isPlaying: the seam seek's transient buffering must not
                // flip the transport button to "Play" (the loop-seam blink bug).
                _uiState.update { it.copy(isPlayingIntent = isPlayingIntent(playWhenReady, state)) }
                if (state == Player.STATE_ENDED) {
                    // Whole stream has flowed through the cache: offline replay is now
                    // guaranteed (P2-3) and the dot can show in the library.
                    val trackId = openedTrackId
                    val cacheKey = currentCacheKey
                    if (trackId != null && cacheKey != null) {
                        viewModelScope.launch { bookkeeper.markPlayed(trackId, cacheKey) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback failed for ${track.sourceRef}", error)
                val trackId = openedTrackId
                viewModelScope.launch {
                    val key = currentCacheKey
                    // DIRECT: existing UrlExpired logic — cache miss ⇒ prompt re-paste.
                    if (track.sourceType == SourceType.DIRECT) {
                        val covered = key != null && withContext(Dispatchers.IO) { isTrackCached(key) }
                        if (trackId != null && !covered) {
                            repository.update(track.copy(needsRefresh = true))
                            fail(UrlExpired(trackId))
                        } else {
                            fail(PlaybackFailed)
                        }
                        return@launch
                    }
                    // YOUTUBE: one re-extraction attempt on playback failure (e.g. expired
                    // streamUrl). If re-extract also fails ⇒ ExtractionFailed with ladder hints.
                    if (track.sourceType == SourceType.YOUTUBE && trackId != null && !reextractAttempted) {
                        reextractAttempted = true
                        val currentTrack = repository.getById(trackId)
                        if (currentTrack == null) { fail(PlaybackFailed); return@launch }
                        try {
                            val resolved = withContext(Dispatchers.IO) { extractor.resolve(currentTrack.sourceRef) }
                            val retryTrack = currentTrack.copy(
                                streamUrl = resolved.progressiveUrl,
                                itag = resolved.itag,
                                needsRefresh = false,
                            )
                            withContext(Dispatchers.IO) { repository.update(retryTrack) }
                            _uiState.update { it.copy(isLoading = true, error = null) }
                            startPlayer(retryTrack)
                        } catch (e: ExtractionException) {
                            fail(ExtractionFailed(trackId))
                        }
                        return@launch
                    }
                    fail(PlaybackFailed)
                }
            }
        }
        player.addListener(listener)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        openedTrackId = track.id
        playerListener = listener
        _player.value = player
        // Preserve session speed across track opens (P1-3e); only refresh loading/title/duration.
        _uiState.update {
            it.copy(isLoading = false, title = track.title, durationMs = track.durationMs)
        }
        startPositionPolling(player)
    }

    /**
     * Stable cache key for [track], or null when the track must not be cached (local SAF
     * references). YouTube keys are `videoId + itag` (never the watch URL — R6); direct URL
     * keys are a hash of the normalized URL (R15).
     */
    private fun cacheKeyFor(track: Track): String? {
        return if (track.sourceRef.startsWith("http://") || track.sourceRef.startsWith("https://")) {
            when (track.sourceType) {
                SourceType.YOUTUBE -> {
                    val videoId = track.youtubeId
                    if (videoId == null) {
                        Log.w(TAG, "YouTube track ${track.id} has no youtubeId — caching disabled")
                        null
                    } else {
                        CacheKeyFactory.youtubeKey(videoId, track.itag ?: "0")
                    }
                }
                SourceType.DIRECT -> CacheKeyFactory.directKey(track.sourceRef)
                SourceType.LOCAL -> null
            }
        } else {
            null
        }
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

    private fun fail(error: PlayerError) {
        releaseInternal()
        openedTrackId = null
        _uiState.update { PlayerUiState(isLoading = false, error = error, title = it.title) }
    }

    /**
     * P2-8: the owner pasted a fresh URL for an expired direct track. Tear down the
     * player, persist the new sourceRef (clearing needsRefresh), then reopen so the
     * cache-backed pipeline retries with the fresh URL.
     */
    fun rePaste(trackId: Long, input: String) = viewModelScope.launch {
        releasePlayer()
        withContext(Dispatchers.IO) {
            val track = repository.getById(trackId) ?: return@withContext
            repository.update(track.copy(sourceRef = input, needsRefresh = false))
        }
        _uiState.update { it.copy(error = null) }
        openTrack(trackId)
    }

    /** True when [cacheKey] has at least one byte cached from position 0. */
    private fun isTrackCached(cacheKey: String): Boolean =
        cache.getCachedSpans(cacheKey).any { it.isCached && it.position == 0L && it.length > 0 }

    private fun releaseInternal() {
        positionJob?.cancel()
        positionJob = null
        loopController.detach()
        _player.value?.let { player -> playerListener?.let(player::removeListener); player.release() }
        playerListener = null
        currentCacheKey = null
        _player.value = null
        // Settle any debounced preset write now — teardown must never drop the last change.
        // (No-op when called from onCleared: the scope is already cancelled and the
        // runBlocking flush there covers it.)
        viewModelScope.launch { presetAutoSaver.flush() }
    }

    private companion object {
        private const val TAG = "TrackTrainer"
        private const val POSITION_POLL_MS = 250L
        private const val DECODER_LOG_TICKS = 20 // 20 × 250 ms ≈ 5 s between decoder logs

    }
}
