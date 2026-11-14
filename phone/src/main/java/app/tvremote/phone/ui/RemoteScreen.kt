package app.tvremote.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R
import app.tvremote.phone.atv.Keys
import app.tvremote.phone.data.Connection
import app.tvremote.phone.data.Shortcut
import app.tvremote.phone.data.TvController
import app.tvremote.phone.data.UiState
import kotlinx.coroutines.delay

private enum class Tab(val nav: NavTab) {
    REMOTE(NavTab("Remote", R.drawable.ic_tv)),
    APPS(NavTab("Apps", R.drawable.ic_layout_grid)),
    BROWSER(NavTab("Browser", R.drawable.ic_globe)),
    NOTES(NavTab("Notes", R.drawable.ic_sticky_note)),
}

/** The Apps tab (and the Remote tab's All apps key) is hidden for now. Its code stays; set true to bring it back. */
private const val SHOW_APPS = false

private val TABS = Tab.entries.filter { it != Tab.APPS || SHOW_APPS }

/**
 * The phone remote from the "Phone remote (built)" page: Remote and Apps, plus the TV browser's
 * trackpad and the watch notes. The last two bring their own header; [browser] gets a way back to the Remote tab.
 */
@Composable
fun RemoteScreen(
    ui: UiState,
    controller: TvController,
    onSwitchTv: () -> Unit,
    onVoice: () -> Unit,
    browser: @Composable (onBack: () -> Unit) -> Unit,
    notes: @Composable () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(Tab.REMOTE.name) }
    val tab = TABS.firstOrNull { it.name == tabName } ?: Tab.REMOTE
    fun go(t: Tab) { tabName = t.name }
    var menuOpen by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var editingApps by rememberSaveable { mutableStateOf(false) }
    val tvName = ui.tv?.name ?: "TV"
    val connected = ui.connection is Connection.Connected
    // The tab bar steps aside for the keyboard, so a tab's content gets the whole space above it.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(tab) { if (tab != Tab.APPS) editingApps = false }
    BackHandler(enabled = tab != Tab.REMOTE || editingApps) { if (editingApps) editingApps = false else go(Tab.REMOTE) }

    Column(Modifier.fillMaxSize().background(Tok.Ground).statusBarsPadding()) {
        when (tab) {
            Tab.REMOTE -> RemoteHeader(
                tvName = tvName,
                status = statusLine(ui),
                lamp = lampFor(ui.connection),
                connected = connected,
                onSwitchTv = onSwitchTv,
                onPower = { controller.key(Keys.POWER) },
            ) {
                Box {
                    HeaderButton(R.drawable.ic_ellipsis_vertical, "More options") { menuOpen = true }
                    PanelMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        items = listOfNotNull(
                            if (connected) MenuItem("TV settings", icon = R.drawable.ic_settings) { controller.key(Keys.SETTINGS) } else null,
                            MenuItem("Switch TV", icon = R.drawable.ic_tv, divider = connected, onClick = onSwitchTv),
                            MenuItem("Pair again", icon = R.drawable.ic_link, onClick = controller::repair),
                            MenuItem("Forget this TV", danger = true, icon = R.drawable.ic_trash_2, divider = true) { forgetting = true },
                        ),
                    )
                }
            }
            Tab.APPS -> Header("Apps", if (connected) "Opens on $tvName" else statusLine(ui), lampFor(ui.connection)) {
                Box(
                    Modifier.padding(end = 4.dp).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Tok.Seam, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button) { editingApps = !editingApps }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (editingApps) "Done" else "Edit", style = MaterialTheme.typography.labelLarge, color = Tok.Bone) }
            }
            else -> Unit
        }
        // The tab bar already keeps clear of the system navigation bar; content must not leave room for it again.
        val content = if (keyboardOpen) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars)
        Box(Modifier.weight(1f).fillMaxWidth().then(content)) {
            when (tab) {
                Tab.REMOTE -> RemoteTab(ui, controller, onVoice = onVoice, onSwitchTv = onSwitchTv, onAllApps = if (SHOW_APPS) ({ go(Tab.APPS) }) else null)
                Tab.APPS -> AppsTab(ui, controller, editing = editingApps)
                Tab.BROWSER -> browser { go(Tab.REMOTE) }
                Tab.NOTES -> notes()
            }
        }
        if (!keyboardOpen) BottomNav(TABS.map { it.nav }, TABS.indexOf(tab)) { go(TABS[it]) }
    }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            containerColor = Tok.Panel,
            title = { Text("Forget $tvName?", style = MaterialTheme.typography.headlineSmall, color = Tok.Bone) },
            text = { Text("You'll need the pairing code from the TV to use it again. Your apps and notes stay.", style = MaterialTheme.typography.bodyMedium, color = Tok.Dim) },
            confirmButton = { PillButton("Forget", { forgetting = false; controller.forgetTv() }, primary = true, height = 48.dp) },
            dismissButton = { TextAction("Cancel", { forgetting = false }) },
        )
    }
}

