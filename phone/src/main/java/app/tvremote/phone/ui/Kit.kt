package app.tvremote.phone.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A Lucide icon from res/drawable, tinted. */
@Composable
fun Ico(@DrawableRes res: Int, tint: Color = Tok.Bone, size: Dp = 22.dp, description: String? = null) {
    Icon(painterResource(res), contentDescription = description, tint = tint, modifier = Modifier.size(size))
}

/** Draws a moulded key: a 2dp lip below and a 1dp highlight along the top edge. */
private fun Modifier.keyCap(fill: Color, lip: Color, highlight: Color?, pressed: Boolean, radius: Dp?): Modifier =
    drawBehind {
        val r = CornerRadius(radius?.toPx() ?: (size.height / 2f))
        val lipH = 2.dp.toPx()
        val dy = if (pressed) lipH else 0f
        if (!pressed) drawRoundRect(lip, topLeft = Offset(0f, lipH), size = size, cornerRadius = r)
        if (highlight != null) {
            drawRoundRect(highlight, topLeft = Offset(0f, dy), size = size, cornerRadius = r)
            drawRoundRect(fill, topLeft = Offset(0f, dy + 1.dp.toPx()), size = Size(size.width, size.height - 1.dp.toPx()), cornerRadius = r)
        } else {
            drawRoundRect(fill, topLeft = Offset(0f, dy), size = size, cornerRadius = r)
        }
    }

/**
 * Remote-key input: fires on touch-down, which feels quicker than on release, and with [repeat]
 * keeps firing while held, like a real remote. [onPressedChange] drives the pressed look.
 */
@Composable
private fun Modifier.remoteKeyInput(
    description: String,
    enabled: Boolean,
    repeat: Boolean,
    onPress: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val fire by rememberUpdatedState(onPress)
    val pressedChange by rememberUpdatedState(onPressedChange)
    return semantics {
        role = Role.Button
        contentDescription = description
        if (enabled) onClick { fire(); true }
    }.pointerInput(enabled, repeat) {
        if (!enabled) return@pointerInput
        detectTapGestures(onPress = {
            pressedChange(true)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            fire()
            coroutineScope {
                val repeater = if (repeat) launch {
                    delay(400)
                    while (true) {
                        fire()
                        delay(90)
                    }
                } else null
                tryAwaitRelease()
                repeater?.cancel()
            }
            pressedChange(false)
        })
    }
}

/** A physical remote key. See [remoteKeyInput] for how it fires. */
@Composable
fun PhysicalKey(
    description: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    radius: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val fill = when {
        primary && enabled -> Tok.Signal
        pressed -> Tok.Seam
        else -> Tok.Key
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .keyCap(fill, if (primary && enabled) Tok.SignalLip else Tok.KeyLip, if (primary && enabled) null else Tok.KeyHighlight, pressed, radius)
            .remoteKeyInput(description, enabled, repeat, onPress) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(y = if (pressed) 2.dp else 0.dp), contentAlignment = Alignment.Center, content = content)
    }
}

/**
 * Volume up, mute and volume down on one key cap, like the rocker on a physical remote.
 * Vertical beside the D-pad (up on top), horizontal in the video panel (down on the left).
 */
