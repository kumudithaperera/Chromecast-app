package app.tvremote.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** TCP port the TV browser listens on. */
const val PORT = 8765

/** Longest line either side accepts. Longer input closes the connection. */
const val MAX_LINE = 4096

enum class KeyName { BACKSPACE, ENTER }
enum class NavAction {
    BACK, FORWARD, RELOAD, STOP, HOME, ALLOW_BLOCKED, READER,

    /** Turn ad blocking off or back on for the site on screen. */
    ADS,

    /** Turn the built-in site helpers (cookie banners, skip intro) off or on. */
    HELPERS,

    /** Turn the downloaded public ad lists on or off (off by default: only the small built-in list blocks). */
    AD_LISTS,
}
enum class TabAction {
    NEW, NEXT, PREV, CLOSE,

    /** Two tabs side by side, or back to one. */
    SPLIT,
}

/** Page zoom for the site on screen, remembered per site. */
enum class ZoomAction { IN, OUT, RESET }

/** Things the phone can do to the video playing in the TV browser. */
enum class VideoAction {
    TOGGLE, SEEK, SKIP, MUTE, RATE, EXIT_FULLSCREEN,

    /**
     * Changes the video's own volume by a step (positive is louder), from 0 to 1. For TVs that ignore
     * the remote's volume keys (HDMI-CEC volume control off), so the phone can still turn the video down.
     */
    VOLUME,

    /** Shows the page's video full screen on the TV (the reverse of EXIT_FULLSCREEN). */
    FULLSCREEN,
}

/** Phone → TV. */
sealed interface Command {
    /** Cursor movement in dp-like units; the TV multiplies by its own density. */
    data class Move(val dx: Float, val dy: Float) : Command
    data object Click : Command
    /** Positive [dy] means the fingers moved down, so the page follows them. */
    data class Scroll(val dy: Float) : Command
    data class Text(val text: String) : Command
    data class Key(val key: KeyName) : Command
    data class Nav(val action: NavAction) : Command
    /** A URL or a search phrase typed on the phone. */
    data class Go(val input: String) : Command
    data class Tab(val action: TabAction) : Command
    data class Desktop(val on: Boolean) : Command
    data class Zoom(val action: ZoomAction) : Command

    /** [value] is seconds for SEEK (absolute) and SKIP (relative), the speed for RATE and the step for VOLUME. */
    data class Video(val action: VideoAction, val value: Float = 0f) : Command

    data object Ping : Command
}

/** TV → phone. */
data class BrowserState(
    val url: String = "",
    val title: String = "",
    val tabCount: Int = 1,
    val tabIndex: Int = 0,
    val desktop: Boolean = true,
    val loading: Boolean = false,
    /** Host of the last redirect or popup the TV blocked, or empty. */
    val blocked: String = "",
    /** Ad blocking is off for this site. */
    val adsAllowed: Boolean = false,
    /** Page zoom in percent. */
    val zoom: Int = 100,
    /** Two tabs are on screen side by side. */
    val split: Boolean = false,
    /** Site helpers (cookie banners, skip intro) are on. */
    val helpers: Boolean = true,
)

/**
 * The video in the TV browser, as the page reports it.
 * [position] and [duration] are seconds; -1 means unknown (a live stream has no duration, and a page
 * whose player can't be reached reports neither).
 */
data class VideoState(
    val playing: Boolean = false,
    val position: Float = -1f,
    val duration: Float = -1f,
    val muted: Boolean = false,
    val rate: Float = 1f,
    val fullscreen: Boolean = false,
) {
    val isLive: Boolean get() = duration < 0f && position >= 0f
}

/** What a VIDEO line carries: a video, or null when there is none any more. */
data class VideoUpdate(val state: VideoState?)

private const val MAX_MOVE = 2000f
private const val MAX_TEXT = 1000
private const val MAX_URL_FIELD = 1500
private const val MAX_TITLE_FIELD = 200

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String? = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: IllegalArgumentException) {
    null
}

private fun fmt(f: Float): String = String.format(Locale.ROOT, "%.1f", f)

/** Two decimals, for playback speed: one decimal turned 0.75 into 0.8 and 1.25 into 1.3. */
private fun fmt2(f: Float): String = String.format(Locale.ROOT, "%.2f", f)

private const val MAX_SECONDS = 1_000_000f

/** Page zoom limits in percent. */
const val MIN_ZOOM = 50
const val MAX_ZOOM = 200

private fun finiteFloat(s: String): Float? {
    val f = s.toFloatOrNull() ?: return null
    return if (f.isNaN() || f.isInfinite()) null else f.coerceIn(-MAX_SECONDS, MAX_SECONDS)
}

private fun moveFloat(s: String): Float? = finiteFloat(s)?.coerceIn(-MAX_MOVE, MAX_MOVE)

fun formatCommand(c: Command): String = when (c) {
    is Command.Move -> "M ${fmt(c.dx)} ${fmt(c.dy)}"
    Command.Click -> "C"
    is Command.Scroll -> "S ${fmt(c.dy)}"
    is Command.Text -> "T ${enc(c.text)}"
    is Command.Key -> "K ${c.key.name}"
    is Command.Nav -> "N ${c.action.name}"
    is Command.Go -> "G ${enc(c.input)}"
    is Command.Tab -> "B ${c.action.name}"
    is Command.Desktop -> "D ${if (c.on) 1 else 0}"
    is Command.Zoom -> "Z ${c.action.name}"
    is Command.Video -> when (c.action) {
        VideoAction.RATE, VideoAction.VOLUME -> "V ${c.action.name} ${fmt2(c.value)}"
        VideoAction.SEEK, VideoAction.SKIP -> "V ${c.action.name} ${fmt(c.value)}"
        else -> "V ${c.action.name}"
    }
    Command.Ping -> "P"
}

