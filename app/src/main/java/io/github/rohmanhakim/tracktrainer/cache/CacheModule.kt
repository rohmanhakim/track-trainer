package io.github.rohmanhakim.tracktrainer.cache

import android.content.Context
import android.util.Log
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    /**
     * Debug builds use a small cache cap so P2-5's LRU eviction can be exercised on device
     * against a real SimpleCache (verified via logs / cache dir). Release builds stay
     * uncapped (R7) — flip DEBUG_CACHE_CAP before cutting a release.
     */
    private const val DEBUG_CACHE_CAP = true
    private const val DEBUG_MAX_CACHE_BYTES: Long = 50 * 1024 * 1024 // 50 MB (P2-5 test pressure)

    private val MAX_CACHE_BYTES: Long
        get() = if (DEBUG_CACHE_CAP) DEBUG_MAX_CACHE_BYTES else Long.MAX_VALUE

    @Provides
    @Singleton
    fun provideCacheDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, "media-cache").apply { mkdirs() }

    @Provides
    @Singleton
    fun providePinnedKeys(): PinnedKeys = PinnedKeys()

    /**
     * Forces [PinSync] construction (it keeps [PinnedKeys] in sync with Room's pinned
     * tracks, backing the evictor's pin exemption). Creation alone starts the sync.
     */
    @Provides
    @Singleton
    fun provideCacheEvictor(
        pinnedKeys: PinnedKeys,
        pinSync: PinSync,
    ): PinAwareLruEvictor =
        PinAwareLruEvictor(
            maxBytes = MAX_CACHE_BYTES,
            isPinned = pinnedKeys::contains,
            onEvict = { span ->
                // P2-5 evidence: every real eviction is logged so we can verify oldest-
                // unpinned-first order against the cache directory contents.
                Log.d("TrackTrainer", "EVICT key=${span.key} size=${span.length} bytes")
            },
        )

    @Provides
    @Singleton
    fun provideSimpleCache(
        cacheDirectory: File,
        evictor: PinAwareLruEvictor,
    ): SimpleCache = SimpleCache(cacheDirectory, evictor).apply {
        // Wire the evictor's removeSpan to this cache so evicted spans are
        // actually deleted from disk (breaks circular dep: evictor needed to
        // build cache, cache needed to wire evictor).
        evictor.removeSpan = { span -> this.removeSpan(span) }
    }

    @Provides
    @Singleton
    fun provideCacheDataSourceFactory(cache: SimpleCache): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
}