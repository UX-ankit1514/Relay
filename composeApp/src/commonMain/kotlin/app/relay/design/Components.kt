package app.relay.design

import androidx.compose.ui.focus.focusRequester

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─── Text ────────────────────────────────────────────────────────────────────

@Composable
fun RText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
    decoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(
            TextStyle(
                color = if (color == Color.Unspecified) style.color else color,
                textAlign = align,
                textDecoration = decoration,
            )
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CategoryLabel(text: String, modifier: Modifier = Modifier) =
    RText(text, RelayTheme.type.metadata, modifier, color = RelayColors.textTertiary)

// ─── Focus ring (visible, high-contrast; required by PRD §26) ────────────────

fun Modifier.focusRing(focused: Boolean, radius: Dp, inset: Dp = 3.dp): Modifier = drawBehind {
    if (focused) {
        val i = inset.toPx()
        drawRoundRect(
            color = RelayColors.borderStrong,
            topLeft = Offset(-i, -i),
            size = Size(size.width + 2 * i, size.height + 2 * i),
            cornerRadius = CornerRadius(radius.toPx() + i),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

// ─── Actions ─────────────────────────────────────────────────────────────────

enum class PillAppearance { Filled, Ghost }
enum class PillSize { Default, Compact }

/** Steep/Actions/Pill button — Filled | Ghost × Default (48) | Compact (40). */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    appearance: PillAppearance = PillAppearance.Filled,
    size: PillSize = PillSize.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = enabled && !loading
    val filled = appearance == PillAppearance.Filled
    val bg = when {
        filled && !enabled -> RelayColors.surfaceCard
        filled -> if (hovered && active) RelayColors.actionPrimary.copy(alpha = 0.86f) else RelayColors.actionPrimary
        hovered && active -> RelayColors.surfaceCard
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> RelayColors.textPlaceholder
        filled -> RelayColors.textInverse
        else -> RelayColors.textPrimary
    }
    val border = if (filled) null else BorderStroke(1.dp, if (enabled) RelayColors.borderStrong else RelayColors.borderDefault)
    Box(
        modifier = modifier
            .height(if (size == PillSize.Default) Sizes.control else Sizes.controlCompact)
            .focusRing(focused, Radius.button)
            .clip(RoundedCornerShape(Radius.button))
            .background(bg)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(Radius.button)) else Modifier)
            .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (size == PillSize.Default) Space.s20 else Space.s16),
        contentAlignment = Alignment.Center,
    ) {
        RText(if (loading) "$label…" else label, RelayTheme.type.ui, color = fg, maxLines = 1)
    }
}

/** Steep/Actions/Text link — arrow is part of the label. */
@Composable
fun TextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    RText(
        label,
        if (small) RelayTheme.type.metadata else RelayTheme.type.ui,
        modifier = modifier
            .focusRing(focused, 4.dp, 2.dp)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        color = if (enabled) RelayColors.textPrimary else RelayColors.textPlaceholder,
        decoration = if (hovered && enabled) TextDecoration.Underline else null,
        maxLines = 1,
    )
}

// ─── Status ──────────────────────────────────────────────────────────────────

enum class StatusKind { Done, Pending, Attention, NotStarted, Neutral }

/** Relay/Status — words + glyph, never colour alone. */
@Composable
fun StatusPill(label: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val (bg, fg, stroke) = when (kind) {
        StatusKind.Done -> Triple(RelayColors.actionPrimary, RelayColors.textInverse, null)
        StatusKind.Pending -> Triple(RelayColors.surfaceCanvas, RelayColors.textPrimary, RelayColors.borderDefault)
        StatusKind.Attention -> Triple(RelayColors.surfaceAccent, RelayColors.textOnAccent, null)
        StatusKind.NotStarted -> Triple(Color.Transparent, RelayColors.textSecondary, RelayColors.borderDefault)
        StatusKind.Neutral -> Triple(Color.Transparent, RelayColors.textPrimary, RelayColors.borderDefault)
    }
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = "Status: $label" }
            .clip(RoundedCornerShape(Radius.button))
            .background(bg)
            .then(if (stroke != null) Modifier.border(1.dp, stroke, RoundedCornerShape(Radius.button)) else Modifier)
            .padding(horizontal = Space.s12, vertical = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            StatusKind.Done -> RText("✓", RelayTheme.type.metadata, color = fg)
            StatusKind.Pending -> Dot(RelayColors.textSecondary)
            StatusKind.Attention -> Dot(RelayColors.chartStroke)
            StatusKind.NotStarted -> Box(Modifier.size(8.dp).border(1.dp, RelayColors.textPlaceholder, CircleShape))
            StatusKind.Neutral -> Unit
        }
        RText(label, RelayTheme.type.metadata, color = fg, maxLines = 1)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

