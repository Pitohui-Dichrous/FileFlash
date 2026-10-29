package app.fileflash

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

internal val AcidDisplay = FontFamily(Font(R.font.anton_regular))
internal val AcidMono = FontFamily.Monospace
internal val AcidSquare = RoundedCornerShape(0.dp)
internal val AcidLime = Color(0xFFD7FF00)
internal val AcidElectric = Color(0xFF244CFF)

internal data class AcidPalette(val dark: Boolean, val canvas: Color, val surface: Color,
    val ink: Color, val muted: Color, val rule: Color, val plane: Color, val planeInk: Color,
    val action: Color, val actionInk: Color, val error: Color)

private val LocalAcid = staticCompositionLocalOf { AcidPalette(false, Color(0xFFF0F0F0), Color.White,
    Color(0xFF111111), Color(0xFF555555), Color(0xFFBDBDBD), AcidLime, Color.Black,
    Color.Black, AcidLime, Color(0xFFB22028)) }
internal val acid: AcidPalette @Composable get() = LocalAcid.current

@Composable internal fun AcidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) AcidPalette(true, Color(0xFF0B0B0B), Color(0xFF191919),
        Color(0xFFF3F3F3), Color(0xFFB6B6B6), Color(0xFF555555), Color.Black, AcidLime,
        AcidLime, Color.Black, Color(0xFFFF8B91)) else LocalAcid.current
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(primary = palette.action, onPrimary = palette.actionInk,
        primaryContainer = palette.plane, onPrimaryContainer = palette.planeInk,
        secondary = palette.ink, onSecondary = palette.canvas,
        secondaryContainer = palette.surface, onSecondaryContainer = palette.ink,
        tertiary = AcidElectric, onTertiary = Color.White,
        background = palette.canvas, onBackground = palette.ink,
        surface = palette.surface, onSurface = palette.ink, surfaceVariant = palette.canvas,
        onSurfaceVariant = palette.muted, surfaceTint = palette.surface,
        surfaceContainer = palette.surface, surfaceContainerHigh = palette.surface,
        surfaceContainerHighest = palette.surface, surfaceContainerLow = palette.surface,
        surfaceContainerLowest = palette.canvas, outline = palette.rule, outlineVariant = palette.rule,
        error = palette.error, onError = if (dark) Color.Black else Color.White)
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAcid provides palette) {
        MaterialTheme(colorScheme = colors,
            typography = Typography(
                bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
                labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 25.sp),
                titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp)),
            shapes = Shapes(AcidSquare, AcidSquare, AcidSquare, AcidSquare, AcidSquare), content = content)
    }
}

@Composable internal fun AcidIcon(resource: Int, description: String? = null,
    modifier: Modifier = Modifier.size(22.dp), tint: Color = LocalContentColor.current) {
    Icon(painterResource(resource), description, modifier, tint)
}

@Composable internal fun AcidActionLabel(text: String, icon: Int, large: Boolean = false) {
    AcidIcon(icon)
    Spacer(Modifier.width(10.dp))
    Text(text, fontSize = if (large) 20.sp else 15.sp, fontWeight = FontWeight.Bold)
}

@Composable internal fun AcidMasthead(onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .heightIn(min = 54.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("FF", fontFamily = AcidDisplay, fontSize = 28.sp, color = acid.ink)
        Text(" / FILEFLASH", fontFamily = AcidMono, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), color = acid.ink)
        IconButton(onClick = onInfo, modifier = Modifier.size(48.dp)) { AcidIcon(R.drawable.ic_acid_info, "帮助") }
    }
    HorizontalDivider(color = acid.ink, thickness = 1.dp)
}

