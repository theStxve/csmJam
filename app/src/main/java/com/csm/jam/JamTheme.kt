@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.csm.jam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens of the redesign: near-black ground, layered greys, one accent. */
object Jam {
    val Bg = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val Surface2 = Color(0xFF1C1C1F)
    val Surface3 = Color(0xFF26262A)
    val Track = Color(0xFF2A2A2E)
    val Line = Color(0x14FFFFFF)
    val Text = Color(0xFFF4F4F5)
    val Muted = Color(0xFFA1A1AA)
    val Faint = Color(0xFF71717A)
    val Accent = Color(0xFF1ED760)
    val OnAccent = Color(0xFF04140A)
    val AccentSoft = Color(0xFF1C2A20)
    val Warn = Color(0xFFFFB86B)
    val Danger = Color(0xFFFF8A8A)
    val DangerSoft = Color(0xFF2A1517)
    val CoDj = Color(0xFF64B5F6)

    private fun variable(res: Int, weight: Int) =
        Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    val Display = FontFamily(variable(R.font.spacegrotesk, 500), variable(R.font.spacegrotesk, 700))
    val Body = FontFamily(
        variable(R.font.inter, 400), variable(R.font.inter, 500),
        variable(R.font.inter, 600), variable(R.font.inter, 700)
    )
    val Mono = FontFamily(variable(R.font.jetbrainsmono, 500))

    val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.5).sp, color = Text)
    val Label = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.6.sp, color = Muted)
    val Number = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)
}

@Composable
fun JamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Jam.Accent,
            onPrimary = Jam.OnAccent,
            background = Jam.Bg,
            surface = Jam.Surface,
            surfaceVariant = Jam.Surface2,
            onSurface = Jam.Text,
            onSurfaceVariant = Jam.Muted,
            error = Jam.Danger
        ),
        typography = MaterialTheme.typography.let { t ->
            t.copy(
                bodyLarge = t.bodyLarge.copy(fontFamily = Jam.Body),
                bodyMedium = t.bodyMedium.copy(fontFamily = Jam.Body),
                bodySmall = t.bodySmall.copy(fontFamily = Jam.Body),
                labelLarge = t.labelLarge.copy(fontFamily = Jam.Body),
                titleLarge = t.titleLarge.copy(fontFamily = Jam.Display),
                headlineSmall = t.headlineSmall.copy(fontFamily = Jam.Display)
            )
        },
        content = content
    )
}

/** Small uppercase section header ("IN DER NÄHE", "WARTESCHLANGE · 6"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Jam.Label, modifier = modifier)
}

/** Big pill button – accent (primary) or outlined. */
@Composable
fun JamButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    height: Dp = 54.dp,
    danger: Boolean = false,
    icon: (@Composable () -> Unit)? = null
) {
    val bg = when {
        danger -> Jam.DangerSoft
        primary -> Jam.Accent
        else -> Jam.Surface
    }
    val fg = when {
        danger -> Jam.Danger
        primary -> Jam.OnAccent
        else -> Jam.Text
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.5f),
        contentColor = fg,
        border = if (!primary && !danger) BorderStroke(1.dp, Color(0x1FFFFFFF)) else null,
        modifier = modifier.height(height)
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Box(Modifier.size(8.dp))
            }
            Text(text, fontFamily = Jam.Body, fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/** Rounded dark card used for every group of controls. */
@Composable
fun JamCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Jam.Surface, contentColor = Jam.Text, modifier = modifier.fillMaxWidth()) {
        content()
    }
}

/** Segmented control: Aus / 3 s / 6 s / 10 s, tabs, … */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Jam.Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            SegmentItem(label, i == selected, mono, enabled) { onSelect(i) }
        }
    }
}

@Composable
private fun RowScope.SegmentItem(label: String, isSelected: Boolean, mono: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (isSelected) Jam.Surface3 else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (isSelected) Jam.Text else Jam.Muted,
            fontFamily = if (mono) Jam.Mono else Jam.Body,
            fontWeight = if (mono) FontWeight.Medium else FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

/** Small rounded label ("HOST", "Synchron", participant chips). */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = Jam.Text, background: Color = Jam.Surface2, bold: Boolean = false) {
    Text(
        text,
        color = color,
        fontFamily = Jam.Body,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        fontSize = if (bold) 11.sp else 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = if (bold) 10.dp else 12.dp, vertical = if (bold) 5.dp else 8.dp)
    )
}

/** Square icon button with a hairline border (header actions). */
@Composable
fun IconTile(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Jam.Surface,
        contentColor = Jam.Text,
        border = BorderStroke(1.dp, Jam.Line),
        modifier = modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Bottom sheet used everywhere: opens fully (not half-way), keeps its content above the
 * gesture/navigation bar and scrolls if it is taller than the screen.
 * [dismissible] = false keeps it open until the user decides (e.g. a join request).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(onDismissRequest: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    val state = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible || it != androidx.compose.material3.SheetValue.Hidden }
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        sheetState = state,
        containerColor = Jam.Surface,
        contentColor = Jam.Text
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
        ) { content() }
    }
}
