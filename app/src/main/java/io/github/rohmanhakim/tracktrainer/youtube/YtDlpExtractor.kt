package io.github.rohmanhakim.tracktrainer.youtube

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * yt-dlp-backed [Extractor] (D2 fallback, activated by gate G1).
 *
 * Delegates to the yt-dlp binary bundled by youtubedl-android: `--dump-json` with a muxed-stream
 * format selector at or below 720p (D3 quality ceiling — no A/V muxing in V1). Bundled yt-dlp can
 * be refreshed in-app via [YoutubeDL.updateYoutubeDL], which is the practical half of R1's
 * "updates within hours" mitigation when YouTube breaks extraction.
 *
 * Errors surface as [ExtractionException] — same contract as [NewPipeYoutubeExtractor], so the two
 * are swap-compatible behind [Extractor] at gate G1.
 */
class YtDlpExtractor(private val context: Context) : Extractor {

    private companion object {
        @Volatile
        private var initialized = false

        fun ensureInitialized(ctx: Context) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        YoutubeDL.getInstance().init(ctx)
                        initialized = true
                    }
                }
            }
        }
    }

    override suspend fun resolve(watchUrl: String): ResolvedVideo =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)
            try {
                val request = YoutubeDLRequest(watchUrl)
                    .addOption("-f", "best[height<=720]/best")
                    .addOption("--no-playlist")
                val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
                val videoId = info.id ?: extractVideoId(watchUrl)
                    ?: throw ExtractionException("No video id in yt-dlp response for $watchUrl")
                val title = info.title ?: info.fulltitle ?: ""
                // duration is seconds (yt-dlp / --dump-json convention).
                val durationMs = info.duration.toLong() * 1000
                val streamUrl = info.url
                    ?: throw ExtractionException("No playable URL in yt-dlp response for $watchUrl")
                ResolvedVideo(videoId, title, durationMs, info.formatId ?: "unknown", streamUrl)
            } catch (e: Exception) {
                Log.e("YtDlpExtractor", "Extraction failed for $watchUrl", e)
                throw ExtractionException("Failed to resolve YouTube URL: $watchUrl", e)
            }
        }

    /** Fallback id when yt-dlp's JSON omits it: parse v= from the watch URL. */
    private fun extractVideoId(url: String): String? {
        val param = "v="
        val start = url.indexOf(param) ?: return null
        val after = url.substring(start + param.length)
        return after.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }.takeIf { it.isNotEmpty() }
    }
}