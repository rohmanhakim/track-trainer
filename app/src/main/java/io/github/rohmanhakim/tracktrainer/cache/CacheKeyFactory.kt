package io.github.rohmanhakim.tracktrainer.cache

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable cache keys for Media3 `SimpleCache`.
 *
 * Keys must outlive the media URLs they're derived from: YouTube progressive URLs expire
 * after ~6 h (R6), so YouTube keys are `videoId + itag` — never the watch/progressive URL.
 * Direct URL keys are a SHA-256 over a normalized form of the URL, so a re-pasted fresh
 * URL for the same stream hashes to the same cache entry (R15).
 */
object CacheKeyFactory {

    private const val YOUTUBE_PREFIX = "yt"
    private const val DIRECT_PREFIX = "direct"

    /** YouTube key: `yt|<videoId>|<itag>`. */
    fun youtubeKey(videoId: String, itag: String): String = "$YOUTUBE_PREFIX|$videoId|$itag"

    /** Prefix matching every itag for a video: `yt|<videoId>|`. */
    fun youtubeKeyPrefix(videoId: String): String = "$YOUTUBE_PREFIX|$videoId|"

    /**
     * Direct media URL key: `direct|<hex sha-256(normalized url)>`.
     *
     * Normalization lowercases scheme/host, sorts query parameters, drops the fragment, and
     * strips volatile YouTube signature params (`expire`, `sig`, `signature`, `lsig`, `ip`,
     * `ei`, `n`) so a re-pasted fresh URL for the same stream hashes to the same key.
     */
    fun directKey(mediaUrl: String): String = "$DIRECT_PREFIX|${sha256Hex(normalizeUrl(mediaUrl))}"

    private val VOLATILE_QUERY_PARAMS = setOf("expire", "sig", "signature", "lsig", "ip", "ei", "n")

    private fun normalizeUrl(url: String): String {
        return try {
            val parsed = java.net.URL(url)
            val scheme = parsed.protocol.lowercase(Locale.ROOT)
            val authority =
                parsed.host.lowercase(Locale.ROOT) + (if (parsed.port > 0) ":${parsed.port}" else "")
            val path = parsed.path.takeIf { it.isNotEmpty() } ?: "/"
            val normalizedQuery = parsed.query
                ?.split("&")
                ?.map { param ->
                    val parts = param.split("=", limit = 2)
                    parts[0] to (if (parts.size > 1) parts[1] else "")
                }
                ?.filter { (name, _) ->
                    name.isNotEmpty() && name.lowercase(Locale.ROOT) !in VOLATILE_QUERY_PARAMS
                }
                ?.sortedBy { it.first.lowercase(Locale.ROOT) }
                ?.joinToString("&") { (name, value) -> "$name=$value" }
                ?: ""
            "$scheme://$authority$path${if (normalizedQuery.isNotEmpty()) "?$normalizedQuery" else ""}"
        } catch (_: Exception) {
            // Best effort for non-URL strings: keep it deterministic.
            url.substringBefore('#').lowercase(Locale.ROOT)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}