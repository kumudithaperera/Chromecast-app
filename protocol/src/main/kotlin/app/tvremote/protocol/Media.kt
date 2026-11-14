package app.tvremote.protocol

import java.net.URI

/** What kind of media a URL points at, judged by its path. */
enum class MediaKind { HLS, DASH, PROGRESSIVE, SUBTITLE }

/** A stream or subtitle file a page asked for, with the Referer the browser sent for it. */
data class MediaRef(val url: String, val kind: MediaKind, val referer: String?)

/** URLs longer than this are ignored. */
const val MAX_MEDIA_URL = 2048

/**
 * Classifies a request URL by its path. Only http and https URLs count, so a page can't point the
 * TV's player at a local file or an app's content.
 */
fun mediaKindOf(url: String): MediaKind? {
    if (url.length > MAX_MEDIA_URL || !isWebUrl(url)) return null
    val path = try {
        URI(url).rawPath?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return null
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> MediaKind.HLS
        path.endsWith(".mpd") -> MediaKind.DASH
        path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov") -> MediaKind.PROGRESSIVE
        path.endsWith(".vtt") || path.endsWith(".srt") -> MediaKind.SUBTITLE
        else -> null
    }
}

/**
 * The streams and subtitles one page has requested, newest last. Bounded, so a page that requests
 * thousands of URLs can't grow it. Not thread-safe; callers synchronise.
 */
class MediaFinds {
    private val streams = ArrayList<MediaRef>()
    private val subtitles = ArrayList<MediaRef>()

    /** Records [url] if it is media. Returns true if it was new. */
    fun offer(url: String, referer: String?): Boolean {
        val kind = mediaKindOf(url) ?: return false
        val list = if (kind == MediaKind.SUBTITLE) subtitles else streams
        if (list.any { it.url == url }) return false
        if (list.size >= MAX_PER_PAGE) list.removeAt(0)
        list += MediaRef(url, kind, referer?.takeIf { it.length <= MAX_MEDIA_URL && isWebUrl(it) })
        return true
    }

    fun clear() {
        streams.clear()
        subtitles.clear()
    }

    val hasStream: Boolean get() = streams.isNotEmpty()

    /**
     * The stream to play: a manifest (HLS or DASH) before a plain file, because pages often fetch short
     * progressive clips (previews, ads) next to the real stream. Among equals, the most recent.
     */
    fun best(): MediaRef? =
        streams.lastOrNull { it.kind == MediaKind.HLS || it.kind == MediaKind.DASH } ?: streams.lastOrNull()

    fun subtitleTracks(): List<MediaRef> = subtitles.toList()

    companion object {
        const val MAX_PER_PAGE = 12
    }
}
