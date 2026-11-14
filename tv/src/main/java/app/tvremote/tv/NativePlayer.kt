package app.tvremote.tv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.tvremote.protocol.Cue
import app.tvremote.protocol.MAX_SUBTITLE_SHIFT
import app.tvremote.protocol.MediaKind
import app.tvremote.protocol.MediaRef
import app.tvremote.protocol.VideoAction
import app.tvremote.protocol.VideoState
import app.tvremote.protocol.cueTextAt
import app.tvremote.protocol.isWebUrl
import app.tvremote.protocol.parseSubtitles
import app.tvremote.protocol.siteOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Plays a stream found on a page in the TV's own player (Media3), full screen over the browser.
 *
 * Why: the page's player runs inside the WebView, where A/V sync drifts on weak hardware and the
 * phone can only reach it through the page. Here the phone's video controls drive ExoPlayer directly,
 * and side-loaded subtitles are drawn by us so their timing can be nudged.
 *
 * What it sends with each request is what the browser itself would have sent: the same User-Agent,
 * the Referer the page used for that request, and cookies only when the stream is on the page's own site.
 * Protected (DRM) streams have no licence here and will fail; the player then closes with a message.
 */
@OptIn(UnstableApi::class)
class NativePlayer(
    private val context: Context,
    private val container: FrameLayout,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlayerChanged()
        fun onPlayerClosed(message: String?)
    }

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var subtitleView: TextView? = null
    private var cues: List<Cue> = emptyList()

    /** Seconds; positive shows subtitles later. Null when there are no subtitles. */
    private var offset: Float? = null
    private var lastSubtitle = ""
    private var muted = false

    val isOpen: Boolean get() = player != null

    private val tick = object : Runnable {
        override fun run() {
            drawSubtitle()
            ui.postDelayed(this, 100)
        }
    }

    /** Opens [stream]. Returns false (and opens nothing) if it isn't a web URL. */
    fun open(stream: MediaRef, subtitles: List<MediaRef>, pageUrl: String, userAgent: String): Boolean {
        if (!isWebUrl(stream.url) || stream.kind == MediaKind.SUBTITLE) return false
        close(null)

        val headers = headersFor(stream.url, stream.referer, pageUrl)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply {
                when (stream.kind) {
                    MediaKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    MediaKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> Unit
                }
            }
            .build()
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = listener.onPlayerChanged()

            override fun onPlayerError(error: PlaybackException) {
                val drm = error.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
                close(
                    if (drm) "This video is protected, so it only plays in the page"
                    else "The TV player couldn't play this video. Back to the page.",
                )
            }
        })
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        player = p

        val v = PlayerView(context).apply {
            player = p
            useController = true
            controllerShowTimeoutMs = 3_000
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        view = v
        val sub = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            val pad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            visibility = View.GONE
        }
        subtitleView = sub
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(
            sub,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (48 * context.resources.displayMetrics.density).toInt()
            },
        )
        container.visibility = View.VISIBLE
        v.requestFocus()

        cues = emptyList()
        offset = null
        lastSubtitle = ""
        muted = false
        pickSubtitle(subtitles)?.let { loadSubtitles(it, pageUrl, userAgent, p) }
        ui.post(tick)
        listener.onPlayerChanged()
        return true
    }

    /** Stops and removes the player. [message] is shown to the viewer, if any. */
    fun close(message: String?) {
        val p = player ?: return
        ui.removeCallbacks(tick)
        player = null
        p.release()
        view?.player = null
        container.removeAllViews()
        container.visibility = View.GONE
        view = null
        subtitleView = null
        cues = emptyList()
        offset = null
        listener.onPlayerClosed(message)
    }

    fun pause() {
        player?.pause()
    }

    /** What the phone's video controls show. */
    fun state(): VideoState? {
        val p = player ?: return null
        val dur = p.duration
        return VideoState(
            playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
            position = p.currentPosition / 1000f,
            duration = if (dur == C.TIME_UNSET || p.isCurrentMediaItemLive) -1f else dur / 1000f,
            muted = muted,
            rate = p.playbackParameters.speed,
            fullscreen = true,
            subtitleOffset = offset,
            native = true,
        )
    }

    fun command(action: VideoAction, value: Float) {
        val p = player ?: return
        when (action) {
            VideoAction.TOGGLE -> if (p.isPlaying) p.pause() else p.play()
            VideoAction.SEEK -> p.seekTo((value * 1000).toLong().coerceAtLeast(0))
            VideoAction.SKIP -> p.seekTo((p.currentPosition + (value * 1000).toLong()).coerceAtLeast(0))
            VideoAction.MUTE -> {
                muted = !muted
                p.volume = if (muted) 0f else 1f
            }
            VideoAction.RATE -> p.setPlaybackSpeed(value.coerceIn(0.25f, 4f))
            VideoAction.EXIT_FULLSCREEN -> close(null)
            VideoAction.SUBTITLE_SHIFT -> {
                val o = offset ?: return
                offset = (o + value).coerceIn(-MAX_SUBTITLE_SHIFT, MAX_SUBTITLE_SHIFT)
                drawSubtitle()
            }
        }
        listener.onPlayerChanged()
    }

    /** Remote keys while the player is up. Back is handled by the activity. */
    fun dispatchKey(event: KeyEvent): Boolean {
        val v = view ?: return false
        // Without the controls showing, left and right skip 10 s, like most TV players.
        if (event.action == KeyEvent.ACTION_DOWN && !v.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { command(VideoAction.SKIP, -10f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { command(VideoAction.SKIP, 10f); return true }
            }
        }
        return v.dispatchKeyEvent(event)
    }

    // ---- subtitles ----

    /** English if a URL says so, otherwise the first one the page asked for. */
    private fun pickSubtitle(list: List<MediaRef>): MediaRef? {
        if (list.isEmpty()) return null
        val en = Regex("""(^|[^a-z])(en|eng|english)([^a-z]|$)""")
        return list.firstOrNull { en.containsMatchIn(it.url.lowercase().substringAfterLast('/')) } ?: list.first()
    }

    private fun loadSubtitles(ref: MediaRef, pageUrl: String, userAgent: String, forPlayer: ExoPlayer) {
        val headers = headersFor(ref.url, ref.referer, pageUrl)
        Thread {
            val text = fetchText(ref.url, headers, userAgent)
            val parsed = text?.let { runCatching { parseSubtitles(it) }.getOrNull() }.orEmpty()
            ui.post {
                if (player !== forPlayer || parsed.isEmpty()) return@post
                cues = parsed
                offset = 0f
                listener.onPlayerChanged()
            }
        }.start()
    }

    private fun drawSubtitle() {
        val p = player ?: return
        val sub = subtitleView ?: return
        val o = offset ?: return
        val text = cueTextAt(cues, p.currentPosition / 1000f - o)
        if (text == lastSubtitle) return
        lastSubtitle = text
        sub.text = text
        sub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fetchText(url: String, headers: Map<String, String>, userAgent: String): String? {
        if (!isWebUrl(url)) return null
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_SUBTITLE_BYTES) return null
                }
            }
            out.toString("UTF-8")
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024

        /**
         * The Referer the page itself sent for this request, and the browser's cookies for the stream's
         * own host, only when that host is on the page's site (third-party cookies are off in this browser).
         */
        fun headersFor(url: String, referer: String?, pageUrl: String): Map<String, String> {
            val out = HashMap<String, String>()
            if (referer != null) out["Referer"] = referer
            val streamHost = runCatching { URI(url).host }.getOrNull()
            val pageHost = runCatching { URI(pageUrl).host }.getOrNull()
            if (streamHost != null && pageHost != null && siteOf(streamHost) == siteOf(pageHost)) {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
            }
            return out
        }
    }
}
