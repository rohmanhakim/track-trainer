package io.github.rohmanhakim.tracktrainer.library

/** Where a pasted URL claims to come from — used for paste-field tolerance checks (D10). */
sealed interface UrlKind {
    object YouTube : UrlKind
    object Direct : UrlKind
    object Invalid : UrlKind
}

/** Human-readable label used in wrong-kind hint strings (§5.1, D10). */
fun UrlKind.label(): String = when (this) {
    is UrlKind.YouTube -> "YouTube link"
    is UrlKind.Direct -> "direct media URL"
    is UrlKind.Invalid -> "valid link"
}

/**
 * Classifies a pasted string so a wrong-kind paste (e.g. a YouTube link into the Direct
 * field) surfaces a friendly hint instead of an error (§5.1, D10).
 *
 * YouTube: any youtube.com / youtu.be host (incl. /shorts). Anything else that parses as
 * http(s) is Direct. Pure — safe to call on any thread.
 */
fun classifyUrlKind(input: String): UrlKind {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return UrlKind.Invalid
    return try {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme ?: return UrlKind.Invalid
        val host = uri.host ?: return UrlKind.Invalid
        if (scheme !in listOf("http", "https")) return UrlKind.Invalid
        if (host.endsWith("youtube.com") || host.endsWith("youtu.be")) UrlKind.YouTube
        else UrlKind.Direct
    } catch (_: Exception) {
        UrlKind.Invalid
    }
}

/**
 * Pulls the YouTube video id out of a watch / shorts / youtu.be URL. Null when there's no
 * id — the stub still creates the row with a null youtubeId; T04 requires a real one
 * before extraction.
 */
fun extractYouTubeVideoId(url: String): String? {
    return try {
        val uri = java.net.URI(url.trim())
        val host = uri.host ?: return null
        if (!host.endsWith("youtube.com") && !host.endsWith("youtu.be")) return null
        val path = uri.path ?: return null
        if (path.startsWith("/shorts/")) return path.substringAfterLast("/").takeIf { it.isNotEmpty() }
        if (host.endsWith("youtu.be")) return path.removePrefix("/").takeIf { it.isNotEmpty() }
        val params = uri.query ?: return null
        params.split("&").map { it.split("=", limit = 2) }.firstOrNull { it.firstOrNull() == "v" }?.getOrNull(1)
    } catch (_: Exception) {
        null
    }
}