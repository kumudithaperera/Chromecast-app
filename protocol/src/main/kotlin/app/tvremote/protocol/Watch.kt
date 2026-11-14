package app.tvremote.protocol

import java.time.Duration

/** Longest show name, episode label and note text the watch notes accept. */
const val MAX_SHOW = 60
const val MAX_EPISODE = 12
const val MAX_NOTE_TEXT = 240

/** A show name and an episode label such as "S2 E4" taken from a page title. Either may be empty. */
data class EpisodeGuess(val show: String, val episode: String)

private class EpisodeHit(val range: IntRange, val season: Int?, val episode: Int) {
    val label: String get() = if (season != null) "S$season E$episode" else "E$episode"
}

// "S02E04", "S2 E4", "S02.E04", "S2 - E4", "Season 2 Episode 4", "Season 2, Ep. 4"
private val SEASON_EPISODE = Regex(
    """\bS(?:eason)?\s*0*(\d{1,2})\s*[,.:\-–—]?\s*E(?:p(?:isode)?)?\.?\s*0*(\d{1,4})\b""",
    RegexOption.IGNORE_CASE,
)
private val NUMBER_X_NUMBER = Regex("""\b(\d{1,2})x0*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
private val EPISODE_ONLY = Regex("""\bE(?:p(?:isode)?)?\.?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
private val SEASON_ONLY = Regex("""S(?:eason)?\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE)
private val BARE_NUMBER = Regex("""\d{1,4}""")

// Title parts are split on " | ", " - ", " – " and " · " (with spaces), so "Spider-Man" stays whole.
private val TITLE_SEPARATOR = Regex("""\s+[|–—·-]\s+""")
private val EDGE_JUNK = Regex("""^[\s\-–—:|,.·(\[]+|[\s\-–—:|,·(\[]+$""")
private val WATCH_PREFIX = Regex("""^watch(?:ing)?\s+""", RegexOption.IGNORE_CASE)

private fun findEpisode(text: String): EpisodeHit? {
    SEASON_EPISODE.find(text)?.let {
        return EpisodeHit(it.range, it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    NUMBER_X_NUMBER.find(text)?.let {
        return EpisodeHit(it.range, it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    EPISODE_ONLY.find(text)?.let {
        return EpisodeHit(it.range, null, it.groupValues[1].toInt())
    }
    return null
}

private fun cleanShow(raw: String): String {
    val first = raw.split(TITLE_SEPARATOR).map { it.replace(EDGE_JUNK, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
    return first.replace(WATCH_PREFIX, "").replace(EDGE_JUNK, "").take(MAX_SHOW).trim()
}

/**
 * Guesses what is being watched from a page title like
 * "Severance Season 2 Episode 4 - Watch Online | Site" or "S02E04 - Severance".
 * Only a starting point: the person can edit both fields before saving.
 */
fun guessEpisode(title: String): EpisodeGuess {
    val t = title.trim()
    if (t.isEmpty()) return EpisodeGuess("", "")
    val hit = findEpisode(t)
    if (hit == null) return EpisodeGuess(cleanShow(t), "")
    var show = cleanShow(t.substring(0, hit.range.first))
    // "S2 E4 - Severance": the name comes after the episode.
    if (show.isEmpty()) show = cleanShow(t.removeRange(hit.range))
    return EpisodeGuess(show, hit.label)
}

/**
 * Tidies an episode label typed by hand: "s02e04", "2x04" and "Season 2 Episode 4" become "S2 E4",
 * "ep 4" and "4" become "E4", "season 2" becomes "S2". Anything else is kept as typed (shortened).
 */
fun normalizeEpisode(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    findEpisode(t)?.let { if (t.removeRange(it.range).isBlank()) return it.label }
    SEASON_ONLY.matchEntire(t)?.let { return "S${it.groupValues[1].toInt()}" }
    if (BARE_NUMBER.matches(t)) return "E${t.toInt()}"
    return t.take(MAX_EPISODE)
}

private val LABEL = Regex("""^(?:S(\d{1,2}) )?E(\d{1,4})$""")

/** The label after [episode]: "S2 E4" -> "S2 E5", "E12" -> "E13". "" when [episode] isn't a label this app wrote. */
fun nextEpisode(episode: String): String {
    val m = LABEL.matchEntire(episode.trim()) ?: return ""
    val next = "E${m.groupValues[2].toInt() + 1}"
    val label = if (m.groupValues[1].isEmpty()) next else "S${m.groupValues[1]} $next"
    return if (label.length <= MAX_EPISODE) label else ""
}

/** One line of text with control characters turned into spaces, trimmed and cut to [max] characters. */
fun cleanLine(raw: String, max: Int): String =
    raw.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(max).trim()

/** 754 -> "12:34", 3725 -> "1:02:05". */
fun clockText(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A short "how long ago" for the start page: "just now", "5 min ago", "3 h ago", "yesterday", "2 weeks ago". */
fun timeAgo(nowMs: Long, thenMs: Long): String {
    val d = Duration.ofMillis((nowMs - thenMs).coerceAtLeast(0))
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        d.toHours() < 48 -> "yesterday"
        d.toDays() < 14 -> "${d.toDays()} days ago"
        d.toDays() < 60 -> "${d.toDays() / 7} weeks ago"
        else -> "${d.toDays() / 30} months ago"
    }
}
