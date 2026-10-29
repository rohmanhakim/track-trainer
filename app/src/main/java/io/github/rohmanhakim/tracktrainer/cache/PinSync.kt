package io.github.rohmanhakim.tracktrainer.cache

import android.util.Log
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.local.TrackDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Keeps [PinnedKeys] in sync with Room's pinned tracks so the evictor's pin exemption
 * is backed by real data. Creation starts the sync; no pin UI exists in Phase 2,
 * so pinning is driven programmatically (e.g. via [io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository.pinTrack]).
 */
@Singleton
class PinSync @Inject constructor(
    private val trackDao: TrackDao,
    private val pinnedKeys: PinnedKeys,
) {

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            trackDao.observePinnedTracks().collect { tracks ->
                val keys = tracks.mapNotNull { cacheKeyFor(it) }.toSet()
                pinnedKeys.replaceWith(keys)
                Log.d(TAG, "Pinned keys synced: ${keys.size} (${keys.joinToString(", ")})")
            }
        }
    }

    private fun cacheKeyFor(track: Track): String? {
        return when (track.sourceType) {
            SourceType.YOUTUBE -> {
                val videoId = track.youtubeId
                if (videoId == null) null
                else CacheKeyFactory.youtubeKey(videoId, track.itag ?: "0")
            }
            SourceType.DIRECT -> CacheKeyFactory.directKey(track.sourceRef)
            SourceType.LOCAL -> null
        }
    }

    companion object {
        private const val TAG = "TrackTrainer"
    }
}