/** The Remote tab's header: the TV name opens the TV list, Power sits apart from Home, then the menu. */
@Composable
private fun RemoteHeader(
    tvName: String,
    status: String,
    lamp: Lamp,
    connected: Boolean,
    onSwitchTv: () -> Unit,
    onPower: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Switch TV", onClick = onSwitchTv)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tvName, style = MaterialTheme.typography.titleLarge, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Ico(R.drawable.ic_chevron_down, tint = Tok.Dim, size = 18.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(lamp.color, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = Tok.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (connected) {
            PhysicalKey("Power", onPower, Modifier.size(48.dp)) { Ico(R.drawable.ic_power, size = 20.dp) }
            Spacer(Modifier.width(4.dp))
        }
        menu()
    }
}

// ---- Remote tab ----

private val ROCKER_WIDTH = 62.dp
private val PAD_GAP = 12.dp

@Composable
private fun RemoteTab(ui: UiState, controller: TvController, onVoice: () -> Unit, onSwitchTv: () -> Unit, onAllApps: (() -> Unit)?) {
    val connected = ui.connection is Connection.Connected
    val offline = ui.connection is Connection.Failed || ui.connection == Connection.Idle
    var touchpad by rememberSaveable { mutableStateOf(controller.useTouchpad) }
    val shortcuts by controller.shortcuts.collectAsState()
    val key: (Int) -> Unit = controller::key

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - 40.dp
        // The design's 276dp pad beside a 62dp volume rocker; narrower phones shrink the pad, not the keys.
        val pad = minOf(276.dp, width - ROCKER_WIDTH - PAD_GAP)
        // imePadding: the type box sits near the top, and the keyboard must not cover what's below it either.
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)) {
            if (offline) {
                OfflineCard(ui, controller, onSwitchTv)
                Spacer(Modifier.height(20.dp))
            } else {
                if (ui.connection == Connection.Connecting) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = Tok.Signal,
                        trackColor = Tok.Panel,
                    )
                }
                QuickLaunch(ui, shortcuts, width, enabled = connected, onLaunch = controller::launch, onAllApps = onAllApps)
                Spacer(Modifier.height(14.dp))
                TypeBox(controller, enabled = connected)
                Segmented(
                    listOf("D-pad", "Touchpad"),
                    selected = if (touchpad) 1 else 0,
                    onSelect = {
                        touchpad = it == 1
                        controller.useTouchpad = touchpad
                    },
                    icons = listOf(null, R.drawable.ic_hand),
                    height = 36.dp,
                    modifier = Modifier.padding(top = 22.dp, bottom = 20.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
                if (touchpad && !offline) {
                    Touchpad(key, enabled = connected, modifier = Modifier.weight(1f).height(pad))
                } else {
                    DPad(key, enabled = connected, diameter = pad)
                }
                VolumeRocker(key, enabled = connected, modifier = Modifier.width(ROCKER_WIDTH).height(pad))
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhysicalKey("Back", { key(Keys.BACK) }, Modifier.weight(1.5f).height(68.dp), enabled = connected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ico(R.drawable.ic_arrow_left)
                        Spacer(Modifier.width(8.dp))
                        Text("Back", style = MaterialTheme.typography.labelLarge, color = Tok.Bone)
                    }
                }
                IconKey(R.drawable.ic_house, "Home", { key(Keys.HOME) }, Modifier.weight(1f).height(68.dp), enabled = connected)
                IconKey(R.drawable.ic_mic, "Voice type on the TV", onVoice, Modifier.weight(1f).height(68.dp), enabled = connected)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconKey(R.drawable.ic_rewind, "Rewind", { key(Keys.MEDIA_REWIND) }, Modifier.weight(1f).height(68.dp), repeat = true, enabled = connected)
                IconKey(R.drawable.ic_play, "Play or pause", { key(Keys.MEDIA_PLAY_PAUSE) }, Modifier.weight(1.5f).height(68.dp), enabled = connected)
                IconKey(R.drawable.ic_fast_forward, "Fast forward", { key(Keys.MEDIA_FAST_FORWARD) }, Modifier.weight(1f).height(68.dp), repeat = true, enabled = connected)
            }
            if (connected && ui.volumeControl == false) VolumeNote()
        }
    }
}

