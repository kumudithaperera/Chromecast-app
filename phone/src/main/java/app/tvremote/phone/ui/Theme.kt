package app.tvremote.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tvremote.phone.R

/** Palette from the Doop canvas brief: a 1970s moulded-plastic pocket calculator. */
object Tok {
    val Ground = Color(0xFF121211) // moulded plastic
    val Panel = Color(0xFF1C1C1A) // case panel
    val Key = Color(0xFF2C2B29) // key cap
    val Seam = Color(0xFF3B3A37) // hairlines
    val Bone = Color(0xFFEDEAE2) // printed legend
    val Dim = Color(0xFFA5A29A) // worn legend
    val Signal = Color(0xFFF4BE1D) // the yellow key: one per screen
    val Ok = Color(0xFF7CC27F) // status lamp
    val Fault = Color(0xFFF0705A) // fault lamp
    val KeyHighlight = Color(0xFF3F3E3A)
    val KeyLip = Color(0xFF000000)
    val SignalLip = Color(0xFF7A5E06)
}

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: FontWeight) =
    Font(R.font.schibsted_grotesk, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val Grotesk = FontFamily(
    grotesk(FontWeight.Normal),
    grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold),
    grotesk(FontWeight.Bold),
)

/** For technical values only: IPs, codes, links. */
val Mono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.35f, tracking: Float = 0f) =
    TextStyle(fontFamily = Grotesk, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp, letterSpacing = tracking.sp)

private val Type = Typography(
    displaySmall = style(32, FontWeight.SemiBold, 1.12f, -0.4f),
    headlineSmall = style(24, FontWeight.SemiBold, 1.2f),
    titleLarge = style(20, FontWeight.SemiBold, 1.2f, -0.2f),
    titleMedium = style(17, FontWeight.SemiBold, 1.25f),
    titleSmall = style(14, FontWeight.SemiBold),
    bodyLarge = style(15),
    bodyMedium = style(14),
    bodySmall = style(13),
    labelLarge = style(15, FontWeight.SemiBold),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(11, FontWeight.Medium),
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    // Always the canvas palette: a remote is used in a dim room.
    val colors = darkColorScheme(
        primary = Tok.Signal,
        onPrimary = Tok.Ground,
        secondary = Tok.Bone,
        onSecondary = Tok.Ground,
        secondaryContainer = Tok.Key,
        onSecondaryContainer = Tok.Bone,
        background = Tok.Ground,
        onBackground = Tok.Bone,
        surface = Tok.Ground,
        onSurface = Tok.Bone,
        surfaceVariant = Tok.Panel,
        onSurfaceVariant = Tok.Dim,
        surfaceContainer = Tok.Panel,
        surfaceContainerHigh = Tok.Panel,
        surfaceContainerHighest = Tok.Key,
        surfaceContainerLow = Tok.Panel,
        error = Tok.Fault,
        onError = Tok.Ground,
        outline = Tok.Seam,
        outlineVariant = Tok.Seam,
    )
    MaterialTheme(colorScheme = colors, typography = Type, content = content)
}
