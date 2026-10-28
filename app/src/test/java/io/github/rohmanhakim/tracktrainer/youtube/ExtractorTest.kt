package io.github.rohmanhakim.tracktrainer.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class ExtractorTest {

    @Test
    fun resolvedVideoHasAllFields() {
        val resolved = ResolvedVideo(
            videoId = "UAJZ99jx5fY",
            title = "Sample video",
            durationMs = 123456,
            itag = "18",
            progressiveUrl = "https://googlevideo.com/videoplayback?id=o-ABC",
            thumbnailUrl = "https://i.ytimg.com/vi/UAJZ99jx5fY/hqdefault.jpg",
        )
        assertEquals("UAJZ99jx5fY", resolved.videoId)
        assertEquals("Sample video", resolved.title)
        assertEquals(123456L, resolved.durationMs)
        assertEquals("18", resolved.itag)
        assertEquals("https://googlevideo.com/videoplayback?id=o-ABC", resolved.progressiveUrl)
        assertEquals("https://i.ytimg.com/vi/UAJZ99jx5fY/hqdefault.jpg", resolved.thumbnailUrl)
    }

    @Test
    fun resolvedVideoThumbnailIsOptional() {
        val resolved = ResolvedVideo(
            videoId = "UAJZ99jx5fY",
            title = "Sample video",
            durationMs = 123456,
            itag = "18",
            progressiveUrl = "https://googlevideo.com/videoplayback?id=o-ABC",
            thumbnailUrl = null,
        )
        assertNull(resolved.thumbnailUrl)
    }

    @Test
    fun extractionExceptionWrapsCause() {
        val cause = IOException("network down")
        val exception = ExtractionException("resolve failed", cause)
        assertEquals("resolve failed", exception.message)
        assertSame(cause, exception.cause)
        assertNotNull(exception.stackTrace)
    }

}