package io.github.rohmanhakim.tracktrainer.youtube

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the production [Extractor] — yt-dlp primary, validated at gate G1 (D2). */
@Module
@InstallIn(SingletonComponent::class)
object YoutubeModule {

    @Provides
    @Singleton
    fun provideExtractor(@ApplicationContext context: Context): Extractor =
        YtDlpExtractor(context)
}