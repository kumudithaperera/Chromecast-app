package app.tvremote.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tvremote.phone.data.formatAgo
import app.tvremote.protocol.AdBlockState
import app.tvremote.protocol.AdLevel
import app.tvremote.protocol.AdOption
import app.tvremote.protocol.Command
import java.text.NumberFormat

/**
 * The TV browser's ad blocking settings. Everything shown comes from the TV, which keeps the settings;
 * each control sends the new value and the sheet changes when the TV confirms it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockSheet(state: AdBlockState, send: (Command) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tok.Panel,
        scrimColor = Tok.Ground.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            Text("Ad blocking", style = MaterialTheme.typography.headlineSmall, color = Tok.Bone)
            Spacer(Modifier.height(6.dp))
            Text(summary(state), style = MaterialTheme.typography.bodySmall, color = Tok.Dim)

            Spacer(Modifier.height(20.dp))
            SectionLabel("Level")
            Spacer(Modifier.height(8.dp))
            Segmented(
                options = listOf("Off", "Basic", "Standard", "Strict"),
                selected = state.level.ordinal,
                onSelect = { i -> AdLevel.entries.getOrNull(i)?.let { if (it != state.level) send(Command.AdLevelSet(it)) } },
            )
            Spacer(Modifier.height(8.dp))
            Text(levelText(state.level), style = MaterialTheme.typography.bodySmall, color = Tok.Dim)

            val on = state.level != AdLevel.OFF
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                "Hide ad spaces", "Removes the empty boxes and banners ads leave on the page.",
                state.hideElements, enabled = on,
            ) { send(Command.AdOptionSet(AdOption.HIDE_ELEMENTS, it)) }
            ToggleRow(
                "Anti-adblock fixes", "Stops pages from spotting the blocker, and from opening ad windows when you click.",
                state.scriptlets, enabled = on,
            ) { send(Command.AdOptionSet(AdOption.SCRIPTLETS, it)) }
            ToggleRow(
                "Site helpers", "Rejects cookie banners and presses \"Skip intro\" and \"Skip ad\".",
                state.siteHelpers, enabled = true,
            ) { send(Command.AdOptionSet(AdOption.SITE_HELPERS, it)) }

            if (state.site.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("This site")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.site, style = MaterialTheme.typography.bodyLarge, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (state.siteAllowed) "Ads allowed here" else "Ads blocked here",
                            style = MaterialTheme.typography.bodySmall, color = Tok.Dim,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PillButton(
                        if (state.siteAllowed) "Block ads" else "Allow ads",
                        { send(Command.AdSite(state.site, !state.siteAllowed)) },
                        height = 48.dp,
                    )
                }
            }

            if (state.allowedSites.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Ads allowed on")
                Spacer(Modifier.height(4.dp))
                state.allowedSites.forEach { site ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(site, style = MaterialTheme.typography.bodyLarge, color = Tok.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextAction("Remove", { send(Command.AdSite(site, false)) })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Lists")
            Spacer(Modifier.height(8.dp))
            val lists = state.level >= AdLevel.STANDARD
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listsText(state, lists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.updateFailed && !state.updating) Tok.Fault else Tok.Dim,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                PillButton("Update now", { send(Command.AdUpdate) }, enabled = lists, busy = state.updating, primary = lists, height = 48.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "EasyList, EasyPrivacy, uBlock Origin's lists and HaGeZi's lists, downloaded by the TV once a week.",
                style = MaterialTheme.typography.bodySmall, color = Tok.Dim,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Tok.Bone else Tok.Dim)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Tok.Dim)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tok.Ground, checkedTrackColor = Tok.Bone, checkedBorderColor = Tok.Bone,
                uncheckedThumbColor = Tok.Dim, uncheckedTrackColor = Tok.Key, uncheckedBorderColor = Tok.Seam,
                disabledCheckedTrackColor = Tok.Seam, disabledCheckedThumbColor = Tok.Panel,
                disabledUncheckedTrackColor = Tok.Key, disabledUncheckedThumbColor = Tok.Seam,
            ),
        )
    }
}

private fun summary(s: AdBlockState): String {
    if (s.level == AdLevel.OFF) return "Off. Nothing is blocked."
    val n = NumberFormat.getIntegerInstance()
    val page = when {
        s.site.isEmpty() -> null
        s.siteAllowed -> "Ads allowed on this site"
        else -> "${n.format(s.blockedOnPage)} blocked on this page"
    }
    return listOfNotNull(page, "${n.format(s.rules)} rules").joinToString(" · ")
}

private fun levelText(level: AdLevel): String = when (level) {
    AdLevel.OFF -> "Nothing is blocked or hidden."
    AdLevel.BASIC -> "A small built-in list of ad and pop-up networks."
    AdLevel.STANDARD -> "Ads, trackers and pop-ups, from the same lists uBlock Origin uses."
    AdLevel.STRICT -> "Also a larger list of ad hosts. Some video players may stop loading."
}

private fun listsText(s: AdBlockState, lists: Boolean): String = when {
    !lists -> "The basic level uses only the built-in list."
    s.updating -> "Downloading…"
    s.updateFailed && s.updatedAt <= 0L -> "Download failed. Only the built-in list is in use."
    s.updateFailed -> "Last update failed. Using the lists from ${formatAgo(s.updatedAt, System.currentTimeMillis())}."
    s.updatedAt <= 0L -> "Not downloaded yet."
    else -> "Updated ${formatAgo(s.updatedAt, System.currentTimeMillis())}."
}