/**
 * Type on the TV from the Remote tab: tapping the box opens the phone's keyboard, and the TV's selected
 * text box mirrors it as you type. Enter on the keyboard submits (searches) on the TV. Recent searches
 * show as chips only while typing.
 */
@Composable
private fun TypeBox(controller: TvController, enabled: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val recent by controller.recentTexts.collectAsState()
    val focusManager = LocalFocusManager.current

    // The TV's text box mirrors this field. The protocol can't send empty text, so clearing deletes key by key.
    fun update(new: String) {
        val old = text
        text = new
        if (new.isEmpty()) repeat(old.length.coerceAtMost(64)) { controller.key(Keys.DEL) } else controller.text(new)
    }

    fun submit() {
        controller.key(Keys.ENTER)
        if (text.isNotBlank()) controller.rememberText(text)
        focusManager.clearFocus()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).alpha(if (enabled) 1f else 0.5f)
                .fieldBox(focused, radius = 26.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(R.drawable.ic_keyboard, tint = Tok.Dim, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = text,
                onValueChange = ::update,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontFamily = Grotesk, fontSize = 16.sp, color = Tok.Bone),
                cursorBrush = SolidColor(Tok.Signal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Type on TV", fontFamily = Grotesk, fontSize = 16.sp, color = Tok.Dim)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "Type on the TV" },
            )
            if (text.isNotEmpty()) HeaderButton(R.drawable.ic_x, "Clear") { update("") }
        }
        if (focused) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Select a search box on the TV first. Letters appear there as you type.",
                style = MaterialTheme.typography.bodySmall,
                color = Tok.Dim,
            )
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { t ->
                        Row(
                            Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Tok.Seam, RoundedCornerShape(18.dp))
                                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type $t on the TV") { update(t) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ico(R.drawable.ic_clock, tint = Tok.Dim, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(t, style = MaterialTheme.typography.bodyMedium, color = Tok.Bone, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Shown when the Chromecast says it won't pass volume on to the TV, so dead volume keys aren't a mystery. */
@Composable
private fun VolumeNote() {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
        Ico(R.drawable.ic_triangle_alert, tint = Tok.Dim, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Volume isn't reaching your TV: HDMI-CEC volume control is switched off on this Chromecast.",
            style = MaterialTheme.typography.bodySmall,
            color = Tok.Dim,
        )
    }
}

