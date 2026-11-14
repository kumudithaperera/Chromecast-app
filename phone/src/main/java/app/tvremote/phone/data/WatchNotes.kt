package app.tvremote.phone.data

import android.content.Context
import app.tvremote.protocol.MAX_EPISODE
import app.tvremote.protocol.MAX_NOTE_TEXT
import app.tvremote.protocol.MAX_SHOW
import app.tvremote.protocol.cleanLine
import app.tvremote.protocol.normalizeEpisode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One line in the watch notes. [position] is seconds into the video, or -1.
 * [url] is empty when the note was typed by hand instead of taken from the TV browser.
 */
data class WatchNote(
    val id: Long,
    val show: String,
    val episode: String,
    val text: String,
    val url: String,
    val position: Int,
    val at: Long,
)

/** The notes of one show, newest first. */
data class ShowNotes(val show: String, val notes: List<WatchNote>) {
    val latest: WatchNote get() = notes.first()

    /** The episode of the most recent note that has one. */
    val lastEpisode: String get() = notes.firstOrNull { it.episode.isNotEmpty() }?.episode.orEmpty()

    /** The page of the most recent note that has one, to carry on watching from. */
    val resumeUrl: String get() = notes.firstOrNull { it.url.isNotEmpty() }?.url.orEmpty()
}

/** Groups a newest-first list by show name, ignoring case. Shows are ordered by their latest note. */
fun groupByShow(notes: List<WatchNote>): List<ShowNotes> {
    val groups = LinkedHashMap<String, MutableList<WatchNote>>()
    notes.forEach { groups.getOrPut(it.show.trim().lowercase()) { mutableListOf() }.add(it) }
    return groups.values.map { ShowNotes(it.first().show, it) }
}

/** What has been watched and what was thought of it, newest first, kept in app-private storage on the phone. */
class WatchNotes(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sp = context.getSharedPreferences("watch_notes", Context.MODE_PRIVATE)
    private val _notes = MutableStateFlow(read())

    val notes: StateFlow<List<WatchNote>> = _notes

    /** Adds a note. Returns null when there is no show name to file it under. */
    fun add(show: String, episode: String, text: String, url: String, position: Int): WatchNote? {
        val name = cleanLine(show, MAX_SHOW)
        if (name.isEmpty()) return null
        val list = _notes.value
        val id = maxOf(clock(), (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val note = WatchNote(id, name, cleanEpisode(episode), cleanLine(text, MAX_NOTE_TEXT), url, position.coerceAtLeast(-1), clock())
        commit((listOf(note) + list).take(MAX))
        return note
    }

    /** Changes what was typed. When it was logged, its page and its place in the list stay. */
    fun update(id: Long, show: String, episode: String, text: String, position: Int) {
        val name = cleanLine(show, MAX_SHOW)
        if (name.isEmpty()) return
        commit(
            _notes.value.map {
                if (it.id != id) it else it.copy(show = name, episode = cleanEpisode(episode), text = cleanLine(text, MAX_NOTE_TEXT), position = position.coerceAtLeast(-1))
            },
        )
    }

    fun remove(id: Long) {
        val list = _notes.value
        if (list.any { it.id == id }) commit(list.filterNot { it.id == id })
    }

    private fun cleanEpisode(raw: String) = normalizeEpisode(cleanLine(raw, MAX_EPISODE))

    private fun commit(list: List<WatchNote>) {
        _notes.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("show", it.show).put("ep", it.episode).put("text", it.text)
                    .put("url", it.url).put("pos", it.position).put("at", it.at),
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun read(): List<WatchNote> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]"))
        List(arr.length()) { i ->
            arr.getJSONObject(i).let {
                WatchNote(
                    id = it.getLong("id"),
                    show = it.getString("show"),
                    episode = it.optString("ep"),
                    text = it.optString("text"),
                    url = it.optString("url"),
                    position = it.optInt("pos", -1),
                    at = it.optLong("at"),
                )
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "notes"
        const val MAX = 300
    }
}
