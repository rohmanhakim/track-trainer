package io.github.rohmanhakim.tracktrainer.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cheap source health check for library load (T04): is the persistable grant still
 * held, and does the referenced file actually open?
 *
 * P1-1a contract — this must never throw. Any failure (missing file, revoked grant,
 * malformed ref) is reported as "missing"; an uncaught exception here would escape
 * into viewModelScope and crash the app, which is exactly what the story forbids.
 * T05 should reuse this on track open.
 */
@Singleton
class TrackHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Ids of LOCAL tracks whose sourceRef cannot be resolved right now. */
    suspend fun findMissing(tracks: List<Track>): Set<Long> = withContext(Dispatchers.IO) {
        tracks.asSequence()
            .filter { it.sourceType == SourceType.LOCAL }
            .filterNot { isResolvable(it.sourceRef) }
            .map { it.id }
            .toSet()
    }

    private fun isResolvable(sourceRef: String): Boolean = try {
        val uri = sourceRef.toUri()
        val grantHeld = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!grantHeld) {
            // Silent otherwise — this branch must be observable to debug P1-1a cases.
            Log.w(TAG, "Source check: grant not held for $sourceRef")
            false
        } else {
            opens(uri).also { ok ->
                if (!ok) Log.w(TAG, "Source check: open returned no descriptor for $sourceRef")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Source unresolvable: $sourceRef", e)
        false
    }

    /**
     * The real probe: actually open the file. A deleted/renamed source throws
     * FileNotFoundException here — at load time — instead of at playback (T05).
     */
    private fun opens(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "openFileDescriptor failed for $uri", e)
        false
    }

    private companion object {
        private const val TAG = "TrackTrainer"
    }
}