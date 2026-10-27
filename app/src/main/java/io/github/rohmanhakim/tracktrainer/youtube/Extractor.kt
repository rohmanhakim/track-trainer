package io.github.rohmanhakim.tracktrainer.youtube

/** Result of resolving a YouTube watch URL to a playable progressive stream. */
data class ResolvedVideo(
    val videoId: String,
    val title: String,
    val durationMs: Long,
    /** itag of the selected progressive (muxed A/V) stream. */
    val itag: String,
    /** Direct progressive media URL (feed it straight into ExoPlayer / CacheDataSource). */
    val progressiveUrl: String,
)

/** Thrown when YouTube extraction fails for any reason. */
class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin extraction contract: no Android types, so it can be unit-tested on the JVM and swapped
 * between NewPipe (primary) and yt-dlp (fallback) at gate G1.
 */
interface Extractor {
    /**
     * Resolves a YouTube watch URL to a [ResolvedVideo].
     * @throws ExtractionException if the video can't be resolved (age-restricted, region-blocked,
     * no progressive stream, network error, ...).
     */
    suspend fun resolve(watchUrl: String): ResolvedVideo
}