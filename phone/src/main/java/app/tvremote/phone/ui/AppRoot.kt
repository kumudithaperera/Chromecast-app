package app.tvremote.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import java.net.Inet4Address
import android.net.ConnectivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tvremote.phone.R
import app.tvremote.phone.data.BrowserLink
import app.tvremote.phone.data.Connection
import app.tvremote.phone.data.PairingStage
import app.tvremote.phone.data.Prefs
import app.tvremote.phone.data.Tv
import app.tvremote.phone.data.TvController
import app.tvremote.phone.data.TvDiscovery
import app.tvremote.phone.data.WatchNotes

val HEX_KEYS = listOf(
    listOf("1", "2", "3", "A"),
    listOf("4", "5", "6", "B"),
    listOf("7", "8", "9", "C"),
    listOf("0", "D", "E", "F"),
)

@Composable
fun AppRoot(controller: TvController, discovery: TvDiscovery, browser: BrowserLink, prefs: Prefs, notes: WatchNotes, onVoice: () -> Unit) {
    val ui by controller.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val pairing = ui.connection as? Connection.Pairing

    Box(Modifier.fillMaxSize().background(Tok.Ground)) {
        when {
            ui.tv == null || picking -> TvPickerScreen(
                discovery = discovery,
                current = ui.tv,
                isPaired = prefs::hasConnected,
                onPick = {
                    picking = false
                    controller.choose(it)
                },
                onBack = if (ui.tv != null) ({ picking = false }) else null,
            )
            pairing != null -> PairScreen(
                state = pairing,
                tvName = ui.tv?.name ?: "your TV",
                onSubmit = controller::submitCode,
                onCancel = controller::cancelPairing,
            )
            else -> RemoteScreen(
                ui = ui,
                controller = controller,
                onSwitchTv = { picking = true },
                onVoice = onVoice,
                browser = { onBack ->
                    BrowserScreen(
                        tv = ui.tv!!,
                        link = browser,
                        prefs = prefs,
                        notes = notes,
                        onOpenBrowserOnTv = { controller.launch("tvbrowser://home") },
                        onTvKey = controller::key,
                        tvKeysEnabled = ui.connection is Connection.Connected,
                        // The remote is up and the Chromecast hasn't said it drops volume keys (HDMI-CEC volume off).
                        tvVolume = ui.connection is Connection.Connected && ui.volumeControl != false,
                        onBack = onBack,
                    )
                },
                notes = { NotesScreen(notes) },
            )
        }
    }
}

/** "Pair: enter code" / "Pair: wrong code": a full screen with a hex keypad, no system keyboard. */
@Composable
private fun PairScreen(state: Connection.Pairing, tvName: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        CodeEntry(
            title = "Pair with\n$tvName",
            subtitle = if (state.stage == PairingStage.Starting) "Asking the TV for a code…" else "Type the 6-character code shown on your TV.",
            rows = HEX_KEYS,
            actionLabel = "Pair",
            onSubmit = onSubmit,
            onCancel = onCancel,
            error = state.error,
            busy = state.stage == PairingStage.Verifying,
            waiting = state.stage == PairingStage.Starting,
        )
    }
}

/** "Choose your TV". */
@Composable
fun TvPickerScreen(discovery: TvDiscovery, current: Tv?, isPaired: (String) -> Boolean, onPick: (Tv) -> Unit, onBack: (() -> Unit)?) {
    // Search only while this screen is visible and the app is in the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> discovery.start()
                Lifecycle.Event.ON_STOP -> discovery.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            discovery.stop()
        }
    }
    if (onBack != null) BackHandler(onBack = onBack)
    val found by discovery.found.collectAsState()
    val searching by discovery.searching.collectAsState()
    val context = LocalContext.current
    // Start from this phone's own network ("192.168.1."), so usually only the TV's last number is left to type.
    var ip by rememberSaveable { mutableStateOf(localPrefix(context)) }
    var focused by remember { mutableStateOf(false) }
    val canConnect = looksLikeHost(ip)

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        if (onBack != null) {
            Box(Modifier.padding(start = 8.dp, top = 4.dp)) { HeaderButton(R.drawable.ic_arrow_left, "Back", onBack) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (onBack != null) 4.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Choose your TV", style = MaterialTheme.typography.displaySmall, color = Tok.Bone)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Your phone and the Chromecast must be on the same Wi-Fi network.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tok.Dim,
                )
                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Tok.Signal, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            found.isEmpty() -> "Looking for TVs…"
                            searching -> "Looking for TVs · ${found.size} found"
                            else -> "${found.size} found"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Tok.Dim,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            items(found, key = { it.host }) { tv ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp)).background(Tok.Panel)
                        .clickable(role = Role.Button, onClickLabel = "Connect to ${tv.name}") { onPick(tv) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).background(Tok.Key, CircleShape), contentAlignment = Alignment.Center) {
                        Ico(R.drawable.ic_tv, size = 22.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            val paired = isPaired(tv.host)
                            Tag(if (paired) "Paired" else "New", if (paired) Tok.Ok else Tok.Dim, if (paired) Tok.Ok else Tok.Seam)
                        }
                        val note = if (tv.host == current?.host) "${tv.host} · current TV" else tv.host
                        Text(note, fontFamily = Mono, fontSize = 13.sp, color = Tok.Dim, maxLines = 1)
                    }
                    Ico(R.drawable.ic_chevron_right, tint = Tok.Dim)
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.Seam))
                Spacer(Modifier.height(20.dp))
                Text("TV not in the list?", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = Tok.Bone)
                Spacer(Modifier.height(14.dp))
                listOf(
                    "Wake the TV with its own remote. A sleeping Chromecast may not answer.",
                    "Check both are on the same Wi-Fi. Guest networks often hide devices from each other.",
                    "Still missing? Type its address below.",
                ).forEachIndexed { i, tip ->
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).border(1.dp, Tok.Seam, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", fontFamily = Mono, fontSize = 12.sp, color = Tok.Bone)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(tip, style = MaterialTheme.typography.bodyMedium, color = Tok.Dim)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("TV's IP address", style = MaterialTheme.typography.titleSmall, color = Tok.Bone)
            Spacer(Modifier.height(4.dp))
            Text("On the TV: Settings › Network & Internet › your Wi-Fi", style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Tok.Bone),
                    cursorBrush = SolidColor(Tok.Signal),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canConnect) onPick(Tv(ip, ip)) }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().height(52.dp).fieldBox(focused).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (ip.isEmpty()) Text("192.168.1.20", fontFamily = Mono, fontSize = 16.sp, color = Tok.Dim)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                )
                PillButton("Connect", { onPick(Tv(ip, ip)) }, primary = true, enabled = canConnect, height = 52.dp)
            }
        }
    }
}

private fun looksLikeHost(s: String) =
    s.isNotBlank() && s.length <= 253 && !s.endsWith('.') && s.all { it.isLetterOrDigit() || it in ".-:" }

/** "192.168.1." from this phone's IPv4 address on the active network, or "" when there isn't one. */
private fun localPrefix(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
    val props = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull() ?: return ""
    val v4 = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return ""
    return v4.hostAddress?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.plus(".") ?: ""
}

/** A small outlined label beside a name ("Paired", "New"). */
@Composable
private fun Tag(text: String, color: Color, border: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = Modifier.border(1.dp, border, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}
