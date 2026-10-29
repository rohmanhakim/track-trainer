package io.github.rohmanhakim.tracktrainer.library

import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import java.net.URI
import java.net.URLDecoder

/** Naming helpers for library rows — self-describing titles + source labels (Phase 2 polish). */
object TrackNaming {

    /**
     * Second-line label under the track title: the source URL for online tracks,
     * a readable document path for local files.
     */
    fun sourceLabel(track: Track): String {
        return when (track.sourceType) {
            SourceType.YOUTUBE -> track.sourceRef
            SourceType.DIRECT -> track.sourceRef
            SourceType.LOCAL -> localLabel(track.sourceRef)
        }
    }

    private fun localLabel(ref: String): String {
        return try {
            URI(ref).path?.substringAfterLast("/")?.let { URLDecoder.decode(it, "UTF-8") } ?: ref
        } catch (_: Exception) {
            ref
        }
    }

    /**
     * Derives a short source title for a direct media URL: `Direct Video (7s)` when
     * the URL carries a `dur` param (googlevideo does), `Direct Video` otherwise.
     * Titles are editable next phase — this is a best-effort default.
     */
    fun deriveSourceTitle(url: String): String {
        val durationSec = try {
            val uri = URI(url)
            val query = uri.query ?: ""
            val durValue =
                query.split("&").map { it.split("=", limit = 2) }
                    .firstOrNull { it.first() == "dur" }?.getOrNull(1)?.toFloatOrNull()
                    ?: 0f
            durValue
        } catch (_: Exception) {
            0f
        }
        return if (durationSec > 0f) {
            "Direct Video (${formatDurationSec(durationSec)})"
        } else {
            "Direct Video"
        }
    }

    private fun formatDurationSec(sec: Float): String {
        val totalSeconds = sec.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        } else {
            "${seconds}s"
        }
    }
}
