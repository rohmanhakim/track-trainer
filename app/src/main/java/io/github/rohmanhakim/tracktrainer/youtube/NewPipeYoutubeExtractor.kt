package io.github.rohmanhakim.tracktrainer.youtube

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService

/**
 * NewPipe-backed [Extractor] (D2 primary).
 *
 * Selects the best *progressive* (muxed A/V) stream at or below 720p (D3 quality ceiling), because
 * adaptive/DASH streams need muxing we don't do in V1. YouTube videos that lack any muxed stream
 * surface as a clean [ExtractionException] — that's a measured spike result, not a crash.
 */
class NewPipeYoutubeExtractor(private val context: Context) : Extractor {
    private companion object {
        @Volatile
        private var initialized = false

        fun ensureInitialized(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        NewPipe.init(downloader)
                        initialized = true
                    }
                }
            }
        }
    }

    override suspend fun resolve(watchUrl: String): ResolvedVideo =
        withContext(Dispatchers.IO) {
            ensureInitialized(NewPipeHttpDownloader())
            try {
                val service: StreamingService = NewPipe.getServiceByUrl(watchUrl)
                val extractor = service.getStreamExtractor(watchUrl)
                // The page must be fetched before getName()/getVideoStreams() are callable.
                extractor.fetchPage()
                val title = extractor.getName()
                val videoId = extractor.getId()
                // StreamExtractor.getLength() returns seconds (NewPipe convention).
                val durationMs = extractor.getLength() * 1000
                val streams = extractor.getVideoStreams()
                val best = selectProgressiveStream(streams)
                    ?: throw ExtractionException(
                        "No progressive (muxed A/V) stream at or below 720p available for $watchUrl")
                val itag = best.getFormatId().toString()
                val streamUrl = best.getUrl() ?: throw ExtractionException("No stream URL for $watchUrl")
                ResolvedVideo(videoId, title, durationMs, itag, streamUrl)
            } catch (e: Exception) {
                Log.e("NewPipeYtExtractor", "Extraction failed for $watchUrl", e)
                throw ExtractionException("Failed to resolve YouTube URL: $watchUrl", e)
            }
        }

    /**
     * Picks the best muxed (non-video-only) stream ≤720p. Prefers itag 22 (720p), then 18 (360p),
     * then the highest resolution ≤720p, falling back to the highest muxed stream available.
     */
    private fun selectProgressiveStream(streams: List<org.schabi.newpipe.extractor.stream.VideoStream>):
        org.schabi.newpipe.extractor.stream.VideoStream? {
        val muxed = streams.filter { !it.isVideoOnly }
        return muxed.firstOrNull { it.getFormatId() == 22 } // 720p
            ?: muxed.firstOrNull { it.getFormatId() == 18 } // 360p
            ?: muxed.filter { resolutionHeight(it.resolution) <= 720 }.maxByOrNull { resolutionHeight(it.resolution) }
            ?: muxed.maxByOrNull { resolutionHeight(it.resolution) }
    }

    private fun resolutionHeight(resolution: String?): Int {
        return (resolution ?: "").removeSuffix("p").toIntOrNull() ?: 0
    }
}
