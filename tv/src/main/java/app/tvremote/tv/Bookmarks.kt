package app.tvremote.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saved pages, newest first, kept in app-private storage. */
class Bookmarks(context: Context) {
    private val sp = context.getSharedPreferences("tv_browser", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, String>> = runCatching {
        val arr = JSONArray(sp.getString("bookmarks", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { it.optString("title") to it.getString("url") } }
    }.getOrDefault(emptyList())

    fun contains(url: String) = all().any { it.second == url }

    /** Adds the page, or removes it if it was already saved. Returns true when it is now saved. */
    fun toggle(title: String, url: String): Boolean {
        val list = all().toMutableList()
        val existing = list.indexOfFirst { it.second == url }
        val saved = if (existing >= 0) {
            list.removeAt(existing)
            false
        } else {
            list.add(0, title to url)
            true
        }
        val arr = JSONArray()
        list.take(MAX).forEach { (t, u) -> arr.put(JSONObject().put("title", t).put("url", u)) }
        sp.edit().putString("bookmarks", arr.toString()).apply()
        return saved
    }

    private companion object {
        const val MAX = 24
    }
}