/** Returns null for anything malformed. The caller drops it and carries on. */
fun parseCommand(line: String): Command? {
    val parts = line.trim().split(' ')
    if (parts.isEmpty() || parts[0].isEmpty()) return null
    val arg = parts.getOrNull(1)
    return when (parts[0]) {
        "M" -> {
            if (parts.size != 3) return null
            val dx = moveFloat(parts[1]) ?: return null
            val dy = moveFloat(parts[2]) ?: return null
            Command.Move(dx, dy)
        }
        "C" -> Command.Click
        "S" -> Command.Scroll(moveFloat(arg ?: return null) ?: return null)
        "T" -> {
            val t = dec(arg ?: return null) ?: return null
            if (t.isEmpty() || t.length > MAX_TEXT) null else Command.Text(t)
        }
        "K" -> Command.Key(enumOrNull<KeyName>(arg) ?: return null)
        "N" -> Command.Nav(enumOrNull<NavAction>(arg) ?: return null)
        "G" -> {
            val g = dec(arg ?: return null) ?: return null
            if (g.isBlank() || g.length > MAX_URL_FIELD) null else Command.Go(g)
        }
        "B" -> Command.Tab(enumOrNull<TabAction>(arg) ?: return null)
        "Z" -> Command.Zoom(enumOrNull<ZoomAction>(arg) ?: return null)
        "D" -> when (arg) {
            "1" -> Command.Desktop(true)
            "0" -> Command.Desktop(false)
            else -> null
        }
        "V" -> {
            val action = enumOrNull<VideoAction>(arg) ?: return null
            when (action) {
                VideoAction.SEEK -> Command.Video(action, (parts.getOrNull(2)?.let(::finiteFloat) ?: return null).coerceIn(0f, MAX_SECONDS))
                VideoAction.SKIP -> Command.Video(action, (parts.getOrNull(2)?.let(::finiteFloat) ?: return null).coerceIn(-3600f, 3600f))
                VideoAction.RATE -> Command.Video(action, (parts.getOrNull(2)?.let(::finiteFloat) ?: return null).coerceIn(0.25f, 4f))
                VideoAction.VOLUME -> Command.Video(action, (parts.getOrNull(2)?.let(::finiteFloat) ?: return null).coerceIn(-1f, 1f))
                else -> Command.Video(action)
            }
        }
        "P" -> Command.Ping
        else -> null
    }
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

// An empty field would vanish when the line is split, so it travels as "~".
// URLEncoder turns a real "~" into %7E, so the two can't be confused.
private fun field(s: String): String = if (s.isEmpty()) "~" else enc(s)
private fun unfield(s: String): String? = if (s == "~") "" else dec(s)

fun formatState(s: BrowserState): String =
    "STATE ${field(s.url.take(MAX_URL_FIELD))} ${field(s.title.take(MAX_TITLE_FIELD))} " +
        "${s.tabCount} ${s.tabIndex} ${if (s.desktop) 1 else 0} ${if (s.loading) 1 else 0} ${field(s.blocked.take(253))} " +
        "${if (s.adsAllowed) 1 else 0} ${s.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)} ${if (s.split) 1 else 0} ${if (s.helpers) 1 else 0}"

/** [line] must start with "STATE ". The four newer fields are optional, so an older TV app still parses. */
fun parseState(line: String): BrowserState? {
    val p = line.trim().split(' ')
    if ((p.size != 8 && p.size != 12) || p[0] != "STATE") return null
    val extra = p.size == 12
    return BrowserState(
        url = unfield(p[1]) ?: return null,
        title = unfield(p[2]) ?: return null,
        tabCount = p[3].toIntOrNull()?.coerceIn(1, 99) ?: return null,
        tabIndex = p[4].toIntOrNull()?.coerceIn(0, 98) ?: return null,
        desktop = p[5] == "1",
        loading = p[6] == "1",
        blocked = unfield(p[7]) ?: return null,
        adsAllowed = extra && p[8] == "1",
        zoom = if (extra) p[9].toIntOrNull()?.coerceIn(MIN_ZOOM, MAX_ZOOM) ?: return null else 100,
        split = extra && p[10] == "1",
        helpers = !extra || p[11] == "1",
    )
}

fun formatVideo(v: VideoState?): String =
    if (v == null) "VIDEO -"
    else "VIDEO ${if (v.playing) 1 else 0} ${fmt(v.position)} ${fmt(v.duration)} ${if (v.muted) 1 else 0} ${fmt2(v.rate)} ${if (v.fullscreen) 1 else 0}"

/** [line] must start with "VIDEO ". Returns null for anything malformed. */
fun parseVideo(line: String): VideoUpdate? {
    val p = line.trim().split(' ')
    if (p.isEmpty() || p[0] != "VIDEO") return null
    if (p.size == 2 && p[1] == "-") return VideoUpdate(null)
    if (p.size != 7) return null
    fun flag(s: String): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }
    fun secs(s: String): Float? = finiteFloat(s)?.takeIf { it >= -1f }
    return VideoUpdate(
        VideoState(
            playing = flag(p[1]) ?: return null,
            position = secs(p[2]) ?: return null,
            duration = secs(p[3]) ?: return null,
            muted = flag(p[4]) ?: return null,
            rate = (finiteFloat(p[5]) ?: return null).coerceIn(0.25f, 4f),
            fullscreen = flag(p[6]) ?: return null,
        ),
    )
}
