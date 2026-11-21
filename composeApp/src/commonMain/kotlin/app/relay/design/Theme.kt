package app.relay.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relay.resources.Res
import app.relay.resources.inter_medium
import app.relay.resources.inter_regular
import app.relay.resources.source_serif_4_italic
import app.relay.resources.source_serif_4_regular
import org.jetbrains.compose.resources.Font

/**
 * Steep design tokens, mirrored 1:1 from the Figma file "Buildthone"
 * (collections "Steep · 01 Primitives", "Steep · 02 Semantic", "Steep · 03 Typography").
 * Do not introduce colours outside this palette — status is expressed with words + glyphs.
 */
object Palette {
    val InkBlack = Color(0xFF17191C)
    val PaperWhite = Color(0xFFFFFFFF)
    val MistGray = Color(0xFFF2F2F3)
    val FogWhite = Color(0xFFFAFAFB)
    val SlateGray = Color(0xFF777B86)
    val AshGray = Color(0xFF979799)
    val SmokeGray = Color(0xFFA3A6AF)
    val BlushPeach = Color(0xFFFBE1D1)
    val SiennaBrown = Color(0xFF5D2A1A)
    val Hairline = Color(0xFFECECEC)
}

/** Semantic roles (Steep · 02 Semantic). */
object RelayColors {
    val surfaceCanvas = Palette.PaperWhite
    val surfaceCard = Palette.MistGray
    val surfaceSection = Palette.FogWhite
    val surfaceAccent = Palette.BlushPeach
    val surfaceElevated = Palette.PaperWhite

    val textPrimary = Palette.InkBlack
    val textSecondary = Palette.SlateGray
    val textTertiary = Palette.AshGray
    val textPlaceholder = Palette.SmokeGray
    val textOnAccent = Palette.SiennaBrown
    val textInverse = Palette.PaperWhite

    val actionPrimary = Palette.InkBlack
    val borderDefault = Palette.Hairline
    val borderStrong = Palette.InkBlack
    val chartStroke = Palette.SiennaBrown

    /** Overlay behind dialogs and side panels: ink at 32%. */
    val scrim = Palette.InkBlack.copy(alpha = 0.32f)
}

/** 4px base scale (Steep · 01 Primitives / space). */
object Space {
    val s2 = 2.dp
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val s28 = 28.dp
    val s32 = 32.dp
    val s40 = 40.dp
    val s64 = 64.dp
    val s80 = 80.dp
    val s96 = 96.dp
    val s120 = 120.dp
    val s160 = 160.dp
}

/** Radius roles (Steep · 02 Semantic / radius). */
object Radius {
    val card = 24.dp
    val smallCard = 16.dp
    val elevated = 20.dp
    val input = 16.dp
    val image = 12.dp
    val button = 999.dp
}

object Sizes {
    val control = 48.dp
    val controlCompact = 40.dp
    val topBar = 72.dp
    val tabs = 56.dp
    val rail = 360.dp
    val maxContent = 1200.dp
}

@Immutable
data class RelayTypography(
    val display: TextStyle,
    val displayItalic: TextStyle,
    val headingLarge: TextStyle,
    val heading: TextStyle,
    val headingSmall: TextStyle,
    val subheading: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val body: TextStyle,
    val ui: TextStyle,
    val uiMedium: TextStyle,
    val caption: TextStyle,
    val metadata: TextStyle,
    val metric: TextStyle,
)

val LocalRelayType = staticCompositionLocalOf<RelayTypography> { error("RelayTheme not set") }

object RelayTheme {
    val type: RelayTypography @Composable get() = LocalRelayType.current
}

private val trimNone = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, line: Double, tracking: Double = 0.0, weight: FontWeight = FontWeight.Normal, italic: Boolean = false) =
    TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = if (tracking == 0.0) TextUnit.Unspecified else tracking.sp,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        color = RelayColors.textPrimary,
        lineHeightStyle = trimNone,
    )

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val serif = FontFamily(
        Font(Res.font.source_serif_4_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.source_serif_4_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val sans = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
    )
    // Steep/Type/* — size / line-height / tracking exactly as in Figma.
    val type = RelayTypography(
        display = style(serif, 90, 117.0, -2.25),
        displayItalic = style(serif, 90, 117.0, -2.25, italic = true),
        headingLarge = style(serif, 64, 83.2, -0.96),
        heading = style(serif, 44, 57.2, -0.66),
        headingSmall = style(sans, 26, 30.68, -0.234),
        subheading = style(sans, 22, 33.0),
        bodyLarge = style(sans, 20, 27.0),
        bodyMedium = style(sans, 18, 27.0, -0.162),
        body = style(sans, 17, 22.95),
        ui = style(sans, 16, 24.0),
        uiMedium = style(sans, 16, 24.0, weight = FontWeight.Medium),
        caption = style(sans, 15, 22.5),
        metadata = style(sans, 14, 21.0),
        metric = style(sans, 20, 27.0, weight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalRelayType provides type, content = content)
}
