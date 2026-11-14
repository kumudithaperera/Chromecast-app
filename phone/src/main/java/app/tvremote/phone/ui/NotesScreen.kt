package app.tvremote.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R
import app.tvremote.phone.data.WatchNote
import app.tvremote.phone.data.WatchNotes
import app.tvremote.phone.data.groupByShow
import app.tvremote.protocol.clockText
import app.tvremote.protocol.nextEpisode
import app.tvremote.protocol.timeAgo

/** What the note sheet is open for: nothing, a new note (with the show filled in, maybe), or an existing one. */
private const val NONE = Long.MIN_VALUE
private const val NEW = 0L

/**
 * The Notes tab: what was written down with Quick note, as plain text under the name of each show.
 * Tap a note to change or delete it, tap a show's name to add its next episode.
 */
@Composable
fun NotesScreen(notes: WatchNotes) {
    val all by notes.notes.collectAsState()
    val groups = remember(all) { groupByShow(all) }
    var editing by rememberSaveable { mutableStateOf(NONE) }
    var newShow by rememberSaveable { mutableStateOf("") }
    var newEpisode by rememberSaveable { mutableStateOf("") }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().background(Tok.Ground)) {
        Header(
            "Notes",
            if (all.isEmpty()) "Nothing written yet" else "${all.size} ${if (all.size == 1) "note" else "notes"} on ${groups.size} ${if (groups.size == 1) "show" else "shows"}",
            null,
        ) {
            HeaderButton(R.drawable.ic_plus, "Add a note") { newShow = ""; newEpisode = ""; editing = NEW }
        }

        if (all.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).padding(top = 24.dp)) {
                Text(
                    "While a video is playing full screen in the Browser tab, open its controls and press Quick note. " +
                        "Or add one yourself with the plus above.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tok.Dim,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(groups, key = { it.show.lowercase() }) { group ->
                    Column {
                        // Tapping the name starts the next note for the show, with the episode after the last one filled in.
                        val next = nextEpisode(group.lastEpisode)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Add a note for ${group.show}") {
                                    newShow = group.show; newEpisode = next; editing = NEW
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.show, style = MaterialTheme.typography.titleMedium, color = Tok.Bone, modifier = Modifier.weight(1f))
                            Text(if (next.isEmpty()) "+ note" else "+ $next", fontFamily = Mono, fontSize = 13.sp, color = Tok.Signal)
                        }
                        group.notes.forEach { note ->
                            NoteLine(note, now, onClick = { editing = note.id })
                        }
                    }
                }
            }
        }
    }

    if (editing != NONE) {
        val close = { editing = NONE }
        val existing = all.firstOrNull { it.id == editing }
        if (editing == NEW) {
            NoteSheet(
                title = "Add a note",
                subtitle = null,
                initial = NoteDraft(newShow, newEpisode, "", -1),
                videoTime = null,
                onSave = { d ->
                    notes.add(d.show, d.episode, d.text, url = "", position = -1)
                    close()
                },
                onDismiss = close,
            )
        } else if (existing != null) {
            NoteSheet(
                title = "Edit note",
                subtitle = "Written ${timeAgo(now, existing.at)}",
                initial = NoteDraft(existing.show, existing.episode, existing.text, existing.position),
                videoTime = existing.position.takeIf { it >= 0 },
                onSave = { d ->
                    notes.update(existing.id, d.show, d.episode, d.text, d.position)
                    close()
                },
                onDismiss = close,
                onDelete = {
                    notes.remove(existing.id)
                    close()
                },
            )
        } else {
            close()
        }
    }
}

/** "S2 E4 · 12:34" in small type, then the text. A note with no text reads "watched". */
@Composable
private fun NoteLine(note: WatchNote, now: Long, onClick: () -> Unit) {
    val where = listOfNotNull(
        note.episode.takeIf { it.isNotEmpty() },
        note.position.takeIf { it >= 0 }?.let { clockText(it) },
    ).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit this note", onClick = onClick).padding(vertical = 8.dp),
    ) {
        Text(
            listOf(where, timeAgo(now, note.at)).filter { it.isNotEmpty() }.joinToString(" · "),
            fontFamily = Mono, fontSize = 12.sp, color = Tok.Dim,
        )
        Spacer(Modifier.height(2.dp))
        Text(note.text.ifEmpty { "Watched" }, style = MaterialTheme.typography.bodyLarge, color = if (note.text.isEmpty()) Tok.Dim else Tok.Bone)
    }
}
