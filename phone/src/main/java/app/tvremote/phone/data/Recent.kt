package app.tvremote.phone.data

/**
 * [value] trimmed and put first in [list], replacing an earlier copy that differs only in case,
 * keeping at most [max]. Blank values leave the list as it was.
 */
fun pushRecent(list: List<String>, value: String, max: Int): List<String> {
    val t = value.trim()
    if (t.isEmpty()) return list
    return (listOf(t) + list.filterNot { it.equals(t, ignoreCase = true) }).take(max)
}
