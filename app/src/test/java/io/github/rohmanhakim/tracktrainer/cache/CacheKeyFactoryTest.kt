package io.github.rohmanhakim.tracktrainer.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheKeyFactoryTest {

    private val videoId = "UAJZ99jx5fY"

    @Test
    fun youtubeKeyIsVideoIdPlusItagAndNeverTheUrl() {
        val key = CacheKeyFactory.youtubeKey(videoId, "401")
        assertEquals("yt|UAJZ99jx5fY|401", key)
        // Never the watch URL or the progressive URL — both expire ~6 h (R6).
        assertFalse(key.contains("youtube.com"))
        assertFalse(key.contains("youtu.be"))
        assertFalse(key.contains("googlevideo.com"))
    }

    @Test
    fun youtubeKeyPrefixMatchesEveryItagForTheVideo() {
        val prefix = CacheKeyFactory.youtubeKeyPrefix(videoId)
        assertEquals("yt|UAJZ99jx5fY|", prefix)
        assertTrue(CacheKeyFactory.youtubeKey(videoId, "401").startsWith(prefix))
        assertTrue(CacheKeyFactory.youtubeKey(videoId, "251").startsWith(prefix))
        assertFalse(CacheKeyFactory.youtubeKey("other", "401").startsWith(prefix))
    }

    @Test
    fun directKeyIsDirectPrefixPlus64Hex() {
        val key = CacheKeyFactory.directKey("https://example.com/video.mp4")
        val hex = key.substringAfter("direct|")
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun directKeyIsDeterministic() {
        val url = "https://example.com/video.mp4"
        assertEquals(CacheKeyFactory.directKey(url), CacheKeyFactory.directKey(url))
    }

    @Test
    fun directKeyIgnoresVolatileExpiryParams() {
        // Same stream, once with YouTube's short-lived signature params, once without.
        // A re-pasted fresh URL for the same stream must hit the same cache entry (R15).
        val expired = "https://rr1---sn-x.googlevideo.com/videoplayback?" +
            "expire=1793117401&ei=abc&ip=1.2.3.4&id=o-ABC&itag=399&mime=video%2Fmp4&sig=XYZ&lsig=LMN"
        val fresh = "https://rr1---sn-x.googlevideo.com/videoplayback?id=o-ABC&itag=399&mime=video%2Fmp4"
        assertEquals(CacheKeyFactory.directKey(expired), CacheKeyFactory.directKey(fresh))
    }

    @Test
    fun directKeyIgnoresParameterOrderAndHostCase() {
        val a = "https://Example.COM/Video.Mp4?b=2&a=1&n=token"
        val b = "https://example.com/Video.Mp4?a=1&b=2"
        assertEquals(CacheKeyFactory.directKey(a), CacheKeyFactory.directKey(b))
    }

    @Test
    fun directKeyDropsFragment() {
        assertEquals(
            CacheKeyFactory.directKey("https://example.com/video.mp4"),
            CacheKeyFactory.directKey("https://example.com/video.mp4#t=120"),
        )
    }

    @Test
    fun directKeyDiffersByStreamIdentity() {
        val base = "https://rr1---sn-x.googlevideo.com/videoplayback?id=o-ABC&itag=399&mime=video%2Fmp4&clen=18081110"
        val audio = base.replace("itag=399", "itag=251").replace("video%2Fmp4", "audio%2Fwebm").replace("18081110", "2278987")
        assertNotEquals(CacheKeyFactory.directKey(base), CacheKeyFactory.directKey(audio))
    }

    @Test
    fun directKeyOnRealSampleUrlsIsStableAcrossExpiry() {
        // The two forms of the same stream from the user's sample: one copy with the
        // short-lived params stripped must hash identically.
        val withParams = "https://rr1---sn-uigxxi0ujipnvo-nvjd.googlevideo.com/videoplayback?" +
            "expire=1793117401&ei=eXjgatGWDvGi9fwPo8u10Qo&ip=114.10.39.94&" +
            "id=o-AGLDBR_HXetX_c87JCZrH6Cdp10brZcxGTYiPGuaBiCK&itag=399&source=youtube&" +
            "mime=video%2Fmp4&clen=18081110&dur=148.250&sig=AE0s2JYwRA"
        val without = "https://rr1---sn-uigxxi0ujipnvo-nvjd.googlevideo.com/videoplayback?" +
            "id=o-AGLDBR_HXetX_c87JCZrH6Cdp10brZcxGTYiPGuaBiCK&itag=399&source=youtube&" +
            "mime=video%2Fmp4&clen=18081110&dur=148.250"
        assertEquals(CacheKeyFactory.directKey(withParams), CacheKeyFactory.directKey(without))
    }
}