@Composable
fun VolumeRocker(onKey: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val down = Triple(R.drawable.ic_volume_1, "Volume down", app.tvremote.phone.atv.Keys.VOLUME_DOWN)
    val mute = Triple(R.drawable.ic_volume_x, "Mute", app.tvremote.phone.atv.Keys.MUTE)
    val up = Triple(R.drawable.ic_volume_2, "Volume up", app.tvremote.phone.atv.Keys.VOLUME_UP)
    val radius = if (vertical) 31.dp else 28.dp

    @Composable
    fun Segment(spec: Triple<Int, String, Int>, segment: Modifier) {
        var pressed by remember { mutableStateOf(false) }
        val isMute = spec === mute
        Box(
            segment
                .clip(RoundedCornerShape(radius))
                .background(if (pressed) Tok.Seam else Color.Transparent)
                .remoteKeyInput(spec.second, enabled, repeat = !isMute, onPress = { onKey(spec.third) }) { pressed = it },
            contentAlignment = Alignment.Center,
        ) {
            if (isMute && vertical) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(spec.first, tint = Tok.Dim, size = 18.dp)
                    Spacer(Modifier.height(3.dp))
                    Text("VOL", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = Tok.Dim)
                }
            } else {
                Ico(spec.first, tint = if (isMute) Tok.Dim else Tok.Bone, size = if (vertical) 22.dp else 20.dp)
            }
        }
    }

    @Composable
    fun Rule() = Box(Modifier.background(Tok.Seam).then(if (vertical) Modifier.width(30.dp).height(1.dp) else Modifier.width(1.dp).height(26.dp)))

    Box(modifier.alpha(if (enabled) 1f else 0.45f).keyCap(Tok.Key, Tok.KeyLip, Tok.KeyHighlight, false, radius)) {
        if (vertical) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Segment(up, Modifier.weight(1f).fillMaxWidth())
                Rule()
                Segment(mute, Modifier.height(64.dp).fillMaxWidth())
                Rule()
                Segment(down, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Segment(down, Modifier.weight(1f).fillMaxHeight())
                Rule()
                Segment(mute, Modifier.weight(1f).fillMaxHeight())
                Rule()
                Segment(up, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** Icon-only key, the most common case. */
@Composable
fun IconKey(
    @DrawableRes icon: Int,
    description: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
) = PhysicalKey(description, onPress, modifier, repeat, enabled) { Ico(icon, size = iconSize) }

/** A pill button that acts on release (dialog and form actions, not remote keys). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    @DrawableRes icon: Int? = null,
    height: Dp = 56.dp,
) {
    val on = primary && enabled
    val fg = when {
        on -> Tok.Ground
        enabled -> Tok.Bone
        else -> Tok.Dim
    }
    Row(
        modifier
            .height(height)
            .keyCap(if (on) Tok.Signal else Tok.Key, if (on) Tok.SignalLip else Tok.KeyLip, if (on) null else Tok.KeyHighlight, false, null)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (icon != null) 12.dp else 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Ico(icon, tint = fg, size = 20.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Plain text action, for Cancel and similar. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Tok.Dim) {
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

enum class Lamp(val color: Color) { Ok(Tok.Ok), Busy(Tok.Signal), Fault(Tok.Fault) }

/** Screen header: title, a status lamp line, and optional trailing actions. */
@Composable
fun Header(
    title: String,
    status: String?,
    lamp: Lamp?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lamp != null) {
                        Box(Modifier.size(8.dp).background(lamp.color, CircleShape))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Tok.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        trailing()
    }
}

/** A 48dp icon button for header actions. */
@Composable
fun HeaderButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Ico(icon) }
}

/** A menu row. [divider] draws a hairline above it, to group rows. */
data class MenuItem(
    val label: String,
    val danger: Boolean = false,
    val selected: Boolean = false,
    @DrawableRes val icon: Int? = null,
    val divider: Boolean = false,
    val onClick: () -> Unit,
)

/** The "Remote menu" overlay: a panel with plain rows; destructive rows in the fault colour. */
@Composable
fun PanelMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Tok.Panel,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Tok.Seam),
        modifier = Modifier.width(240.dp),
    ) {
        items.forEach { item ->
            if (item.divider) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Tok.Seam))
            DropdownMenuItem(
                leadingIcon = item.icon?.let { res -> { Ico(res, tint = if (item.danger) Tok.Fault else Tok.Bone, size = 20.dp) } },
                text = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = when {
                            item.danger -> Tok.Fault
                            item.selected -> Tok.Signal
                            else -> Tok.Bone
                        },
                    )
                },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                modifier = Modifier.height(if (item.icon != null) 48.dp else 54.dp),
            )
        }
    }
}

/** Two-way switch (D-pad / Touchpad). The selected side is a bone pill; [icons] are optional per option. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<Int?> = emptyList(),
    height: Dp = 40.dp,
) {
    Row(
        modifier.fillMaxWidth().height(height).border(1.dp, Tok.Seam, RoundedCornerShape(height / 2)).padding(if (height < 40.dp) 2.dp else 3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(height / 2))
                    .background(if (on) Tok.Bone else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                icons.getOrNull(i)?.let {
                    Ico(it, tint = if (on) Tok.Ground else Tok.Dim, size = 15.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) Tok.Ground else Tok.Dim,
                )
              }
            }
        }
    }
}

data class NavTab(val label: String, @DrawableRes val icon: Int)

@Composable
fun BottomNav(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Tok.Ground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.Seam))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(74.dp).padding(top = 10.dp)) {
            tabs.forEachIndexed { i, tab ->
                val color = if (i == selected) Tok.Signal else Tok.Dim
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Ico(tab.icon, tint = color, size = 24.dp)
                    Text(tab.label, style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
        }
    }
}

/** A panel card with a fault stripe on the left, for connection problems. */
@Composable
fun FaultBanner(message: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.Panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(104.dp).background(Tok.Fault))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Tok.Bone, modifier = Modifier.weight(1f).padding(16.dp))
        Box(Modifier.padding(end = 16.dp)) { action() }
    }
}

