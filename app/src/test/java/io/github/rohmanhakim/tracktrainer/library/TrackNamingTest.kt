package io.github.rohmanhakim.tracktrainer.library

import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNamingTest {

    @Test
    fun youtubeRowLabelIsTheWatchUrl() {
        val track = Track(
            sourceType = SourceType.YOUTUBE,
            sourceRef = "https://www.youtube.com/watch?v=QiK_CCbwqH0",
            title = "Short video",
        )
        assertEquals("https://www.youtube.com/watch?v=QiK_CCbwqH0", TrackNaming.sourceLabel(track))
    }

    @Test
    fun directRowLabelIsTheDirectUrl() {
        val track = Track(
            sourceType = SourceType.DIRECT,
            sourceRef =
                "https://rr2---sn-uigxxi0ujipnvo-nvjz.googlevideo.com/videoplayback?itag=399&clen=939195",
            title = "Direct Video (7s)",
        )
        assertEquals(
            "https://rr2---sn-uigxxi0ujipnvo-nvjz.googlevideo.com/videoplayback?itag=399&clen=939195",
            TrackNaming.sourceLabel(track),
        )
    }

    @Test
    fun deriveSourceTitleWithDurSeconds() {
        assertEquals(
            "Direct Video (7s)",
            TrackNaming.deriveSourceTitle("https://rr2.googlevideo.com/videoplayback?dur=7.233"),
        )
        assertEquals(
            "Direct Video (45s)",
            TrackNaming.deriveSourceTitle("https://example.com/video.mp4?dur=45"),
        )
    }

    @Test
    fun deriveSourceTitleWithDurMinutes() {
        assertEquals("Direct Video (3m 12s)", TrackNaming.deriveSourceTitle("https://example.com/v.mp4?dur=192"))
    }

    @Test
    fun deriveSourceTitleWithoutDur() {
        assertEquals("Direct Video", TrackNaming.deriveSourceTitle("https://example.com/video.mp4"))
    }

    @Test
    fun localRowLabelFallsBackToRef() {
        val track = Track(
            sourceType = SourceType.LOCAL,
            sourceRef = "content://media/external/document/song.mp4",
            title = "Song",
        )
        assertEquals("song.mp4", TrackNaming.sourceLabel(track))
    }
}