// ─── Avatar ──────────────────────────────────────────────────────────────────

@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(RelayColors.surfaceCard), contentAlignment = Alignment.Center) {
        RText(initials.take(2).uppercase(), RelayTheme.type.uiMedium)
    }
}

// ─── Inputs ──────────────────────────────────────────────────────────────────

/** Relay/Text field — label, 48px input, helper or written error. */
@Composable
fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    prefix: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> RelayColors.chartStroke
        focused -> RelayColors.borderStrong
        else -> RelayColors.borderDefault
    }
    val borderWidth = if (error != null || focused) 2.dp else 1.dp
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        RText(label, RelayTheme.type.caption)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleLine) Modifier.height(Sizes.control) else Modifier.heightIn(min = 96.dp))
                .clip(RoundedCornerShape(Radius.input))
                .background(if (enabled) RelayColors.surfaceCanvas else RelayColors.surfaceSection)
                .border(borderWidth, borderColor, RoundedCornerShape(Radius.input))
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { focusRequester.requestFocus() }
                .padding(horizontal = Space.s16, vertical = if (singleLine) 0.dp else Space.s12),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            if (prefix != null) RText(prefix, RelayTheme.type.ui, color = RelayColors.textSecondary)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                interactionSource = interaction,
                cursorBrush = SolidColor(RelayColors.textPrimary),
                textStyle = RelayTheme.type.ui,
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    .then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp))
                    .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) RText(placeholder, RelayTheme.type.ui, color = RelayColors.textPlaceholder, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        when {
            error != null -> RText("! $error", RelayTheme.type.metadata, color = RelayColors.textOnAccent)
            helper != null -> RText(helper, RelayTheme.type.metadata, color = RelayColors.textSecondary)
        }
    }
}

/** Relay/Checkbox row. */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    error: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .focusRing(focused, Radius.smallCard)
            .clip(RoundedCornerShape(Radius.smallCard))
            .background(RelayColors.surfaceCanvas)
            .border(1.dp, if (checked) RelayColors.borderStrong else RelayColors.borderDefault, RoundedCornerShape(Radius.smallCard))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onCheckedChange)
            .padding(Space.s16),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (checked) RelayColors.actionPrimary else Color.Transparent)
                .then(if (!checked) Modifier.border(1.dp, RelayColors.textPlaceholder, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (checked) RText("✓", RelayTheme.type.metadata, color = RelayColors.textInverse) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            RText(label, RelayTheme.type.uiMedium)
            if (description != null) RText(description, RelayTheme.type.metadata, color = if (error) RelayColors.textOnAccent else RelayColors.textSecondary)
        }
    }
}

// ─── Cards ───────────────────────────────────────────────────────────────────

/** Steep/Cards/Neutral — flat mist surface, 24 radius. */
@Composable
fun NeutralCard(
    title: String,
    modifier: Modifier = Modifier,
    category: String? = null,
    body: String? = null,
    link: String? = null,
    onLink: (() -> Unit)? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .focusRing(focused, Radius.card)
            .clip(RoundedCornerShape(Radius.card))
            .background(RelayColors.surfaceCard)
            .then(if (selected) Modifier.border(2.dp, RelayColors.borderStrong, RoundedCornerShape(Radius.card)) else Modifier)
            .then(
                if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier
            )
            .padding(horizontal = Space.s20, vertical = Space.s32),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        if (category != null) CategoryLabel(category)
        RText(title, RelayTheme.type.bodyLarge)
        if (body != null) RText(body, RelayTheme.type.body)
        content?.invoke(this)
        if (link != null) {
            Spacer(Modifier.height(Space.s8))
            TextLink(link, onClick = { onLink?.invoke() ?: onClick?.invoke() })
        }
    }
}

/** Steep/Cards/Accent editorial — the single blush-peach highlight of a screen. */
@Composable
fun AccentCard(title: String, quote: String, attribution: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.card)).background(RelayColors.surfaceAccent).padding(Space.s32),
        verticalArrangement = Arrangement.spacedBy(Space.s16),
    ) {
        RText(title, RelayTheme.type.headingSmall, color = RelayColors.textOnAccent)
        RText(quote, RelayTheme.type.bodyMedium, color = RelayColors.textOnAccent)
        if (attribution != null) RText(attribution, RelayTheme.type.metadata, color = RelayColors.textOnAccent)
    }
}

/** Steep floating artifact surface (white, elevated). */
@Composable
fun FloatingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .shadow(14.dp, RoundedCornerShape(Radius.elevated), ambientColor = Palette.InkBlack.copy(alpha = 0.10f), spotColor = Palette.InkBlack.copy(alpha = 0.10f))
            .clip(RoundedCornerShape(Radius.elevated))
            .background(RelayColors.surfaceElevated)
            .border(1.dp, Palette.InkBlack.copy(alpha = 0.05f), RoundedCornerShape(Radius.elevated))
            .padding(horizontal = Space.s20, vertical = Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
        content = content,
    )
}

