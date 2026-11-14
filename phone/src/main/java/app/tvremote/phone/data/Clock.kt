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
