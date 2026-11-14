package app.tvremote.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tvremote.phone.R
import app.tvremote.phone.atv.Keys
import app.tvremote.phone.data.formatClock
import app.tvremote.phone.data.BrowserLink
import app.tvremote.phone.data.LinkState
import app.tvremote.phone.data.PadAction
import app.tvremote.phone.data.Prefs
import app.tvremote.phone.data.STRIP_PAGE_STEP_DP
import app.tvremote.phone.data.stripScroll
import app.tvremote.phone.data.Pt
import app.tvremote.phone.data.TrackpadTracker
import app.tvremote.phone.data.Tv
import app.tvremote.phone.data.WatchNotes
import app.tvremote.protocol.BrowserState
import app.tvremote.protocol.Command
import app.tvremote.protocol.KeyName
import app.tvremote.protocol.NavAction
import app.tvremote.protocol.TabAction
import app.tvremote.protocol.VideoAction
import app.tvremote.protocol.VideoState
import app.tvremote.protocol.ZoomAction
import kotlinx.coroutines.delay

private val PIN_KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(" ", "0", " "),
)

/** "Browser trackpad": drives the pointer in the TV Browser app. */
@Composable
fun BrowserScreen(
    tv: Tv,
    link: BrowserLink,
    prefs: Prefs,
    notes: WatchNotes,
    onOpenBrowserOnTv: () -> Unit,
    onTvKey: (Int) -> Unit,
    tvKeysEnabled: Boolean,
    onBack: () -> Unit,
) {
    val state by link.state.collectAsState()
    val page by link.browser.collectAsState()
    val video by link.video.collectAsState()
    BackHandler(onBack = onBack)

    // Set once the person asks the TV to start the browser; keeps retrying until it answers or 20 s pass.
    var launched by remember { mutableStateOf(false) }
    // A link that was working and dropped (Wi-Fi blip, the TV app restarting) is retried a few times unasked.
    var retries by remember { mutableIntStateOf(0) }
    LaunchedEffect(state, launched) {
        val s = state
        if (s is LinkState.Ready) {
            launched = false
            retries = 0
        }
        if (s is LinkState.Failed && (launched || (s.dropped && retries < 3))) {
            if (!launched) retries++
            delay(if (launched) 2_500 else 1_200)
            link.connect(tv.host)
        }
    }
    LaunchedEffect(launched) {
        if (launched) {
            delay(20_000)
            launched = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(tv.host) {
        link.connect(tv.host)
        // Coming back to the app after the link died in the background: try again straight away.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && link.state.value is LinkState.Failed) link.connect(tv.host)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            link.close()
        }
    }

    Column(Modifier.fillMaxSize().background(Tok.Ground).statusBarsPadding()) {
        when (val s = state) {
            is LinkState.NeedPin -> CodeEntry(
                title = "Connect to the\nTV browser",
                subtitle = "Type the PIN shown at the top right of the TV browser.",
                rows = PIN_KEYS,
                actionLabel = "Connect",
                onSubmit = { link.submitPin(tv.host, it) },
                onCancel = onBack,
                error = if (s.wrong) "That PIN didn't match. Check the PIN on the TV and try again." else null,
                // Shorter keys: this keypad shares the screen with the tab bar.
                keyHeight = 54.dp,
            )
            is LinkState.Ready -> Controls(link, page, video, prefs, notes, tvName = tv.name, onTvKey = onTvKey, tvKeysEnabled = tvKeysEnabled)
            else -> {
                val (status, lamp) = when (s) {
                    LinkState.Connecting, LinkState.Idle -> "Connecting to ${tv.name}…" to Lamp.Busy
                    else -> "Not connected" to Lamp.Fault
                }
                Header("Browser on TV", status, lamp) { HeaderButton(R.drawable.ic_x, "Close", onBack) }
                Column(Modifier.padding(20.dp)) {
                    when (s) {
                        is LinkState.Locked -> FaultBanner("Too many wrong PINs. The TV is locked for about ${s.seconds} seconds.") {
                            PillButton("Try again", { link.connect(tv.host) }, primary = true, height = 48.dp)
                        }
                        is LinkState.Failed -> {
                            FaultBanner(s.message) {}
                            Spacer(Modifier.height(16.dp))
                            PillButton(
                                "Open the browser on the TV",
                                { launched = true; onOpenBrowserOnTv() },
                                Modifier.fillMaxWidth(),
                                primary = true,
                                enabled = tvKeysEnabled,
                                busy = launched,
                                icon = R.drawable.ic_tv,
                            )
                            if (!tvKeysEnabled) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "The remote isn't connected to the TV, so the phone can't start the browser there. Open TV Browser on the TV yourself, or connect on the Remote tab.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tok.Dim,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            PillButton("Try again", { link.connect(tv.host) }, Modifier.fillMaxWidth())
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Controls(
    link: BrowserLink,
    page: BrowserState,
    video: VideoState?,
    prefs: Prefs,
    notes: WatchNotes,
    tvName: String,
    onTvKey: (Int) -> Unit,
    tvKeysEnabled: Boolean,
) {
    // While a video is full screen on the TV a floating button opens its controls over this screen,
    // so the pointer and the player are one tap apart and nobody has to switch screens.
    val fullscreen = video?.fullscreen == true
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fullscreen) { if (!fullscreen) playerOpen = false }

    var speed by remember { mutableFloatStateOf(prefs.trackpadSpeed) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    // Quick note is only offered in the video panel, so that panel comes back when the note is done.
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Header("Browser on TV", if (fullscreen) "Playing full screen on $tvName" else "Cursor active on $tvName", Lamp.Ok) {
        // Open tabs at a glance; tapping goes to the next one.
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Next tab (${page.tabIndex + 1} of ${page.tabCount})") { link.send(Command.Tab(TabAction.NEXT)) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).border(1.5.dp, Tok.Bone, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${page.tabCount}", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone) }
        }
        Box {
            HeaderButton(R.drawable.ic_ellipsis_vertical, "More options") { menuOpen = true }
            PanelMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOfNotNull(
                    MenuItem("Forward") { link.send(Command.Nav(NavAction.FORWARD)) },
                    MenuItem("Start page") { link.send(Command.Nav(NavAction.HOME)) },
                    MenuItem("New tab") { link.send(Command.Tab(TabAction.NEW)) },
                    MenuItem("Next tab (${page.tabIndex + 1} of ${page.tabCount})") { link.send(Command.Tab(TabAction.NEXT)) },
                    MenuItem("Close this tab") { link.send(Command.Tab(TabAction.CLOSE)) },
                    MenuItem(if (page.split) "One page at a time" else "Split screen (two tabs)") { link.send(Command.Tab(TabAction.SPLIT)) },
                    MenuItem("Zoom in (now ${page.zoom}%)") { link.send(Command.Zoom(ZoomAction.IN)) },
                    MenuItem("Zoom out") { link.send(Command.Zoom(ZoomAction.OUT)) },
                    if (page.zoom != 100) MenuItem("Reset zoom to 100%") { link.send(Command.Zoom(ZoomAction.RESET)) } else null,
                    if (page.url.isNotEmpty()) {
                        MenuItem(if (page.adsAllowed) "Block ads on this site" else "Allow ads on this site") { link.send(Command.Nav(NavAction.ADS)) }
                    } else null,
                    MenuItem(if (page.helpers) "Turn off site helpers" else "Turn on site helpers") { link.send(Command.Nav(NavAction.HELPERS)) },
                    MenuItem("Stronger ad blocking on/off") { link.send(Command.Nav(NavAction.AD_LISTS)) },
                    MenuItem(if (page.desktop) "Use mobile sites" else "Use desktop sites") { link.send(Command.Desktop(!page.desktop)) },
                    MenuItem("Pointer speed") { speedOpen = true },
                ),
            )
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        AddressBar(page, onGo = { link.send(Command.Go(it)) })
        if (page.blocked.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tok.Panel)
                    .clickable(role = Role.Button) { link.send(Command.Nav(NavAction.ALLOW_BLOCKED)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_shield_check, tint = Tok.Ok, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Blocked ${page.blocked}", style = MaterialTheme.typography.bodyMedium, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Allow once", style = MaterialTheme.typography.titleSmall, color = Tok.Signal)
            }
        }
        if (page.media && !fullscreen) {
            Spacer(Modifier.height(10.dp))
            // A stream was found on the page: offer the TV's own player (better sync, subtitle timing).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tok.Panel)
                    .clickable(role = Role.Button, onClickLabel = "Play in the TV player") { link.send(Command.Nav(NavAction.PLAYER)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(R.drawable.ic_film, tint = Tok.Bone, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Video found on this page", style = MaterialTheme.typography.bodyMedium, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Play in TV player", style = MaterialTheme.typography.titleSmall, color = Tok.Signal)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Trackpad(link, speed = { speed }, modifier = Modifier.fillMaxSize())
                if (fullscreen) {
                    VideoButton(
                        playing = video?.playing == true,
                        onClick = { playerOpen = true },
                        modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                    )
                }
            }
            // Its own scroll area, so scrolling doesn't need two fingers on the trackpad.
            ScrollStrip(link, speed = { speed }, modifier = Modifier.width(56.dp).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        if (keyboard) {
            LiveKeyboard(link)
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconKey(R.drawable.ic_arrow_left, "Previous page", { link.send(Command.Nav(NavAction.BACK)) }, Modifier.weight(1f).height(60.dp))
            IconKey(
                if (page.loading) R.drawable.ic_x else R.drawable.ic_refresh_cw,
                if (page.loading) "Stop loading" else "Reload",
                { link.send(Command.Nav(if (page.loading) NavAction.STOP else NavAction.RELOAD)) },
                Modifier.weight(1f).height(60.dp),
            )
            IconKey(R.drawable.ic_book_open, "Reader mode", { link.send(Command.Nav(NavAction.READER)) }, Modifier.weight(1f).height(60.dp))
            IconKey(R.drawable.ic_keyboard, if (keyboard) "Hide keyboard" else "Keyboard", { keyboard = !keyboard }, Modifier.weight(1f).height(60.dp))
            // Click shares the key row, so the trackpad gets the height a separate row would take.
            PhysicalKey("Click", { link.send(Command.Click) }, Modifier.weight(1.9f).height(60.dp), primary = true) {
                Text("Click", fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Tok.Ground)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (playerOpen && video != null) {
        ModalBottomSheet(
            onDismissRequest = { playerOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Tok.Panel,
            scrimColor = Tok.Ground.copy(alpha = 0.6f),
        ) {
            PlayerPanel(video, page, link, onTvKey, tvKeysEnabled, onNote = { playerOpen = false; noteOpen = true })
        }
    }

    if (noteOpen) {
        val closeNote = {
            noteOpen = false
            if (fullscreen) playerOpen = true
        }
        QuickNoteSheet(
            page = page,
            video = video,
            onSave = { d ->
                notes.add(d.show, d.episode, d.text, page.url, d.position)
                android.widget.Toast.makeText(context, "Saved to Notes", android.widget.Toast.LENGTH_SHORT).show()
                closeNote()
            },
            onDismiss = closeNote,
        )
    }

    if (speedOpen) {
        AlertDialog(
            onDismissRequest = { speedOpen = false; prefs.trackpadSpeed = speed },
            containerColor = Tok.Panel,
            title = { Text("Pointer speed", style = MaterialTheme.typography.headlineSmall, color = Tok.Bone) },
            text = {
                Column {
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.5f..2f,
                        colors = SliderDefaults.colors(thumbColor = Tok.Signal, activeTrackColor = Tok.Signal, inactiveTrackColor = Tok.Key),
                    )
                    Row {
                        Text("Slower", style = MaterialTheme.typography.bodySmall, color = Tok.Dim, modifier = Modifier.weight(1f))
                        Text("Faster", style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
                    }
                }
            },
            confirmButton = { PillButton("Done", { speedOpen = false; prefs.trackpadSpeed = speed }, primary = true, height = 48.dp) },
        )
    }
}

/** The floating button over the trackpad that opens the video controls. Bone, so the yellow stays on Click. */
@Composable
private fun VideoButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Tok.Bone)
            .clickable(role = Role.Button, onClickLabel = "Open video controls", onClick = onClick)
            .semantics { contentDescription = if (playing) "Video controls. Playing." else "Video controls. Paused." }
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_clapperboard, tint = Tok.Ground, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("Video", style = MaterialTheme.typography.labelLarge, color = Tok.Ground)
    }
}

/** The standard playback speeds, always offered as a fixed list. */
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 0.75 -> "0.75×", 1 -> "1×". */
private fun speedLabel(rate: Float): String =
    java.lang.String.format(java.util.Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"

private fun speedMenuLabel(rate: Float): String = if (rate == 1f) "Normal (1×)" else speedLabel(rate)

/**
 * Remote control for the video that is full screen on the TV ("Browser: video player" in the design).
 * Nothing is drawn on the TV; every key here is sent to the player in the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPanel(
    video: VideoState,
    page: BrowserState,
    link: BrowserLink,
    onTvKey: (Int) -> Unit,
    tvKeysEnabled: Boolean,
    onNote: () -> Unit,
) {
    val known = video.position >= 0f
    var dragging by remember { mutableStateOf(false) }
    var dragTo by remember { mutableFloatStateOf(0f) }
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    // Hold the thumb where it was released until the TV reports the new position.
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(1_500)
            pendingSeek = null
        }
    }
    LaunchedEffect(video.position, pendingSeek) {
        pendingSeek?.let { if (kotlin.math.abs(video.position - it) < 2f) pendingSeek = null }
    }
    // Same for the speed: show what was chosen at once, so quick taps never act on a stale reading.
    var speedOpen by remember { mutableStateOf(false) }
    var pendingRate by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pendingRate) {
        if (pendingRate != null) {
            delay(1_500)
            pendingRate = null
        }
    }
    LaunchedEffect(video.rate, pendingRate) {
        pendingRate?.let { if (kotlin.math.abs(video.rate - it) < 0.01f) pendingRate = null }
    }
    val rate = pendingRate ?: video.rate
    val shown = when {
        dragging -> dragTo
        pendingSeek != null -> pendingSeek!!
        else -> video.position
    }
    val scrubColors = SliderDefaults.colors(
        thumbColor = Tok.Signal,
        activeTrackColor = Tok.Signal,
        inactiveTrackColor = Tok.Key,
        activeTickColor = Tok.Signal,
        inactiveTickColor = Tok.Key,
    )
    val host = remember(page.url) { runCatching { android.net.Uri.parse(page.url).host }.getOrNull().orEmpty().removePrefix("www.") }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (video.playing) Tok.Signal else Tok.Dim, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        video.native && video.playing -> "TV PLAYER · PLAYING"
                        video.native -> "TV PLAYER · PAUSED"
                        !known -> "FULL SCREEN VIDEO"
                        video.playing -> "NOW PLAYING"
                        else -> "PAUSED"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                    color = Tok.Dim,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                page.title.ifBlank { host.ifBlank { "Video" } },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 26.sp),
                color = Tok.Bone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(host, fontFamily = Mono, fontSize = 13.sp, color = Tok.Dim, maxLines = 1)
            }
            Spacer(Modifier.height(18.dp))
            when {
                known && video.duration > 0f -> {
                    Slider(
                        value = shown.coerceIn(0f, video.duration),
                        onValueChange = { dragging = true; dragTo = it },
                        onValueChangeFinished = {
                            dragging = false
                            pendingSeek = dragTo
                            link.send(Command.Video(VideoAction.SEEK, dragTo))
                        },
                        valueRange = 0f..video.duration,
                        colors = scrubColors,
                        // The design's round knob on a flat 6dp track, not Material's bar thumb and gap.
                        thumb = { Box(Modifier.size(22.dp).background(Tok.Signal, CircleShape)) },
                        track = { state ->
                            SliderDefaults.Track(
                                sliderState = state,
                                colors = scrubColors,
                                thumbTrackGapSize = 0.dp,
                                trackInsideCornerSize = 0.dp,
                                drawStopIndicator = null,
                                modifier = Modifier.height(6.dp),
                            )
                        },
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatClock(shown), fontFamily = Mono, fontSize = 13.sp, color = Tok.Bone)
                        Spacer(Modifier.weight(1f))
                        Text(formatClock(video.duration), fontFamily = Mono, fontSize = 13.sp, color = Tok.Dim)
                    }
                }
                known -> Text("Live · ${formatClock(video.position)}", fontFamily = Mono, fontSize = 13.sp, color = Tok.Bone)
                else -> Text(
                    "This player doesn't share its position, so only play and pause can be sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tok.Dim,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhysicalKey("Back 10 seconds", { link.send(Command.Video(VideoAction.SKIP, -10f)) }, Modifier.weight(1f).height(66.dp), enabled = known) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ico(R.drawable.ic_rewind, size = 24.dp)
                    Text("10", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                }
            }
            PhysicalKey(
                if (video.playing) "Pause" else "Play",
                { link.send(Command.Video(VideoAction.TOGGLE)) },
                Modifier.weight(1.5f).height(66.dp),
                primary = true,
            ) { Ico(if (video.playing) R.drawable.ic_pause else R.drawable.ic_play, tint = Tok.Ground, size = 28.dp) }
            PhysicalKey("Forward 10 seconds", { link.send(Command.Video(VideoAction.SKIP, 10f)) }, Modifier.weight(1f).height(66.dp), enabled = known) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("10", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                    Ico(R.drawable.ic_fast_forward, size = 24.dp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The TV's own volume through the remote link, not the video element's: one rocker, apart from speed.
            VolumeRocker(onTvKey, enabled = tvKeysEnabled, vertical = false, modifier = Modifier.weight(2f).height(56.dp))
            Box(Modifier.weight(1f)) {
                PhysicalKey(
                    "Playback speed ${speedLabel(rate)}",
                    { speedOpen = true },
                    Modifier.fillMaxWidth().height(56.dp),
                    enabled = known,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ico(R.drawable.ic_gauge, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(speedLabel(rate), fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                    }
                }
                PanelMenu(
                    expanded = speedOpen,
                    onDismiss = { speedOpen = false },
                    items = SPEEDS.map { s ->
                        MenuItem(speedMenuLabel(s), selected = kotlin.math.abs(s - rate) < 0.01f) {
                            pendingRate = s
                            link.send(Command.Video(VideoAction.RATE, s))
                        }
                    },
                )
            }
        }
        video.subtitleOffset?.let { offset ->
            Spacer(Modifier.height(10.dp))
            // Shift subtitles when they come early or late. Only the TV player draws its own subtitles.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PhysicalKey("Subtitles earlier by half a second", { link.send(Command.Video(VideoAction.SUBTITLE_SHIFT, -0.5f)) }, Modifier.weight(1f).height(56.dp)) {
                    Text("−0.5 s", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Subtitles", style = MaterialTheme.typography.labelMedium, color = Tok.Dim)
                    Text(
                        when {
                            kotlin.math.abs(offset) < 0.05f -> "on time"
                            offset > 0 -> java.lang.String.format(java.util.Locale.ROOT, "%.1f s later", offset)
                            else -> java.lang.String.format(java.util.Locale.ROOT, "%.1f s earlier", -offset)
                        },
                        fontFamily = Mono,
                        fontSize = 14.sp,
                        color = Tok.Bone,
                    )
                }
                PhysicalKey("Subtitles later by half a second", { link.send(Command.Video(VideoAction.SUBTITLE_SHIFT, 0.5f)) }, Modifier.weight(1f).height(56.dp)) {
                    Text("+0.5 s", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (known && !video.isLive) "Note at ${formatClock(video.position)}" else "Quick note", onNote, Modifier.weight(1f), icon = R.drawable.ic_sticky_note, height = 56.dp)
            PillButton(
                if (video.native) "Back to the page" else "Exit full screen",
                { link.send(Command.Video(VideoAction.EXIT_FULLSCREEN)) },
                Modifier.weight(1f),
                icon = R.drawable.ic_x,
                height = 56.dp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Swipe down for the trackpad. The TV shows only the video.",
            style = MaterialTheme.typography.bodySmall,
            color = Tok.Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** The address pill: shows the page; tap to type an address or a search, then send it to the TV. */
@Composable
private fun AddressBar(page: BrowserState, onGo: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val shown = page.url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    fun go() {
        if (text.isNotBlank()) onGo(text.trim())
        focusManager.clearFocus()
    }

    Row(
        Modifier.fillMaxWidth().height(52.dp).fieldBox(editing, radius = 26.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(if (page.url.startsWith("http://")) R.drawable.ic_globe else R.drawable.ic_lock, tint = Tok.Dim, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = if (editing) text else shown.ifEmpty { "Start page" },
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Tok.Bone),
            cursorBrush = SolidColor(Tok.Signal),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged {
                if (it.isFocused && !editing) text = ""
                editing = it.isFocused
            }.semantics { contentDescription = "Address or search" },
        )
        HeaderButton(R.drawable.ic_send, "Send address to TV") {
            if (editing) go() else runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun Trackpad(link: BrowserLink, speed: () -> Float, modifier: Modifier) {
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val currentLink by rememberUpdatedState(link)
    val currentSpeed by rememberUpdatedState(speed)
    val tracker = remember(density) {
        TrackpadTracker(density, { currentSpeed() }) { action ->
            when (action) {
                is PadAction.Move -> currentLink.send(Command.Move(action.dx, action.dy))
                PadAction.Click -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentLink.send(Command.Click)
                }
                is PadAction.Scroll -> currentLink.send(Command.Scroll(action.dy))
            }
        }
    }
    TrailPad(
        hint = "One finger moves the cursor · Tap to click\nScroll with two fingers or the side strip",
        modifier = modifier.semantics {
            contentDescription = "Trackpad. Drag to move the pointer, tap to click, drag with two fingers to scroll."
        },
        gestures = Modifier.pointerInput(tracker) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                tracker.update(down.uptimeMillis, listOf(Pt(down.position.x, down.position.y)))
                var last = down.uptimeMillis
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    last = event.changes.maxOf { it.uptimeMillis }
                    if (pressed.isNotEmpty()) tracker.update(last, pressed.map { Pt(it.position.x, it.position.y) })
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                tracker.end(last)
            }
        },
    )
}

/**
 * A scroll area beside the trackpad: drag up or down with one finger to scroll the page, tap an arrow to
 * move a screenful. Dragging down scrolls down, like a scrollbar.
 */
@Composable
private fun ScrollStrip(link: BrowserLink, speed: () -> Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val currentLink by rememberUpdatedState(link)
    val currentSpeed by rememberUpdatedState(speed)
    var fingerY by remember { mutableStateOf<Float?>(null) }
    var travelled by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Tok.Panel)
            .semantics { contentDescription = "Scroll strip. Drag up or down to scroll the page. Tap the arrows to move a screenful." }
            .onSizeChanged { height = it.height.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { at ->
                    val step = when {
                        at.y < height * 0.22f -> STRIP_PAGE_STEP_DP   // up arrow: content moves down
                        at.y > height * 0.78f -> -STRIP_PAGE_STEP_DP  // down arrow
                        else -> 0f
                    }
                    if (step != 0f) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentLink.send(Command.Scroll(step))
                    }
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { at -> fingerY = at.y; travelled = 0f },
                    onDragEnd = { fingerY = null },
                    onDragCancel = { fingerY = null },
                ) { change, dy ->
                    change.consume()
                    fingerY = change.position.y.coerceIn(0f, height)
                    currentLink.send(Command.Scroll(stripScroll(dy, density, currentSpeed())))
                    travelled += kotlin.math.abs(dy)
                    if (travelled > 64.dp.toPx()) {
                        travelled = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            drawLine(
                Tok.Seam, Offset(x, 52.dp.toPx()), Offset(x, size.height - 52.dp.toPx()),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 11.dp.toPx())),
            )
            fingerY?.let { drawCircle(Tok.Signal, radius = 14.dp.toPx(), center = Offset(x, it)) }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) { Ico(R.drawable.ic_chevron_up, tint = Tok.Dim, size = 24.dp) }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { Ico(R.drawable.ic_chevron_down, tint = Tok.Dim, size = 24.dp) }
    }
}

/**
 * Types straight into whatever is focused on the TV page. New characters at the
 * end are sent as they appear; deleting sends backspace.
 */
@Composable
private fun LiveKeyboard(link: BrowserLink) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).fieldBox(focused, radius = 26.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { new ->
                when {
                    new.startsWith(text) -> if (new.length > text.length) link.send(Command.Text(new.substring(text.length)))
                    text.startsWith(new) -> repeat(text.length - new.length) { link.send(Command.Key(KeyName.BACKSPACE)) }
                    else -> {
                        // A replacement (autocorrect, paste over a selection): redo the tail.
                        val common = text.commonPrefixWith(new).length
                        repeat(text.length - common) { link.send(Command.Key(KeyName.BACKSPACE)) }
                        if (new.length > common) link.send(Command.Text(new.substring(common)))
                    }
                }
                text = new
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Grotesk, fontSize = 16.sp, color = Tok.Bone),
            cursorBrush = SolidColor(Tok.Signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                link.send(Command.Key(KeyName.ENTER))
                text = ""
            }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Click a text box on the TV, then type", fontFamily = Grotesk, fontSize = 16.sp, color = Tok.Dim)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
        HeaderButton(R.drawable.ic_corner_down_left, "Enter") {
            link.send(Command.Key(KeyName.ENTER))
            text = ""
        }
    }
}
