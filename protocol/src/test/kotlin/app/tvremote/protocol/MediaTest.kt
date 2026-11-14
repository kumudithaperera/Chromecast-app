package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    @Test fun classifiesByPathOnly() {
        assertEquals(MediaKind.HLS, mediaKindOf("https://cdn.test/live/master.m3u8?token=a.mp4"))
        assertEquals(MediaKind.DASH, mediaKindOf("https://cdn.test/v/manifest.MPD"))
        assertEquals(MediaKind.PROGRESSIVE, mediaKindOf("http://192.168.1.5:8096/film.mkv"))
        assertEquals(MediaKind.SUBTITLE, mediaKindOf("https://cdn.test/subs/en.vtt"))
        assertEquals(MediaKind.SUBTITLE, mediaKindOf("https://cdn.test/subs/en.srt"))
        assertNull(mediaKindOf("https://cdn.test/seg/000123.ts"))
        assertNull(mediaKindOf("https://cdn.test/page?file=movie.mp4"))
        assertNull(mediaKindOf("https://cdn.test/"))
    }

    @Test fun refusesNonWebAndOverlongUrls() {
        assertNull(mediaKindOf("file:///sdcard/movie.mp4"))
        assertNull(mediaKindOf("content://media/external/video.mp4"))
        assertNull(mediaKindOf("asset:///x.m3u8"))
        assertNull(mediaKindOf("https://cdn.test/" + "a".repeat(MAX_MEDIA_URL) + ".mp4"))
    }

    @Test fun prefersManifestsAndIsBounded() {
        val f = MediaFinds()
        assertFalse(f.hasStream)
        assertTrue(f.offer("https://cdn.test/preview.mp4", "https://site.test/"))
        assertTrue(f.offer("https://cdn.test/master.m3u8", "https://site.test/"))
        assertTrue(f.offer("https://cdn.test/ad.mp4", null))
        assertFalse(f.offer("https://cdn.test/ad.mp4", null))
        assertEquals("https://cdn.test/master.m3u8", f.best()!!.url)
        assertEquals("https://site.test/", f.best()!!.referer)
        repeat(50) { f.offer("https://cdn.test/s$it.vtt", "javascript:alert(1)") }
        assertEquals(MediaFinds.MAX_PER_PAGE, f.subtitleTracks().size)
        assertNull(f.subtitleTracks().first().referer)
        f.clear()
        assertNull(f.best())
    }
}
