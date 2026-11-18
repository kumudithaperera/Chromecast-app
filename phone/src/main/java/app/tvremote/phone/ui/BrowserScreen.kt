package app.tvremote.phone.ui

import android.view.KeyEvent
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
import app.tvremote.phone.data.endsAtMillis
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
import kotlinx.coroutines.launch

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
                subtitle = "Type the pairing code shown at the top right of the TV browser.",
                rows = PIN_KEYS,
                actionLabel = "Connect",
                onSubmit = { link.submitPin(tv.host, it) },
                onCancel = onBack,
                error = when {
                    s.identityChanged ->
                        "This TV's security identity changed, so the phone has to pair again. That's normal if the TV app " +
                            "was reinstalled. The code check below stops anything else on the network from pairing in its place."
                    s.wrong -> "That code didn't match. The TV now shows a new code; type that one."
                    else -> null
                },
                // Shorter keys: this keypad shares the screen with the tab bar.
                keyHeight = 54.dp,
            )
            is LinkState.Ready -> Controls(link, page, video, prefs, notes, tvName = tv.name)
            else -> {
                val (status, lamp) = when (s) {
                    LinkState.Connecting, LinkState.Idle -> "Connecting to ${tv.name}…" to Lamp.Busy
                    else -> "Not connected" to Lamp.Fault
                }
                Header("Browser on TV", status, lamp) { HeaderButton(R.drawable.ic_x, "Close", onBack) }
                Column(Modifier.padding(20.dp)) {
                    when (s) {
                        is LinkState.Locked -> FaultBanner("Too many wrong codes. The TV is locked for about ${s.seconds} seconds.") {
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
) {
    // Two tabs, Trackpad and Video, side by side in a pager: tap a tab or swipe across. The Video tab
    // exists while the TV reports a video, and opens by itself when that video goes full screen.
    val fullscreen = video?.fullscreen == true
    val hasVideo = video != null
    val pager = rememberPagerState(pageCount = { if (hasVideo) 2 else 1 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(fullscreen) { if (fullscreen && pager.currentPage != VIDEO_PAGE) pager.animateScrollToPage(VIDEO_PAGE) }

    var speed by remember { mutableFloatStateOf(prefs.trackpadSpeed) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    // Quick note is offered on the Video tab; the tab stays put while the note sheet is up.
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

    if (hasVideo) {
        Segmented(
            options = listOf("Trackpad", if (video?.playing == true) "Video · playing" else "Video"),
            selected = pager.currentPage,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            icons = listOf(R.drawable.ic_mouse_pointer_2, R.drawable.ic_clapperboard),
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
        )
    }
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalAlignment = Alignment.Top,
        key = { it },
    ) { pageIndex ->
        if (pageIndex == VIDEO_PAGE && video != null) {
            PlayerPanel(
                video, page, link,
                pointerSpeed = { speed },
                // Only the tab on screen takes the phone's volume buttons.
                active = pager.currentPage == VIDEO_PAGE,
                onNote = { noteOpen = true },
            )
        } else {
            TrackpadPage(link, page, speed = { speed }, keyboard = keyboard, onKeyboard = { keyboard = !keyboard })
        }
    }

    if (noteOpen) {
        QuickNoteSheet(
            page = page,
            video = video,
            onSave = { d ->
                notes.add(d.show, d.episode, d.text, page.url, d.position)
                android.widget.Toast.makeText(context, "Saved to Notes", android.widget.Toast.LENGTH_SHORT).show()
                noteOpen = false
            },
            onDismiss = { noteOpen = false },
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

/** Index of the Video tab in the browser screen's pager; the trackpad is 0. */
private const val VIDEO_PAGE = 1

/** The Trackpad tab: address bar, notices, the pad with its scroll strip, and the key row. */
@Composable
private fun TrackpadPage(
    link: BrowserLink,
    page: BrowserState,
    speed: () -> Float,
    keyboard: Boolean,
    onKeyboard: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp)) {
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
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Trackpad(link, speed = speed, modifier = Modifier.weight(1f).fillMaxHeight())
            // Its own scroll area, so scrolling doesn't need two fingers on the trackpad.
            ScrollStrip(link, speed = speed, modifier = Modifier.width(56.dp).fillMaxHeight())
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
            IconKey(R.drawable.ic_keyboard, if (keyboard) "Hide keyboard" else "Keyboard", onKeyboard, Modifier.weight(1f).height(60.dp))
            // Click shares the key row, so the trackpad gets the height a separate row would take.
            PhysicalKey("Click", { link.send(Command.Click) }, Modifier.weight(1.9f).height(60.dp), primary = true) {
                Text("Click", fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Tok.Ground)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

}

/** How much one press of a volume key changes the video's own volume (0 to 1). */
private const val VOLUME_STEP = 0.05f

/**
 * Sends the phone's hardware volume buttons to [onKey] (as remote key codes) while [enabled], in place of
 * the activity's own handling (which only knows the remote's TV keys). Hooks the window this composable
 * is in: the activity's, or a dialog's when it sits in one.
 */
@Composable
private fun VolumeButtonsTo(enabled: Boolean, onKey: (Int) -> Unit) {
    val view = LocalView.current
    val on by rememberUpdatedState(enabled)
    val key by rememberUpdatedState(onKey)
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: view.context.findActivity()?.window
            ?: return@DisposableEffect onDispose {}
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val code = when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> Keys.VOLUME_UP
                    KeyEvent.KEYCODE_VOLUME_DOWN -> Keys.VOLUME_DOWN
                    else -> return original.dispatchKeyEvent(event)
                }
                if (!on) return original.dispatchKeyEvent(event)
                // Held buttons repeat their key-down, like holding a remote key.
                if (event.action == KeyEvent.ACTION_DOWN) key(code)
                return true
            }
        }
        onDispose { window.callback = original }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The playback speeds offered, as one row so the current one is always visible. */
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 0.75 -> "0.75×", 1 -> "1×". */
private fun speedLabel(rate: Float): String =
    java.lang.String.format(java.util.Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"

/**
 * The Video tab: remote control for the video playing on the TV. Nothing is drawn on the TV.
 *
 * Ordered by how often each control is used and where the thumb rests. What is playing sits at the top
 * and is only read. The controls fill the lower part of the screen: a small pointer pad for the player's
 * own buttons, the scrubber and the transport keys (used all the time), then sound and speed under their
 * own labels, and the rare actions (note, full screen) last. Every setting shows its current value
 * without opening a menu.
 */
@Composable
private fun PlayerPanel(
    video: VideoState,
    page: BrowserState,
    link: BrowserLink,
    pointerSpeed: () -> Float,
    active: Boolean,
    onNote: () -> Unit,
) {
    val known = video.position >= 0f
    // Volume here is the video's own sound in the page: it works with any TV, up to the TV's current level.
    val volumeKey: (Int) -> Unit = { code ->
        when (code) {
            Keys.VOLUME_UP -> link.send(Command.Video(VideoAction.VOLUME, VOLUME_STEP))
            Keys.VOLUME_DOWN -> link.send(Command.Video(VideoAction.VOLUME, -VOLUME_STEP))
            Keys.MUTE -> link.send(Command.Video(VideoAction.MUTE))
        }
    }
    VolumeButtonsTo(enabled = active && known, onKey = volumeKey)

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
    val host = remember(page.url) { runCatching { android.net.Uri.parse(page.url).host }.getOrNull().orEmpty().removePrefix("www.") }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = screen)
                .padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp),
            // Information at the top, controls pushed down into thumb reach; scrolls on short screens.
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            NowPlaying(video, title = page.title.ifBlank { host.ifBlank { "Video" } }, host = host)

            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                // A small pad for the pointer, for the player's own buttons on the TV (skip intro, quality,
                // next episode) without leaving this tab. Tap to click; swiping here moves the pointer.
                Trackpad(
                    link,
                    speed = pointerSpeed,
                    hint = "Move the pointer · Tap to click",
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                Spacer(Modifier.height(18.dp))
                when {
                    known && video.duration > 0f -> Scrubber(
                        shown = shown,
                        video = video,
                        rate = rate,
                        dragging = dragging,
                        onDrag = { dragging = true; dragTo = it },
                        onRelease = {
                            dragging = false
                            pendingSeek = dragTo
                            link.send(Command.Video(VideoAction.SEEK, dragTo))
                        },
                    )
                    known -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                            color = Tok.Ground,
                            modifier = Modifier.background(Tok.Fault, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(formatClock(video.position), fontFamily = Mono, fontSize = 14.sp, color = Tok.Bone)
                    }
                    else -> Text(
                        "This player doesn't share its position, so only play and pause can be sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tok.Dim,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Transport(video, enabled = known, send = link::send)

                PanelSection("Sound") {
                    VolumeRocker(volumeKey, enabled = known, vertical = false, modifier = Modifier.fillMaxWidth().height(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (known) "Changes the video's own sound, up to the TV's current level." else "This player doesn't share its volume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tok.Dim,
                    )
                }

                if (known) {
                    PanelSection("Speed", trailing = if (kotlin.math.abs(rate - 1f) < 0.01f) "Normal" else speedLabel(rate)) {
                        Segmented(
                            options = SPEEDS.map(::speedLabel),
                            selected = SPEEDS.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f },
                            onSelect = { i ->
                                pendingRate = SPEEDS[i]
                                link.send(Command.Video(VideoAction.RATE, SPEEDS[i]))
                            },
                            height = 44.dp,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        if (known && !video.isLive) "Note at ${formatClock(video.position)}" else "Quick note",
                        onNote,
                        Modifier.weight(1f),
                        icon = R.drawable.ic_sticky_note,
                        height = 52.dp,
                    )
                    // One button for the screen mode: full screen when the video is in the page, back out when it's full.
                    PillButton(
                        if (video.fullscreen) "Exit full screen" else "Full screen",
                        { link.send(Command.Video(if (video.fullscreen) VideoAction.EXIT_FULLSCREEN else VideoAction.FULLSCREEN)) },
                        Modifier.weight(1f),
                        icon = if (video.fullscreen) R.drawable.ic_x else R.drawable.ic_maximize,
                        height = 52.dp,
                    )
                }
            }
        }
    }
}

/** What is playing: the player and its state, the title, the site. Read, never touched. */
@Composable
private fun NowPlaying(video: VideoState, title: String, host: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (video.playing) Tok.Signal else Tok.Dim, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(
                    if (video.fullscreen) "FULL SCREEN" else "IN THE PAGE",
                    when {
                        video.position < 0f -> null
                        video.playing -> "PLAYING"
                        else -> "PAUSED"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                color = Tok.Dim,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 27.sp),
            color = Tok.Bone,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (host.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(host, fontFamily = Mono, fontSize = 13.sp, color = Tok.Dim, maxLines = 1)
        }
    }
}

/** Position bar with elapsed time, when it ends (at the current speed) and the length. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scrubber(
    shown: Float,
    video: VideoState,
    rate: Float,
    dragging: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val colors = SliderDefaults.colors(
        thumbColor = Tok.Signal,
        activeTrackColor = Tok.Signal,
        inactiveTrackColor = Tok.Key,
        activeTickColor = Tok.Signal,
        inactiveTickColor = Tok.Key,
    )
    val context = LocalContext.current
    val endsAt = endsAtMillis(System.currentTimeMillis(), shown, video.duration, rate)
        ?.let { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) }
    Slider(
        value = shown.coerceIn(0f, video.duration),
        onValueChange = onDrag,
        onValueChangeFinished = onRelease,
        valueRange = 0f..video.duration,
        colors = colors,
        // The design's round knob on a flat track, a little larger while held so the finger doesn't hide it.
        thumb = { Box(Modifier.size(if (dragging) 28.dp else 22.dp).background(Tok.Signal, CircleShape)) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                drawStopIndicator = null,
                modifier = Modifier.height(6.dp),
            )
        },
        modifier = Modifier.semantics { contentDescription = "Position ${formatClock(shown)} of ${formatClock(video.duration)}" },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // While dragging, the time to jump to stands out: it is what will happen on release.
        Text(formatClock(shown), fontFamily = Mono, fontSize = 13.sp, color = if (dragging) Tok.Signal else Tok.Bone)
        Spacer(Modifier.weight(1f))
        if (endsAt != null) Text("Ends $endsAt", style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
        Spacer(Modifier.weight(1f))
        Text(formatClock(video.duration), fontFamily = Mono, fontSize = 13.sp, color = Tok.Dim)
    }
}

/** Back 10 s, play or pause (the one yellow key, largest and in the middle), forward 10 s. */
@Composable
private fun Transport(video: VideoState, enabled: Boolean, send: (Command) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PhysicalKey("Back 10 seconds", { send(Command.Video(VideoAction.SKIP, -10f)) }, Modifier.weight(1f).height(64.dp), enabled = enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(R.drawable.ic_rewind, size = 24.dp)
                Text("10", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
            }
        }
        PhysicalKey(
            if (video.playing) "Pause" else "Play",
            { send(Command.Video(VideoAction.TOGGLE)) },
            Modifier.weight(1.4f).height(76.dp),
            primary = true,
        ) { Ico(if (video.playing) R.drawable.ic_pause else R.drawable.ic_play, tint = Tok.Ground, size = 32.dp) }
        PhysicalKey("Forward 10 seconds", { send(Command.Video(VideoAction.SKIP, 10f)) }, Modifier.weight(1f).height(64.dp), enabled = enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("10", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Tok.Bone)
                Ico(R.drawable.ic_fast_forward, size = 24.dp)
            }
        }
    }
}

/** A labelled group in the Video tab, with the setting's current value on the right. */
@Composable
private fun PanelSection(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp), color = Tok.Dim, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, fontFamily = Mono, fontSize = 13.sp, color = Tok.Bone)
        }
        content()
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
private fun Trackpad(
    link: BrowserLink,
    speed: () -> Float,
    modifier: Modifier,
    hint: String = "One finger moves the cursor · Tap to click\nScroll with two fingers or the side strip",
) {
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
        hint = hint,
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
