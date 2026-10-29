package io.github.rohmanhakim.tracktrainer.cache

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps [Track] cache bookkeeping in sync with what actually sits in [SimpleCache].
 *
 * Phase 2 policy: a track is marked `offline` (dot appears in the library) only once its
 * stream has played to completion — then every byte is on disk and airplane-mode replay is
 * guaranteed (P2-3). Eviction-driven dot reset is intentionally absent in Phase 2
 * (nothing is evicted while uncapped); pinned tracks are never evicted anyway.
 */
@Singleton
class CacheBookkeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
    private val cache: SimpleCache,
) {

    /**
     * Mark [trackId] as fully cached once playback of [cacheKey] completed.
     */
    suspend fun markPlayed(trackId: Long, cacheKey: String) =
        withContext(Dispatchers.IO) {
            val track = repository.getById(trackId) ?: return@withContext
            val cachedBytes =
                cache.getCachedSpans(cacheKey).firstOrNull { it.isCached && it.position == 0L }?.length
                    ?: 0L
            if (cachedBytes == 0L) {
                // Nothing actually reached SimpleCache — dot must stay off (offline=false
                // is the honest state; re-extract on next open will try again).
                return@withContext
            }
            repository.update(
                track.copy(
                    offline = true,
                    cacheBytes = cachedBytes,
                    lastPlayedAt = System.currentTimeMillis(),
                ),
            )
        }
}