package app.tvremote.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R
import app.tvremote.phone.data.formatClock
import app.tvremote.protocol.BrowserState
import app.tvremote.protocol.Command
import app.tvremote.protocol.MAX_EPISODE
import app.tvremote.protocol.MAX_NOTE_TEXT
import app.tvremote.protocol.MAX_SHOW
import app.tvremote.protocol.VideoState
import app.tvremote.protocol.guessEpisode
import app.tvremote.protocol.normalizeEpisode

/** What the sheet hands back. [position] is seconds into the video, or -1 for none. */
data class NoteDraft(val show: String, val episode: String, val text: String, val position: Int)

/**
 * "Quick note": log what is on in the TV browser tab. The show and episode are guessed from the page
 * title and can be edited; the video time is the moment the sheet was opened, not when Save is pressed,
 * because the video keeps playing while the note is typed. Saving with no text just marks it watched.
 */
@Composable
fun QuickNoteSheet(
    page: BrowserState,
    video: VideoState?,
    onSave: (NoteDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = remember(page.url) { hostOf(page.url) }
    // Fixed when the sheet opens.
    val position = remember { video?.takeIf { it.position >= 0f && it.duration > 0f }?.position?.toInt() }
    val guess = remember { guessEpisode(page.title.ifBlank { host }) }
    NoteSheet(
        title = "Quick note",
        subtitle = "Saved to Notes on this phone" + if (host.isNotEmpty()) " with a link to $host." else ".",
        initial = NoteDraft(guess.show.ifBlank { host }, guess.episode, "", position ?: -1),
        videoTime = position,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")

/**
 * The form behind both a new note and an edit. [videoTime] is the video time the note can carry, shown as
 * a switch; null when there is none. [onDelete] is given only for a note that already exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    title: String,
    subtitle: String?,
    initial: NoteDraft,
    videoTime: Int?,
    onSave: (NoteDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var show by rememberSaveable { mutableStateOf(initial.show) }
    var episode by rememberSaveable { mutableStateOf(initial.episode) }
    var text by rememberSaveable { mutableStateOf(initial.text) }
    var withTime by rememberSaveable { mutableStateOf(initial.position >= 0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    fun save() {
        if (show.isBlank()) return
        onSave(NoteDraft(show.trim(), normalizeEpisode(episode), text.trim(), if (withTime && videoTime != null) videoTime else -1))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tok.Panel,
        scrimColor = Tok.Ground.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Tok.Bone)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
            }
            Spacer(Modifier.height(20.dp))

            Text("Show or film", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
            Spacer(Modifier.height(8.dp))
            NoteField(show, { show = it.take(MAX_SHOW) }, "Name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Episode", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
                    Spacer(Modifier.height(8.dp))
                    NoteField(episode, { episode = it.take(MAX_EPISODE) }, "S2 E4", Modifier.fillMaxWidth(), mono = true)
                }
                if (videoTime != null) {
                    TimeChip(formatClock(videoTime.toFloat()), withTime) { withTime = !withTime }
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Note", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
            Spacer(Modifier.height(8.dp))
            NoteField(
                text, { text = it.take(MAX_NOTE_TEXT) }, "Optional. Leave empty to just mark it watched.", Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done, onDone = ::save,
            )
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextAction("Cancel", onDismiss, Modifier.weight(1f))
                PillButton("Save note", ::save, Modifier.weight(1.8f), primary = true, enabled = show.isNotBlank(), icon = R.drawable.ic_sticky_note)
            }
            if (onDelete != null) {
                Spacer(Modifier.height(4.dp))
                TextAction(
                    if (confirmDelete) "Tap again to delete this note" else "Delete note",
                    { if (confirmDelete) onDelete() else confirmDelete = true },
                    Modifier.fillMaxWidth(),
                    color = Tok.Fault,
                )
            }
        }
    }
}

/** "At 23:10": whether to stamp the note with the video time. */
@Composable
private fun TimeChip(time: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (on) Tok.Key else Tok.Panel)
            .fieldBox(false, radius = 26.dp)
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics { stateDescription = if (on) "On" else "Off"; contentDescription = "Add video time $time to the note" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(if (on) R.drawable.ic_check else R.drawable.ic_clock, tint = if (on) Tok.Signal else Tok.Dim, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text("At $time", fontFamily = Mono, fontSize = 14.sp, color = if (on) Tok.Bone else Tok.Dim, maxLines = 1)
    }
}

@Composable
private fun NoteField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = if (mono) Mono else Grotesk, fontSize = 15.sp, color = Tok.Bone),
        cursorBrush = SolidColor(Tok.Signal),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(52.dp).fieldBox(focused).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, fontFamily = if (mono) Mono else Grotesk, fontSize = 15.sp, color = Tok.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
