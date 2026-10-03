package io.github.rohmanhakim.tracktrainer.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = repository.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

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
            } catch (e: Exception) {
                // Grant/read failures (revoked provider, unreadable file) must never
                // crash the app — T04 hardens this path properly with re-link.
                Log.w(TAG, "Failed to add track $uri", e)
                _messages.trySend("Couldn't add track — file may be unreadable")
            }
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

    companion object {
        private const val TAG = "TrackTrainer"
        private const val DEFAULT_TITLE = "Untitled track"
    }
}
