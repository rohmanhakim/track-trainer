package io.github.rohmanhakim.tracktrainer.cache

import android.content.Context
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

    /** No size cap until the owner asks for one (R7, Phase 7+). */
    private const val MAX_CACHE_BYTES: Long = Long.MAX_VALUE

    @Provides
    @Singleton
    fun provideCacheDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, "media-cache").apply { mkdirs() }

    @Provides
    @Singleton
    fun providePinnedKeys(): PinnedKeys = PinnedKeys()

    @Provides
    @Singleton
    fun provideCacheEvictor(pinnedKeys: PinnedKeys): PinAwareLruEvictor =
        PinAwareLruEvictor(
            maxBytes = MAX_CACHE_BYTES,
            isPinned = pinnedKeys::contains,
        )

    @Provides
    @Singleton
    fun provideSimpleCache(
        cacheDirectory: File,
        evictor: PinAwareLruEvictor,
    ): SimpleCache = SimpleCache(cacheDirectory, evictor)

    @Provides
    @Singleton
    fun provideCacheDataSourceFactory(cache: SimpleCache): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
}