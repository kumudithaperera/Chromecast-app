package app.tvremote.phone.data

/** 754.5 -> "12:34", 3725 -> "1:02:05". Unknown or negative -> "--:--". */
fun formatClock(seconds: Float): String {
    if (seconds.isNaN() || seconds < 0f || seconds.isInfinite()) return "--:--"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * When the video will end, in wall-clock milliseconds, playing at [rate] from [position] seconds of
 * [duration]. Null when that can't be known: no position, no duration (live), or a rate of zero.
 */
fun endsAtMillis(nowMillis: Long, position: Float, duration: Float, rate: Float): Long? {
    if (position < 0f || duration <= 0f || rate <= 0f || position > duration) return null
    return nowMillis + ((duration - position) / rate * 1000f).toLong()
}

/** How long ago [thenMillis] was, in words: "just now", "5 minutes ago", "2 days ago". 0 or less → "never". */
fun formatAgo(thenMillis: Long, nowMillis: Long): String {
    if (thenMillis <= 0L) return "never"
    val minutes = (nowMillis - thenMillis).coerceAtLeast(0L) / 60_000L
    fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"} ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> n(minutes, "minute")
        minutes < 24 * 60 -> n(minutes / 60, "hour")
        else -> n(minutes / (24 * 60), "day")
    }
}
