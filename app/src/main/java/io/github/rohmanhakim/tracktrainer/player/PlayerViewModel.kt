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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.cache.CacheBookkeeper
import io.github.rohmanhakim.tracktrainer.cache.CacheKeyFactory
import io.github.rohmanhakim.tracktrainer.countin.CountCancelReason
import io.github.rohmanhakim.tracktrainer.countin.CountInConfig
import io.github.rohmanhakim.tracktrainer.countin.CountInController
import io.github.rohmanhakim.tracktrainer.countin.CountInPlayer
import io.github.rohmanhakim.tracktrainer.countin.CountInTrigger
import io.github.rohmanhakim.tracktrainer.countin.CountInTriggers
import io.github.rohmanhakim.tracktrainer.countin.HardcodedCountInConfig
import io.github.rohmanhakim.tracktrainer.countin.TrackCountInClicker
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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Last user-authored seek target until the player confirms it landed. */
    private val pendingSeek = PendingSeek()
    /** T02: phase-4 defaults; T04 swaps this for the track's Preset-backed source. */
    private val countInConfig: CountInConfig = HardcodedCountInConfig()

    /**
     * Count-in engine (P4-1/P4-2): boundary-anchored state machine over the T01 click
     * engine. Player/speed are read lazily like [loopController], so it survives
     * player rebuilds (rotation) and re-targets each fresh ExoPlayer.
     */
    private val countIn = CountInController(
        scope = viewModelScope,
        clicker = TrackCountInClicker(viewModelScope),
        player = object : CountInPlayer {
            override fun pause() {
                _player.value?.pause()
            }

            override fun play() {
                _player.value?.play()
            }
        },
        config = { countInConfig },
        playbackSpeed = { currentSpeed },
    )

    init {
        // Log every loop seam so P3-2 deviation can be measured on-device via logcat.
        viewModelScope.launch {
            loopController.seamEvents.collect { event ->
                Log.d(
                    TAG,
                    "loop seam: boundary=${event.boundaryPositionMs} ms " +
                        "overrun=${event.overrunMs} ms → seekTo A=${event.seekToMs} ms",
                )
                // T02 trigger (b): count before A on every seam restart. In-session
                // decision (phase-4 T02 "How"): the count starts AFTER the seam's
                // seek-to-A settles — freeze on the real restart frame, then count.
                if (!countIn.isEnabled || countIn.isCounting) return@collect
                // LoopController.tryEmit can resume this collector INLINE
                // (Main.immediate on main) — before pollLoop issues seekToA; observed
                // on the Redmi: seam counts anchored at B (30272 ms) instead of A.
                // Yield once so the seek is posted, then wait for its settle, so the
                // count freezes on A's frame (in-session decision: AFTER the seek).
                delay(1)
                if (!awaitLoopSeekSettled()) return@collect
                val player = _player.value ?: return@collect
                // The owner may have paused (or disarmed the loop) while the seek was
                // settling — then plain Phase-3 seam behaviour continues, no count.
                if (!player.playWhenReady || !loopController.state.value.loopEnabled) return@collect
                countIn.start(CountInTrigger.LOOP_SEAM, player.currentPosition)
            }
        }
        // Reflect loop controller state (A/B, loop on/off, active point) in the UI.
        viewModelScope.launch {
            loopController.state.collect { state ->
                _uiState.update { it.copy(loopState = state) }
            }
        }
        // Decision 7: intent/keep-screen-on must read "playing" for the whole count —
        // the player listener only fires on player events, so a count state change
        // (start, resume, cancel) recomputes it here; both paths agree because they
        // pass the same counting flag.
        viewModelScope.launch {
            countIn.state.collect {
                val player = _player.value
                _uiState.update { ui ->
                    ui.copy(
                        isPlayingIntent = isPlayingIntent(
                            playWhenReady = player?.playWhenReady ?: false,
                            playbackState = player?.playbackState ?: Player.STATE_IDLE,
                            counting = countIn.isCounting,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Waits for the loop's in-flight seek-to-A to land (T02 trigger (b)); false on
     * timeout — playback then continues count-less rather than hanging the seam.
     * Callers must delay/yield first: an inline-resumed collector can otherwise see
     * `inFlight == false` before pollLoop has even posted the seek (see the seam
     * collector) and read the pre-seek position.
     */
    private suspend fun awaitLoopSeekSettled(timeoutMs: Long = 500L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (pendingSeek.inFlight) delay(10L)
            true
        } ?: false

    /**
     * Opens a track and starts playback. Idempotent for the current track: the screen's
     * LaunchedEffect re-fires after rotation and that must not restart playback (P1-4).
     */
    fun openTrack(trackId: Long) {
        if (trackId == openedTrackId && _player.value != null) return
        // T02 cancel list: switching tracks aborts any count (rotation re-entry
        // returns above, so a mid-count rotation never cancels).
        countIn.cancel(CountCancelReason.TRACK_OPEN)
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
        // must pause, not silently keep playing. During a count the intent reads true
        // (decision 7), so this branch is also the count's cancel path.
        if (isPlayingIntent(player.playWhenReady, player.playbackState, countIn.isCounting)) {
            countIn.cancel(CountCancelReason.TRANSPORT)
            player.pause()
            syncPositionFromPlayer(player)
        } else {
            // At STATE_ENDED playWhenReady is already true, so play() alone is a
            // no-op — restart explicitly so "Play" after the end replays from the top.
            val ended = player.playbackState == Player.STATE_ENDED
            if (ended) seekTo(0L)
            // T02 triggers (a)/(c): count first when starting from rest at 00:00 or
            // exactly at an armed loop's A (decision 4); seekTo posts async, so trust
            // the ENDED target over a stale currentPosition read.
            val positionMs = if (ended) 0L else player.currentPosition
            val trigger = CountInTriggers.onPlayFromStopped(positionMs, loopController.state.value)
            if (trigger == null || !countIn.start(trigger, positionMs)) {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = _player.value ?: return
        // T02 cancel list: a scrub aborts a running count (stays paused at the anchor).
        countIn.cancel(CountCancelReason.SEEK)
        val durationMs = _uiState.value.durationMs
        val target = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        pendingSeek.onUserSeek(target)
        seekExactly(player, target)
        // Update the label now — the 250 ms poll would make every seek look laggy (P1-2).
        _uiState.update { it.copy(positionMs = target) }
    }

    /**
     * `SeekParameters.EXACT` sandwiched around the player default `CLOSEST_SYNC`: bar and
     * frame-step seeks must land exactly where the owner aimed — CLOSEST_SYNC snaps to a
     * keyframe (seconds away on typical GOPs) and Set A/B would capture that snap instead
     * of the timestamp on screen. Playback-thread messages are FIFO, so this seek resolves
     * under EXACT before CLOSEST_SYNC is restored and the loop seam's seek-to-A stays a
     * fast sync-frame seek (P3-2).
     */
    private fun seekExactly(player: ExoPlayer, targetMs: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    /**
     * Pause freezes the playhead — re-sync the label now so Set A/B don't capture the
     * up-to-250 ms-stale poll value. Skipped while a user seek is in flight: then the
     * authored target owns the label, not a pre-seek `currentPosition` read.
     */
    private fun syncPositionFromPlayer(player: ExoPlayer) {
        if (!pendingSeek.inFlight) {
            _uiState.update { it.copy(positionMs = player.currentPosition) }
        }
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
        if (_player.value == null) return
        loopController.setActiveLoopPoint(null)
        loopController.setA(playheadMs())
        schedulePresetSave()
    }

    /** Sets loop point B to the current playhead position (tap to set / move). See [setLoopA]. */
    fun setLoopB() {
        if (_player.value == null) return
        loopController.setActiveLoopPoint(null)
        loopController.setB(playheadMs())
        schedulePresetSave()
    }

    /**
     * The position Set A/B captures: the displayed playhead, never a fresh
     * `player.currentPosition` read — that still returns the pre-seek position while a
     * seek is in flight, which is how a precise step/scrub ended up capturing a point
     * away from the timestamp on screen (see [PendingSeek]).
     */
    private fun playheadMs(): Long = _uiState.value.positionMs

    /**
     * A loop seek-to-A landed on its `CLOSEST_SYNC` keyframe — from now on that's where
     * playback actually restarts, so correct A (and its label) to it and persist to the
     * active named point: label == saved row == playback, converging after the first
     * seek-to-A. At most one correction per session — a corrected A sits on the sync
     * grid, so later seek-to-A land exactly on it. Skips no-op landings and ones that
     * would invalidate the loop (A ≥ B).
     */
    private fun correctLoopA(landedMs: Long) {
        val s = loopController.state.value
        if (s.positionA < 0L || s.positionA == landedMs) return
        val b = s.positionB
        if (landedMs < 0L || (b >= 0L && landedMs >= b)) {
            Log.w(TAG, "loop A correction skipped: landed=$landedMs outside valid range (B=$b)")
            return
        }
        Log.d(TAG, "loop A corrected: ${s.positionA} → $landedMs (CLOSEST_SYNC restart point)")
        loopController.setA(landedMs)
        // The playhead is where the seek just landed — sync the label immediately
        // rather than waiting for the next position-poll tick.
        _uiState.update { it.copy(positionMs = landedMs) }
        val id = s.activeLoopPointId
        if (id != null) {
            viewModelScope.launch {
                loopRepository.getById(id)?.let { loopRepository.update(it.copy(positionA = landedMs)) }
            }
        }
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
        // T02 cancel list: disarming the loop aborts a running count.
        if (s.loopEnabled) countIn.cancel(CountCancelReason.LOOP_OFF)
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
     * Steps ±1 frame while paused (P3-5, F10) via [seekExactly] so a single frame isn't
     * snapped to the nearest sync frame. Falls back to ~33 ms (30 fps) when frame rate is
     * unknown (R14).
     */
    fun frameStep(forward: Boolean) {
        val player = _player.value ?: return
        // Intent gate: player.isPlaying is transiently false during the seam seek's
        // buffering — a step tap in that window must not sneak a seek into a live loop.
        // counting=true keeps the gate closed mid-count too (a frozen frame isn't steppable).
        if (isPlayingIntent(player.playWhenReady, player.playbackState, countIn.isCounting)) return
        val durationMs = _uiState.value.durationMs
        // The displayed playhead, not player.currentPosition: while the previous step's
        // seek is in flight the player still reports the pre-seek position, so rapid taps
        // would all step from a stale base and stall/drift the stepping.
        val current = _uiState.value.positionMs
        val frameRate = player.videoFormat?.frameRate ?: 0f
        val frameMs = frameDurationMs(frameRate)
        var target = if (forward) current + frameMs else current - frameMs
        target = if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        pendingSeek.onUserSeek(target)
        seekExactly(player, target)
        _uiState.update { it.copy(positionMs = target) }
        Log.d(TAG, "frame step: forward=$forward from=$current to=$target frameMs=$frameMs")
    }

    /** Pause without tearing down — used when the app leaves the foreground. */
    fun pause() {
        // T02: leaving the foreground aborts a count — clicks are app audio, and the
        // count would otherwise resume playback into the background (Phase 1's
        // no-background-audio invariant; MediaSession is Phase 7).
        countIn.cancel(CountCancelReason.BACKGROUND)
        _player.value?.let { player ->
            player.pause()
            syncPositionFromPlayer(player)
        }
    }

    /** Back-navigation teardown: stop audio and free the player; a later open builds fresh. */
    fun releasePlayer() {
        countIn.cancel(CountCancelReason.RELEASE)
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
        pendingSeek.reset()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // http(s) items are served through the disk cache (read-through: streams while
        // writing). LOCAL SAF references bypass the cache entirely: the cache's upstream
        // is HTTP-only (content:// fails with "unknown protocol: content") and local
        // bytes must never be copied into the cache (cacheKeyFor → null invariant).
        val mediaSourceFactory = if (track.sourceType == SourceType.LOCAL) {
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        } else {
            DefaultMediaSourceFactory(cacheDataSourceFactory)
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            // Sync-frame seek-to-A for the loop seam (P3-2); user-authored seeks
            // (bar / frame step) override this with EXACT via seekExactly().
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Take audio focus so a call or another app pauses us instead of two
            // sounds fighting; no MediaSession until Phase 7, this is the cheap half.
            .setAudioAttributes(audioAttributes, true)
            .build()
        // Apply the session speed to the fresh player (default 1× if never changed).
        player.setPlaybackParameters(PlaybackParameters(currentSpeed))
        // Bind the loop engine to this player instance; A/B state lives on the
        // controller and survives player rebuilds (rotation). Loop seeks land via
        // CLOSEST_SYNC — flagged so correctLoopA can fix the label to the real
        // restart point when the landing is known.
        loopController.attach(ExoLoopPlayer(player) { pendingSeek.onLoopSeek() })
        // Null for LOCAL — the same no-cache invariant the media-source factory above
        // enforces; videoId+itag (YouTube) or normalized-URL-hash (DIRECT) otherwise.
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
                // counting (T02 decision 7) keeps it armed while frozen on the anchor.
                _uiState.update {
                    it.copy(isPlayingIntent = isPlayingIntent(playWhenReady, state, countIn.isCounting))
                }
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

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Once the playback thread has processed a seek its position is
                // trustworthy again: user seeks hand the label back to the position
                // poll (a superseded landing keeps the newer target pending), while
                // loop seek-to-A landings correct A to the real restart point.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val settled = pendingSeek.onSeekSettled(newPosition.positionMs)
                    if (settled != null && settled.wasLoopSeek) {
                        correctLoopA(settled.landedMs)
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
                        // While a user seek is in flight the authored target owns the
                        // label — a mid-seek currentPosition read is the pre-seek value.
                        positionMs = if (pendingSeek.inFlight) {
                            it.positionMs
                        } else {
                            player.currentPosition
                        },
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
        pendingSeek.reset()
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
