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

    /**
     * Switch between the basic and the standard ad blocking level. From phone apps older than [Command.AdLevelSet];
     * newer ones set the level directly.
     */
    AD_LISTS,
}

/**
 * How much the TV browser blocks. BASIC: a small built-in list of ad hosts. STANDARD: EasyList, EasyPrivacy
 * and uBlock Origin's lists. STRICT: also HaGeZi's larger host list, which can stop some video players.
 */
enum class AdLevel { OFF, BASIC, STANDARD, STRICT }

/** Ad blocking parts the phone can turn on and off. */
enum class AdOption {
    /** Hide the empty boxes and banners left on the page. */
    HIDE_ELEMENTS,

    /** Small scripts that stop pages from detecting the blocker or opening ad windows. */
    SCRIPTLETS,

    /** Press reject on cookie banners and "skip intro" on players. */
    SITE_HELPERS,
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

    data class AdLevelSet(val level: AdLevel) : Command
    data class AdOptionSet(val option: AdOption, val on: Boolean) : Command

    /** Allow ads on [site] (as `siteOf` gives it), or block them there again. */
    data class AdSite(val site: String, val allowed: Boolean) : Command

    /** Download the ad lists now instead of waiting for the weekly update. */
    data object AdUpdate : Command

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

/** TV → phone: the ad blocking settings and what they are doing. Sent on connect and after a change. */
data class AdBlockState(
    val level: AdLevel = AdLevel.STANDARD,
    val hideElements: Boolean = true,
    val scriptlets: Boolean = true,
    val siteHelpers: Boolean = true,
    /** Rules in use. */
    val rules: Int = 0,
    /** When the lists were last downloaded (milliseconds since 1970), 0 for never. */
    val updatedAt: Long = 0L,
    val updating: Boolean = false,
    /** The last download failed; the lists in use are older ones or the built-in list. */
    val updateFailed: Boolean = false,
    /** Requests blocked on the page on screen. */
    val blockedOnPage: Int = 0,
    /** The site on screen (as `siteOf` gives it), "" for the start page. */
    val site: String = "",
    val siteAllowed: Boolean = false,
    /** Sites where ads are allowed, at most as many as fit on one line. */
    val allowedSites: List<String> = emptyList(),
)

private val SITE_NAME = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

/** A site name as `siteOf` makes it: lowercase labels, or an IPv4 address. */
fun isSiteName(s: String): Boolean = s.length <= 253 && SITE_NAME.matches(s)

/** Room left on an ADBLOCK line for the allowed sites. */
private const val MAX_SITES_FIELD = 3000

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
    is Command.AdLevelSet -> "AL ${c.level.name}"
    is Command.AdOptionSet -> "AO ${c.option.name} ${if (c.on) 1 else 0}"
    is Command.AdSite -> "AS ${if (c.allowed) 1 else 0} ${enc(c.site)}"
    Command.AdUpdate -> "AU"
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
        "AL" -> Command.AdLevelSet(enumOrNull<AdLevel>(arg) ?: return null)
        "AO" -> {
            if (parts.size != 3) return null
            Command.AdOptionSet(enumOrNull<AdOption>(arg) ?: return null, flagOf(parts[2]) ?: return null)
        }
        "AS" -> {
            if (parts.size != 3) return null
            val site = dec(parts[2]) ?: return null
            if (!isSiteName(site)) return null
            Command.AdSite(site, flagOf(arg ?: return null) ?: return null)
        }
        "AU" -> Command.AdUpdate
        else -> null
    }
}

private fun flagOf(s: String): Boolean? = when (s) {
    "1" -> true
    "0" -> false
    else -> null
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

fun formatAdBlock(a: AdBlockState): String {
    // As many allowed sites as fit; the rest stay on the TV.
    val sites = StringBuilder()
    for (site in a.allowedSites) {
        if (!isSiteName(site)) continue
        if (sites.length + site.length + 1 > MAX_SITES_FIELD) break
        if (sites.isNotEmpty()) sites.append(',')
        sites.append(site)
    }
    val status = when {
        a.updating -> 1
        a.updateFailed -> 2
        else -> 0
    }
    return "ADBLOCK ${a.level.name} ${if (a.hideElements) 1 else 0} ${if (a.scriptlets) 1 else 0} ${if (a.siteHelpers) 1 else 0} " +
        "${a.rules.coerceAtLeast(0)} ${a.updatedAt.coerceAtLeast(0)} $status ${a.blockedOnPage.coerceAtLeast(0)} " +
        "${field(a.site.takeIf(::isSiteName).orEmpty())} ${if (a.siteAllowed) 1 else 0} ${field(sites.toString())}"
}

/** [line] must start with "ADBLOCK ". Returns null for anything malformed. */
fun parseAdBlock(line: String): AdBlockState? {
    val p = line.trim().split(' ')
    if (p.size != 12 || p[0] != "ADBLOCK") return null
    val site = unfield(p[9]) ?: return null
    val sites = unfield(p[11]) ?: return null
    return AdBlockState(
        level = enumOrNull<AdLevel>(p[1]) ?: return null,
        hideElements = flagOf(p[2]) ?: return null,
        scriptlets = flagOf(p[3]) ?: return null,
        siteHelpers = flagOf(p[4]) ?: return null,
        rules = p[5].toIntOrNull()?.coerceIn(0, 10_000_000) ?: return null,
        updatedAt = p[6].toLongOrNull()?.coerceAtLeast(0L) ?: return null,
        updating = p[7] == "1",
        updateFailed = p[7] == "2",
        blockedOnPage = p[8].toIntOrNull()?.coerceIn(0, 1_000_000) ?: return null,
        site = site.takeIf { it.isEmpty() || isSiteName(it) } ?: return null,
        siteAllowed = flagOf(p[10]) ?: return null,
        allowedSites = if (sites.isEmpty()) emptyList() else sites.split(',').filter(::isSiteName),
    )
}