/** Steep/Data/Region table, reused as a two-column key/value panel. */
@Composable
fun KeyValueTable(title: String, headings: Pair<String, String>, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    FloatingCard(modifier) {
        RText(title, RelayTheme.type.ui)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RText(headings.first, RelayTheme.type.metadata, color = RelayColors.textTertiary)
            RText(headings.second, RelayTheme.type.metadata, color = RelayColors.textTertiary)
        }
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(top = Space.s8), horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
                RText(k, RelayTheme.type.metadata, Modifier.weight(1f))
                RText(v, RelayTheme.type.metadata, align = TextAlign.End)
            }
        }
    }
}

data class ListRowSpec(
    val title: String,
    val meta: String? = null,
    val muted: Boolean = false,
    val trailing: (@Composable RowScope.() -> Unit)? = null,
)

/** Mist list card with hairline separators (used for terms, files, history, evidence). */
@Composable
fun ListCard(rows: List<ListRowSpec>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(RelayColors.surfaceCard)
            .padding(horizontal = Space.s24, vertical = Space.s8)
    ) {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth()
                    .then(if (i < rows.lastIndex) Modifier.drawBehind {
                        drawLine(RelayColors.borderDefault, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    } else Modifier)
                    .padding(vertical = Space.s16),
                horizontalArrangement = Arrangement.spacedBy(Space.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    RText(r.title, RelayTheme.type.uiMedium, color = if (r.muted) RelayColors.textSecondary else RelayColors.textPrimary)
                    if (r.meta != null) RText(r.meta, RelayTheme.type.metadata, color = RelayColors.textSecondary)
                }
                r.trailing?.invoke(this)
            }
        }
    }
}

@Composable
fun Amount(value: String, muted: Boolean = false, strike: Boolean = false) =
    RText(value, RelayTheme.type.metric, color = if (muted) RelayColors.textSecondary else RelayColors.textPrimary, decoration = if (strike) TextDecoration.LineThrough else null)

// ─── Page furniture ──────────────────────────────────────────────────────────

@Composable
fun TitleBlock(category: String?, title: String, subtitle: String? = null, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        if (category != null) CategoryLabel(category)
        RText(title, if (large) RelayTheme.type.headingLarge else RelayTheme.type.heading)
        if (subtitle != null) RText(subtitle, RelayTheme.type.bodyLarge, color = RelayColors.textSecondary)
    }
}

@Composable
fun SectionHeader(title: String, meta: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        RText(title, RelayTheme.type.headingSmall, Modifier.weight(1f, fill = false))
        if (trailing != null) trailing() else if (meta != null) RText(meta, RelayTheme.type.metadata, color = RelayColors.textSecondary)
    }
}

@Composable
fun Section(title: String, meta: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
        SectionHeader(title, meta, trailing = trailing)
        content()
    }
}

/** A status pill followed by explanatory metadata (confirmations, errors, notices). */
@Composable
fun StatusLine(label: String, kind: StatusKind, meta: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, kind)
        if (meta != null) RText(meta, RelayTheme.type.metadata, Modifier.weight(1f), color = RelayColors.textSecondary)
        if (trailing != null) trailing()
    }
}

/** Bordered notice row (send errors, unavailable states). */
@Composable
fun NoticeRow(label: String, kind: StatusKind, meta: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.smallCard)).border(1.dp, RelayColors.borderDefault, RoundedCornerShape(Radius.smallCard))
            .padding(horizontal = Space.s16, vertical = Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(label, kind)
        RText(meta, RelayTheme.type.metadata, Modifier.weight(1f), color = RelayColors.textSecondary)
        action?.invoke()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(RelayColors.borderDefault))

@Composable
fun OrDivider(text: String = "or") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
        HairlineDivider(Modifier.weight(1f))
        RText(text, RelayTheme.type.metadata, color = RelayColors.textSecondary)
        HairlineDivider(Modifier.weight(1f))
    }
}

@Composable
fun KeyFacts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).border(1.dp, RelayColors.borderDefault, RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.s24, vertical = Space.s20),
        horizontalArrangement = Arrangement.spacedBy(Space.s24),
    ) {
        facts.forEach { (k, v) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                RText(k, RelayTheme.type.metadata, color = RelayColors.textSecondary)
                RText(v, RelayTheme.type.ui)
            }
        }
    }
}

@Composable
fun Spinnerless(text: String) = RText(text, RelayTheme.type.metadata, color = RelayColors.textSecondary)

@Composable
fun FixedWidth(width: Dp, content: @Composable () -> Unit) = Box(Modifier.width(width).defaultMinSize(minWidth = width)) { content() }