@Composable internal fun AcidNavigation(page: Int, select: (Int) -> Unit) {
    val titles = listOf("共享", "连接", "文字", "记录")
    val icons = listOf(R.drawable.ic_acid_radio_tower, R.drawable.ic_acid_scan_line, R.drawable.ic_acid_type, R.drawable.ic_acid_history)
    Column(Modifier.background(acid.surface).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        HorizontalDivider(color = acid.ink)
        Row(Modifier.fillMaxWidth()) {
            titles.forEachIndexed { index, label ->
                val active = index == page
                val ink = if (active) acid.actionInk else acid.muted
                Column(Modifier.weight(1f).background(if (active) acid.action else acid.surface)
                    .selectable(active, role = Role.Tab, onClick = { select(index) }).heightIn(min = 78.dp)
                    .padding(vertical = 12.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AcidIcon(icons[index], tint = ink)
                    Text(label, color = ink, fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Actual text measurement preserves the poster rhythm without stretching CJK glyphs. */
@Composable internal fun AcidDisplayLine(value: String, modifier: Modifier = Modifier,
    color: Color = acid.planeInk, maximum: Int = 100, disrupted: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val plane = acid.plane
    val warning = acid.error
    BoxWithConstraints(modifier) {
        val available = with(density) { maxWidth.toPx() }
        var size = maximum
        while (size > 28 && measurer.measure(value, TextStyle(fontFamily = AcidDisplay, fontSize = size.sp,
                letterSpacing = (-1).sp), maxLines = 1).size.width > available) size--
        Text(value, style = TextStyle(fontFamily = AcidDisplay, fontSize = size.sp,
            lineHeight = (size * .98f).sp, letterSpacing = (-1).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
            color = color, maxLines = 1, softWrap = false,
            modifier = Modifier.drawWithContent {
                drawContent()
                // Static, narrow signal breaks affect display lettering only, never reading text.
                if (disrupted) {
                    listOf(.34f to 3.dp.toPx(), .68f to (-2).dp.toPx()).forEach { (fraction, shift) ->
                        val y = this.size.height * fraction
                        clipRect(top = y, bottom = y + 2.dp.toPx()) {
                            drawRect(plane)
                            translate(left = shift) { this@drawWithContent.drawContent() }
                        }
                    }
                    drawRect(warning, Offset(this.size.width * .76f, this.size.height * .34f), Size(12.dp.toPx(), 1.dp.toPx()))
                }
            })
    }
}

@Composable internal fun AcidPoster(index: String, title: String, top: String, bottom: String,
    detail: String, compact: Boolean = false, disrupted: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(acid.canvas)
        .clip(AbsoluteCutCornerShape(bottomRight = 26.dp)).background(acid.plane)
        .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LOCAL / $index", fontFamily = AcidMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = acid.planeInk)
            Spacer(Modifier.weight(1f))
            Text(detail, color = if (disrupted) acid.error else acid.planeInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp))
        }
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        Layout(content = {
            Row(verticalAlignment = Alignment.Top) {
                AcidDisplayLine(top, Modifier.weight(1f), maximum = if (compact) 74 else 104, disrupted = disrupted)
                Box(Modifier.padding(start = 12.dp, top = 9.dp).size(if (compact) 45.dp else 58.dp)
                    .background(if (disrupted) acid.error else AcidElectric), contentAlignment = Alignment.Center) {
                    Text(index, fontFamily = AcidDisplay, fontSize = if (compact) 27.sp else 36.sp, color = if (disrupted && acid.dark) Color.Black else Color.White)
                }
            }
            AcidDisplayLine(bottom, Modifier.fillMaxWidth().padding(start = if (compact) 42.dp else 28.dp), maximum = if (compact) 74 else 104, disrupted = disrupted)
        }) { measurables, constraints ->
            val lines = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            // Anton carries generous internal leading; close the two display rows proportionally.
            val overlap = (minOf(lines[0].height, lines[1].height) * .20f).toInt()
            layout(lines.maxOf { it.width }, lines.sumOf { it.height } - overlap) {
                lines[0].placeRelative(0, 0)
                lines[1].placeRelative(0, lines[0].height - overlap)
            }
        }
        Spacer(Modifier.height(13.dp))
        HorizontalDivider(color = if (disrupted) acid.error else acid.planeInk, thickness = 2.dp)
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = if (compact) 24.sp else 28.sp, fontWeight = FontWeight.Black, color = acid.planeInk)
            Spacer(Modifier.weight(1f))
            Text("LAN / $index", fontFamily = AcidMono, fontSize = 10.sp, color = acid.planeInk)
        }
    }
}