/** "Remote: not connected": what went wrong, the one action that fixes it, and a way to pick another TV. */
@Composable
private fun OfflineCard(ui: UiState, controller: TvController, onSwitchTv: () -> Unit) {
    val name = ui.tv?.name ?: "the TV"
    when (val c = ui.connection) {
        is Connection.Failed -> ProblemCard(if (c.needsRepair) "Pair with $name again" else "Can't reach $name", c.message) {
            if (c.needsRepair) {
                PillButton("Pair again", controller::repair, Modifier.weight(1f), primary = true, icon = R.drawable.ic_link, height = 48.dp)
            } else {
                PillButton("Retry now", controller::connect, Modifier.weight(1f), primary = true, icon = R.drawable.ic_refresh_cw, height = 48.dp)
            }
            PillButton("Choose a TV", onSwitchTv, Modifier.weight(1f), height = 48.dp)
        }
        Connection.Idle -> ProblemCard("Not connected", "The remote isn't connected to $name.") {
            PillButton("Connect", controller::connect, Modifier.weight(1f), primary = true, height = 48.dp)
            PillButton("Choose a TV", onSwitchTv, Modifier.weight(1f), height = 48.dp)
        }
        else -> Unit
    }
}

/**
 * The quick-launch row: the app on the TV now (or the first shortcut, labelled), as many other
 * shortcuts as fit, then All apps.
 */
@Composable
private fun QuickLaunch(ui: UiState, shortcuts: List<Shortcut>, width: Dp, enabled: Boolean, onLaunch: (String) -> Unit, onAllApps: (() -> Unit)?) {
    val onTv = brandOf(ui.currentApp)
    val live = if (onTv == null) null else shortcuts.firstOrNull { (brandOf(it.link) ?: brandOf(it.name)) == onTv }
    val lead = live ?: shortcuts.firstOrNull()
    val others = shortcuts.filter { it != lead }
    val keySize = 52.dp
    val gap = 6.dp
    val chip = 118.dp
    val allApps = if (onAllApps != null) keySize + gap else 0.dp
    val fit = ((width - chip - allApps) / (keySize + gap)).toInt().coerceAtLeast(0)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) {
            if (lead == live) {
                Row(
                    Modifier.width(chip).height(keySize).clip(RoundedCornerShape(16.dp)).background(Tok.Panel)
                        .border(1.dp, Tok.Dim, RoundedCornerShape(16.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open ${lead.name} on TV") { onLaunch(lead.link) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppLogo(lead, 28.dp)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(lead.name, style = MaterialTheme.typography.titleSmall, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("On now", style = MaterialTheme.typography.labelSmall, color = Tok.Dim)
                    }
                }
            } else {
                PhysicalKey("Open ${lead.name} on TV", { onLaunch(lead.link) }, Modifier.width(chip).height(keySize), enabled = enabled, radius = 16.dp) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(lead, 28.dp)
                        Spacer(Modifier.width(9.dp))
                        Text(lead.name, style = MaterialTheme.typography.titleSmall, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        others.take(fit).forEach { s ->
            PhysicalKey("Open ${s.name} on TV", { onLaunch(s.link) }, Modifier.size(keySize), enabled = enabled, radius = 16.dp) { AppLogo(s, 28.dp) }
        }
        if (onAllApps != null) PhysicalKey("All apps", onAllApps, Modifier.size(keySize), radius = 16.dp) { Ico(R.drawable.ic_layout_grid) }
    }
}

/** A shortcut's real logo when it's a known service, otherwise a generic app icon. */
@Composable
private fun AppLogo(s: Shortcut, size: Dp) {
    val logo = (brandOf(s.link) ?: brandOf(s.name))?.let(LOGOS::get)
    if (logo != null) {
        Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)))
    } else {
        Box(Modifier.size(size).background(Tok.Key, CircleShape), contentAlignment = Alignment.Center) {
            Ico(R.drawable.ic_app_window, size = size * 0.6f)
        }
    }
}

// ---- Apps tab ----