/** A connection problem with a title, an explanation and its actions in a row underneath. */
@Composable
fun ProblemCard(title: String, body: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.Panel).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Tok.Fault))
        Column(Modifier.weight(1f).padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Ico(R.drawable.ic_triangle_alert, tint = Tok.Fault, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = Tok.Bone)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        }
    }
}

/** Small uppercase label above a group ("RECENT", "YOUR APPS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp),
        color = Tok.Dim,
        modifier = modifier,
    )
}

/**
 * A touch surface that leaves the design's dotted yellow trail behind the finger.
 * It only watches; the gesture handling goes in [gestures].
 */
@Composable
fun TrailPad(hint: String, modifier: Modifier = Modifier, enabled: Boolean = true, gestures: Modifier = Modifier) {
    val trail = remember { mutableStateListOf<Offset>() }
    var lifted by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifted) {
        if (lifted > 0) {
            delay(450)
            trail.clear()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Tok.Panel)
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    trail.clear()
                    trail.add(down.position)
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        e.changes.firstOrNull { it.pressed }?.let {
                            trail.add(it.position)
                            if (trail.size > 48) trail.removeAt(0)
                        }
                    } while (e.changes.any { it.pressed })
                    lifted++
                }
            }
            .then(gestures),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (trail.size > 1) {
                val p = Path().apply {
                    moveTo(trail[0].x, trail[0].y)
                    for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
                }
                drawPath(
                    p, Tok.Signal,
                    style = Stroke(
                        width = 3.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 11.dp.toPx())),
                    ),
                )
            }
            trail.lastOrNull()?.let { drawCircle(Tok.Signal, radius = 20.dp.toPx(), center = it) }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = Tok.Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/**
 * Fixed-length code entry with its own keypad (the "Pair: enter code" design).
 * [rows] are the keypad rows; a blank string leaves a gap.
 */
@Composable
fun CodeEntry(
    title: String,
    subtitle: String,
    rows: List<List<String>>,
    actionLabel: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    error: String? = null,
    busy: Boolean = false,
    waiting: Boolean = false,
    keyHeight: Dp = 64.dp,
) {
    var code by remember { mutableStateOf("") }
    var shownError by remember { mutableStateOf(error) }
    LaunchedEffect(error) { shownError = error }
    val haptic = LocalHapticFeedback.current
    val ready = code.length == length && !busy && !waiting

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, color = Tok.Bone)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (waiting) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Tok.Signal, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Tok.Dim)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until length) {
                val ch = code.getOrNull(i)?.toString().orEmpty()
                val border = when {
                    shownError != null -> Tok.Fault
                    i == code.length && !waiting -> Tok.Signal
                    else -> Color.Transparent
                }
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(10.dp)).background(Tok.Panel)
                        .border(if (shownError != null) 1.5.dp else 2.dp, border, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(ch, fontFamily = Mono, fontSize = 26.sp, color = Tok.Bone) }
            }
        }
        if (shownError != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Ico(R.drawable.ic_triangle_alert, tint = Tok.Fault, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(shownError!!, style = MaterialTheme.typography.bodyMedium, color = Tok.Fault)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        if (k.isBlank()) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            PhysicalKey(
                                k, onPress = {
                                    if (code.length < length && !waiting && !busy) {
                                        code += k
                                        shownError = null
                                    }
                                },
                                modifier = Modifier.weight(1f).height(keyHeight),
                            ) { Text(k, fontFamily = Mono, fontSize = 20.sp, color = Tok.Bone) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Cancel", onCancel, Modifier.weight(1f))
                PhysicalKey("Delete", onPress = {
                    code = code.dropLast(1)
                    shownError = null
                }, modifier = Modifier.weight(1f).height(keyHeight), repeat = true) { Ico(R.drawable.ic_delete) }
                PillButton(
                    actionLabel,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSubmit(code)
                    },
                    modifier = Modifier.weight(2f),
                    primary = true,
                    enabled = ready,
                    busy = busy,
                    height = keyHeight,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** A text field box: hairline border, yellow when focused. */
fun Modifier.fieldBox(focused: Boolean, radius: Dp = 12.dp, width: Dp = 1.dp): Modifier =
    border(if (focused) 2.dp else width, if (focused) Tok.Signal else Tok.Seam, RoundedCornerShape(radius))
