package io.github.rohmanhakim.tracktrainer.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.prefs.AppPreferences
import io.github.rohmanhakim.tracktrainer.data.repo.TrackHealthChecker
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import io.github.rohmanhakim.tracktrainer.youtube.Extractor
import io.github.rohmanhakim.tracktrainer.youtube.ExtractionException
import javax.inject.Inject
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the library screen renders: the rows plus which ones lost their source file. */
data class LibraryUiState(
    val tracks: List<Track> = emptyList(),
    val missingIds: Set<Long> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
    private val healthChecker: TrackHealthChecker,
    private val preferences: AppPreferences,
    private val extractor: Extractor,
) : ViewModel() {

    /**
     * Health re-runs on every DB emission (library load, add, re-link) — checked on
     * load per T04's "How", never a background poller; a new subscription after
     * process death re-checks from scratch.
     */
    val uiState: StateFlow<LibraryUiState> = repository.observeTracks()
        .flatMapLatest { tracks ->
            flow {
                emit(LibraryUiState(tracks = tracks, missingIds = healthChecker.findMissing(tracks)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** True while a YouTube link is being resolved (~30–60 s). Gates the paste-dialog UI. */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /** One-shot "add track" job; safe to re-enter, failures surface as a snackbar. */
    fun addTrack(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Persistable grant first: the read below and every future open
                    // depend on it surviving process death and reboot (P1-1 AC, D11).
                    // modeFlags take only READ/WRITE per ContentResolver contract —
                    // the picker's intent already carries FLAG_GRANT_PERSISTABLE.
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val title = readDisplayName(uri)
                    val durationMs = readDurationMs(uri)
                    repository.add(
                        Track(
                            sourceType = SourceType.LOCAL,
                            sourceRef = uri.toString(),
                            title = title,
                            durationMs = durationMs,
                        ),
                    )
                }
                showCloudHintOnce(uri)
            } catch (e: Exception) {
                // Grant/read failures (revoked provider, unreadable file) must never
                // crash the app — the missing-state + re-link path is T04's answer.
                Log.w(TAG, "Failed to add track $uri", e)
                _messages.trySend("Couldn't add track — file may be unreadable")
            }
        }
    }

    /**
     * Add a pasted URL as a new track, tolerance-checked against the option the owner
     * picked (§5.1, D10). Wrong-kind input surfaces a friendly hint via [messages],
     * never an error; the row is only created when kinds match.
     *
     * YouTube is a T03 stub: it creates the row so the chooser acceptance is literally
     * satisfied, but extraction + playback land in T04 (yt-dlp).
     */
    fun addUrl(input: String, chosenKind: UrlKind) {
        val kind = classifyUrlKind(input)
        if (kind == UrlKind.Invalid) {
            _messages.trySend(context.getString(R.string.hint_invalid_url))
            return
        }
        if (kind != chosenKind) {
            _messages.trySend(context.getString(when (chosenKind) {
                is UrlKind.YouTube -> R.string.hint_wrong_kind_youtube
                is UrlKind.Direct -> R.string.hint_wrong_kind_direct
                is UrlKind.Invalid -> throw IllegalStateException("Unreachable: chosen kind can't be Invalid")
            }))
            return
        }
        viewModelScope.launch {
            _resolving.value = true
            try {
                withContext(Dispatchers.IO) {
                    when (chosenKind) {
                    is UrlKind.YouTube -> {
                        val resolved = withContext(Dispatchers.IO) { extractor.resolve(input) }
                        // Thumbnail is best-effort: a failed download never fails the add.
                        val thumbnailPath = resolved.thumbnailUrl?.let { thumbnailUrl: String ->
                            downloadThumbnail(resolved.videoId, thumbnailUrl)
                        }
                        withContext(Dispatchers.IO) {
                            repository.add(
                                Track(
                                    sourceType = SourceType.YOUTUBE,
                                    sourceRef = input,
                                    streamUrl = resolved.progressiveUrl,
                                    youtubeId = resolved.videoId,
                                    itag = resolved.itag,
                                    title = resolved.title,
                                    durationMs = resolved.durationMs,
                                    thumbnailPath = thumbnailPath,
                                )
                            )
                        }
                        _messages.trySend(context.getString(R.string.youtube_added, resolved.title))
                    }
                    is UrlKind.Direct -> {
                        repository.add(
                            Track(
                                sourceType = SourceType.DIRECT,
                                sourceRef = input,
                                title = deriveTitle(input),
                            )
                        )
                    }
                    is UrlKind.Invalid -> Unit
                }
            }
            } catch (e: ExtractionException) {
                Log.w(TAG, "YouTube extraction failed for $input", e)
                _messages.trySend(context.getString(R.string.tt_youtube_resolve_failed))
                _messages.trySend(context.getString(R.string.hint_extraction_fallback))
            } finally {
                _resolving.value = false
            }
        }
    }

    private fun deriveTitle(url: String): String =
        try {
            java.net.URI(url).path
                ?.substringAfterLast("/")
                ?.takeIf { it.isNotEmpty() }
                ?: java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }

    /**
     * Re-point an existing track at a fresh source (P1-1a re-link). Order matters:
     * store the new reference before releasing the old grant, so a mid-way failure
     * never leaves the row pointing at nothing; release failures are benign.
     */
    fun relinkTrack(trackId: Long, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = repository.getById(trackId)
                        ?: error("Track $trackId no longer exists")
                    // Same contract as addTrack: READ-only modeFlags (see above).
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    repository.update(
                        existing.copy(
                            sourceRef = uri.toString(),
                            title = readDisplayName(uri),
                            durationMs = readDurationMs(uri),
                        ),
                    )
                    releaseOldGrant(existing.sourceRef, uri.toString())
                }
                showCloudHintOnce(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-link track $trackId to $uri", e)
                _messages.trySend("Couldn't re-link track — file may be unreadable")
            }
        }
    }

    /**
     * 01-product-overview §5.1: non-blocking one-time hint when the picked file
     * isn't stored on this device. Providers expose no "is-cloud" flag, so this is
     * an allowlist heuristic — unknown authority ⇒ maybe-cloud; a false positive
     * costs one ignored toast, and only ever once.
     */
    private suspend fun showCloudHintOnce(uri: Uri) {
        if (uri.authority in LOCAL_AUTHORITIES) return
        if (preferences.cloudHintShown.first()) return
        preferences.markCloudHintShown()
        _messages.trySend("This file isn't stored on this device — offline playback won't work.")
    }

    private fun releaseOldGrant(oldRef: String, newRef: String) {
        if (oldRef == newRef) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                oldRef.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Already released/revoked — nothing to give back; must not fail the re-link.
            Log.w(TAG, "Old grant not held for $oldRef", e)
        }
    }

    private fun readDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: DEFAULT_TITLE
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            // Duration is cosmetic at this stage; T05 will read it again for playback.
            Log.w(TAG, "Duration read failed for $uri", e)
            0L
        } finally {
            retriever.release()
        }
    }

    /** Best-effort thumbnail download to `filesDir/thumbnails/<videoId>.jpg`. Never fails the add. */
    private suspend fun downloadThumbnail(videoId: String, thumbnailUrl: String): String? {
        return try {
            val thumbDir = File(context.filesDir, "thumbnails")
            thumbDir.mkdirs()
            val file = File(thumbDir, "$videoId.jpg")
            URL(thumbnailUrl).openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail download failed for $videoId", e)
            null
        }
    }

    companion object {
        private const val TAG = "TrackTrainer"
        private const val DEFAULT_TITLE = "Untitled track"

        /** Authorities that are demonstrably on-device (§5.1 cloud-hint allowlist). */
        private val LOCAL_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "media",
            "com.android.providers.downloads.documents",
        )
    }
}
