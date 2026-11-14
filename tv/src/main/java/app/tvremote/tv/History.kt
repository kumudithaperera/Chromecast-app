package app.tvremote.tv

import android.content.Context
import app.tvremote.protocol.isWebUrl
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val at: Long)

/**
 * Pages visited, newest first, one entry per address, kept in app-private storage.
 * Called on the main thread only (WebViewClient callbacks and the start page actions).
 */
class History(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sp = context.getSharedPreferences("tv_browser", Context.MODE_PRIVATE)
    private var cache: MutableList<HistoryEntry>? = null

    fun all(): List<HistoryEntry> = load()

    /** Notes a visit. Visiting an address again moves it to the top and keeps its title if the new one is blank. */
    fun record(url: String, title: String) {
        val key = keyOf(url)
        if (!isWebUrl(key)) return
        val list = load()
        val old = list.indexOfFirst { it.url == key }
        val keptTitle = if (old >= 0) list.removeAt(old).title else ""
        list.add(0, HistoryEntry(key, title.trim().take(MAX_TITLE).ifBlank { keptTitle }, clock()))
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save()
    }

    /** Fills in the title of an address already in the list. Does not reorder or add anything. */
    fun setTitle(url: String, title: String) {
        val key = keyOf(url)
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isEmpty()) return
        val list = load()
        val i = list.indexOfFirst { it.url == key }
        if (i < 0 || list[i].title == clean) return
        list[i] = list[i].copy(title = clean)
        save()
    }

    fun remove(url: String) {
        val list = load()
        if (list.removeAll { it.url == keyOf(url) }) save()
    }

    fun clear() {
        load().clear()
        save()
    }

    private fun load(): MutableList<HistoryEntry> {
        cache?.let { return it }
        val list = runCatching {
            val arr = JSONArray(sp.getString(KEY, "[]"))
            MutableList(arr.length()) { i ->
                arr.getJSONObject(i).let { HistoryEntry(it.getString("u"), it.optString("t"), it.optLong("at")) }
            }
        }.getOrDefault(mutableListOf())
        cache = list
        return list
    }

    private fun save() {
        val arr = JSONArray()
        load().forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("at", it.at)) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "history"
        const val MAX = 200
        const val MAX_TITLE = 160

        /** Two visits to the same page that differ only in the #fragment are one entry. */
        fun keyOf(url: String) = url.substringBefore('#')
    }
}
