package io.github.rohmanhakim.tracktrainer.cache

/**
 * Placeholder cache-key factory for the T01 spike.
 *
 * YouTube keys are `videoId + itag` (never the watch URL, since YouTube URLs expire ~6 h — see R6).
 * Direct-URL keys are a normalized hash. The real, unit-tested factory lands in T02.
 */
object CacheKeyFactory {
    fun youtubeKey(videoId: String, itag: String): String = "yt|$videoId|$itag"

    fun directKey(mediaUrl: String): String = "direct|${mediaUrl.hashCode()}"
}