internal val LOGOS = mapOf(
    "youtube" to R.drawable.logo_youtube,
    "netflix" to R.drawable.logo_netflix,
    "prime" to R.drawable.logo_prime_video,
    "disney" to R.drawable.logo_disney_plus,
    "plex" to R.drawable.logo_plex,
    "twitch" to R.drawable.logo_twitch,
    "tubi" to R.drawable.logo_tubi,
    "pluto" to R.drawable.logo_pluto_tv,
    "jellyfin" to R.drawable.logo_jellyfin,
    "moonlight" to R.drawable.logo_moonlight,
)

/** Which known service a link, name or package name belongs to. */
internal fun brandOf(text: String?): String? {
    val t = text?.lowercase() ?: return null
    return when {
        "youtube" in t || "youtu.be" in t -> "youtube"
        "netflix" in t -> "netflix"
        "primevideo" in t || "prime video" in t || "amazonvideo" in t -> "prime"
        "disney" in t -> "disney"
        "plex" in t -> "plex"
        "twitch" in t -> "twitch"
        "tubi" in t -> "tubi"
        "pluto" in t -> "pluto"
        "jellyfin" in t -> "jellyfin"
        "moonlight" in t || "limelight" in t -> "moonlight"
        else -> null
    }
}

/**
 * "Apps": the app on the TV now as a card, then the shortcuts three across.
 * Edit (in the header) shows a remove badge on each tile; long-press still removes too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsTab(ui: UiState, controller: TvController, editing: Boolean) {
    val shortcuts by controller.shortcuts.collectAsState()
    val enabled = ui.connection is Connection.Connected
    val onTv = brandOf(ui.currentApp)
    // Any app on the TV except its home screen gets the card, known service or not.
    val liveApp = ui.currentApp?.takeIf { enabled && !editing && it != HOME_PACKAGE }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Shortcut?>(null) }
    val shown = shortcuts.filter { s -> liveApp == null || onTv == null || (brandOf(s.link) ?: brandOf(s.name)) != onTv }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (liveApp != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionLabel("On TV now")
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.Panel)
                            .border(1.dp, Tok.Dim, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val logo = onTv?.let(LOGOS::get)
                        if (logo != null) {
                            Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                        } else {
                            Box(Modifier.size(36.dp).background(Tok.Key, CircleShape), contentAlignment = Alignment.Center) { Ico(R.drawable.ic_tv, size = 20.dp) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(appLabel(liveApp), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel(if (editing) "Tap an app to remove it" else "Your apps")
        }
        items(shown, key = { it.name + it.link }) { s ->
            Column(
                Modifier
                    .height(104.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tok.Panel)
                    .alpha(if (enabled || editing) 1f else 0.55f)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = if (editing) "Remove ${s.name}" else "Open ${s.name} on TV",
                        onClick = { if (editing) removing = s else if (enabled) controller.launch(s.link) },
                        onLongClickLabel = "Remove",
                        onLongClick = { removing = s },
                    )
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    AppLogo(s, 32.dp)
                    Spacer(Modifier.weight(1f))
                    if (editing) {
                        Box(Modifier.size(24.dp).background(Tok.Key, CircleShape), contentAlignment = Alignment.Center) {
                            Ico(R.drawable.ic_x, size = 14.dp)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(s.name, style = MaterialTheme.typography.titleSmall, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!editing) {
            item {
                Column(
                    Modifier
                        .height(104.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .drawBehind {
                            drawRoundRect(
                                Tok.Seam,
                                cornerRadius = CornerRadius(16.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                            )
                        }
                        .clickable(role = Role.Button) { adding = true }
                        .padding(14.dp),
                ) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Ico(R.drawable.ic_plus, size = 24.dp) }
                    Spacer(Modifier.weight(1f))
                    Text("Add an app", style = MaterialTheme.typography.titleSmall, color = Tok.Bone, maxLines = 1)
                }
            }
        }
    }

    if (adding) AddAppSheet(onAdd = { controller.addShortcut(it); adding = false }, onDismiss = { adding = false })
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Tok.Panel,
            title = { Text("Remove ${s.name}?", style = MaterialTheme.typography.headlineSmall, color = Tok.Bone) },
            text = { Text(s.link, fontFamily = Mono, fontSize = 14.sp, color = Tok.Dim) },
            confirmButton = { PillButton("Remove", { controller.removeShortcut(s); removing = null }, primary = true, height = 48.dp) },
            dismissButton = { TextAction("Cancel", { removing = null }) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppSheet(onAdd: (Shortcut) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    val target = normalizeLink(link)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tok.Panel,
        scrimColor = Tok.Ground.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Add an app", style = MaterialTheme.typography.headlineSmall, color = Tok.Bone)
            Spacer(Modifier.height(22.dp))
            Text("Name", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
            Spacer(Modifier.height(8.dp))
            SheetField(name, { name = it }, mono = false, keyboard = KeyboardType.Text)
            Spacer(Modifier.height(18.dp))
            Text("Link or package name", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
            Spacer(Modifier.height(8.dp))
            SheetField(link, { link = it.trim() }, mono = true, keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(14.dp))
            Text(
                buildAnnotatedString {
                    append("Use a deep link such as ")
                    withStyle(SpanStyle(fontFamily = Mono, color = Tok.Bone)) { append("plex://") }
                    append(" or ")
                    withStyle(SpanStyle(fontFamily = Mono, color = Tok.Bone)) { append("https://www.youtube.com") }
                    append(". A package name also works for some apps.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Tok.Dim,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextAction("Cancel", onDismiss, Modifier.weight(1f))
                PillButton(
                    "Add",
                    { target?.let { onAdd(Shortcut(name.trim().ifEmpty { link }, it)) } },
                    Modifier.weight(1.8f),
                    primary = true,
                    enabled = target != null,
                )
            }
        }
    }
}

@Composable
private fun SheetField(value: String, onChange: (String) -> Unit, mono: Boolean, keyboard: KeyboardType) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = if (mono) Mono else Grotesk, fontSize = 15.sp, color = Tok.Bone),
        cursorBrush = SolidColor(Tok.Signal),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(52.dp).fieldBox(focused).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}

/** Deep links pass through; a bare package name becomes a market://launch link. */
private fun normalizeLink(input: String): String? = when {
    input.isBlank() -> null
    "://" in input -> input
    Regex("""[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+""").matches(input) -> "market://launch?id=$input"
    else -> null
}

