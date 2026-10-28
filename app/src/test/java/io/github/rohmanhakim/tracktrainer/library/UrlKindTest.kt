package io.github.rohmanhakim.tracktrainer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class UrlKindTest {

    @Test
    fun youtubeWatchUrlIsYouTubeKind() {
        assertSame(UrlKind.YouTube, classifyUrlKind("https://www.youtube.com/watch?v=UAJZ99jx5fY"))
    }

    @Test
    fun youtuBeIsYouTubeKind() {
        assertSame(UrlKind.YouTube, classifyUrlKind("https://youtu.be/UAJZ99jx5fY"))
    }

    @Test
    fun shortsIsYouTubeKind() {
        assertSame(UrlKind.YouTube, classifyUrlKind("https://www.youtube.com/shorts/Y0J3-wGMjkM"))
    }

    @Test
    fun wwwSubdomainIsYouTubeKind() {
        assertSame(UrlKind.YouTube, classifyUrlKind("https://www.youtube.com/watch?v=UAJZ99jx5fY"))
    }

    @Test
    fun googlevideoStreamIsDirectKind() {
        assertSame(
            UrlKind.Direct,
            classifyUrlKind("https://rr1---sn-x.googlevideo.com/videoplayback?id=o-ABC&itag=399"),
        )
    }

    @Test
    fun plainMp4IsDirectKind() {
        assertSame(UrlKind.Direct, classifyUrlKind("https://example.com/video.mp4"))
    }

    @Test
    fun httpUrlIsDirectKind() {
        // Any parseable http(s) URL that isn't YouTube is Direct; scheme alone doesn't
        // make it invalid.
        assertSame(UrlKind.Direct, classifyUrlKind("http://example.com/video.mp4"))
    }

    @Test
    fun emptyAndGarbageAreInvalid() {
        assertSame(UrlKind.Invalid, classifyUrlKind(""))
        assertSame(UrlKind.Invalid, classifyUrlKind("   "))
        assertSame(UrlKind.Invalid, classifyUrlKind("not a url at all"))
        assertSame(UrlKind.Invalid, classifyUrlKind("ftp://example.com/video.mp4"))
    }

    @Test
    fun extractVideoIdFromWatchUrl() {
        assertEquals("UAJZ99jx5fY", extractYouTubeVideoId("https://www.youtube.com/watch?v=UAJZ99jx5fY"))
        assertEquals("UAJZ99jx5fY", extractYouTubeVideoId("https://youtube.com/watch?v=UAJZ99jx5fY&t=120"))
    }

    @Test
    fun extractVideoIdFromYoutuBe() {
        assertEquals("UAJZ99jx5fY", extractYouTubeVideoId("https://youtu.be/UAJZ99jx5fY"))
    }

    @Test
    fun extractVideoIdFromShorts() {
        assertEquals("Y0J3-wGMjkM", extractYouTubeVideoId("https://www.youtube.com/shorts/Y0J3-wGMjkM"))
    }

    @Test
    fun extractVideoIdMissingIsNull() {
        assertNull(extractYouTubeVideoId("https://www.youtube.com/watch?no=v"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/feed/history"))
        assertNull(extractYouTubeVideoId("https://example.com/video.mp4"))
        assertNull(extractYouTubeVideoId("not a url"))
    }
}