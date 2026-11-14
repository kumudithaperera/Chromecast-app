package app.tvremote.protocol

/** One subtitle line: shown from [start] to [end] seconds. [text] is plain text, tags removed. */
data class Cue(val start: Float, val end: Float, val text: String)

/** Most cues a file may have; a film has a couple of thousand. */
const val MAX_CUES = 20_000

private val TIMING = Regex(
    """^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""",
)
private val TAG = Regex("<[^>]*>")
private val ASS_TAG = Regex("""\{\\[^}]*\}""")

/**
 * Reads SRT and WebVTT. Both are "timing line, then text lines, then a blank line"; the differences
 * (header, cue ids, settings after the timing, comma or dot before milliseconds) don't matter here.
 * Markup is stripped, so nothing in the file is ever interpreted.
 */
fun parseSubtitles(text: String): List<Cue> {
    val cues = ArrayList<Cue>()
    val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    while (i < lines.size && cues.size < MAX_CUES) {
        val m = TIMING.find(lines[i])
        if (m == null) {
            i++
            continue
        }
        val start = seconds(m.groupValues[1])
        val end = seconds(m.groupValues[2])
        i++
        val body = StringBuilder()
        while (i < lines.size && lines[i].isNotBlank() && !TIMING.containsMatchIn(lines[i])) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[i].trim())
            i++
        }
        val clean = decodeEntities(body.toString().replace(TAG, "").replace(ASS_TAG, "")).trim()
        if (start != null && end != null && end > start && clean.isNotEmpty()) cues += Cue(start, end, clean.take(500))
    }
    cues.sortBy { it.start }
    return cues
}

/** The text to show at [time] seconds, joining overlapping cues, or "" for none. */
fun cueTextAt(cues: List<Cue>, time: Float): String {
    if (cues.isEmpty()) return ""
    // Last cue starting at or before [time].
    var lo = 0
    var hi = cues.size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].start <= time) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (idx < 0) return ""
    val out = ArrayList<String>(2)
    // Overlaps are rare and short; look back a few cues at most.
    var j = idx
    while (j >= 0 && idx - j < 8) {
        val c = cues[j]
        if (c.end > time) out += c.text
        j--
    }
    return out.asReversed().joinToString("\n")
}

private fun seconds(s: String): Float? {
    val parts = s.replace(',', '.').split(':')
    return try {
        when (parts.size) {
            2 -> parts[0].toInt() * 60f + parts[1].toFloat()
            3 -> parts[0].toInt() * 3600f + parts[1].toInt() * 60f + parts[2].toFloat()
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

private fun decodeEntities(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