// ---- status text ----

private fun lampFor(c: Connection): Lamp = when (c) {
    Connection.Connected -> Lamp.Ok
    Connection.Connecting, is Connection.Pairing -> Lamp.Busy
    else -> Lamp.Fault
}

private fun statusLine(ui: UiState): String = when (ui.connection) {
    // No volume here: a Chromecast never reports a level, so any number would be made up.
    Connection.Connected -> buildList {
        add("Connected")
        when {
            ui.poweredOn == false -> add("screen off")
            ui.currentApp == HOME_PACKAGE -> add("Home screen")
            ui.currentApp != null -> add("${appLabel(ui.currentApp)} on screen")
        }
    }.joinToString(" · ")
    Connection.Connecting -> "Connecting…"
    is Connection.Pairing -> "Pairing…"
    is Connection.Failed -> "Not connected"
    Connection.Idle -> "Not connected"
}

private const val HOME_PACKAGE = "com.google.android.apps.tv.launcherx"

private val KNOWN_APPS = mapOf(
    HOME_PACKAGE to "Home screen",
    "com.google.android.youtube.tv" to "YouTube",
    "com.netflix.ninja" to "Netflix",
    "com.amazon.amazonvideo.livingroom" to "Prime Video",
    "com.disney.disneyplus" to "Disney+",
    "com.plexapp.android" to "Plex",
    "org.jellyfin.androidtv" to "Jellyfin",
    "tv.twitch.android.app" to "Twitch",
    "com.spotify.tv.android" to "Spotify",
    "app.tvremote.tv" to "TV Browser",
)

internal fun appLabel(pkg: String) = KNOWN_APPS[pkg] ?: pkg
