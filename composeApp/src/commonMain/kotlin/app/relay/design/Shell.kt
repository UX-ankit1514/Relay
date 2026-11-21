package app.relay.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

data class ActionSpec(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val loading: Boolean = false)

/** IA §3B top bar: wordmark · project + status chip · role badge · avatar. */
@Composable
fun TopBar(
    onLogo: () -> Unit,
    projectName: String? = null,
    projectStatus: Pair<String, StatusKind>? = null,
    role: String? = null,
    avatarInitials: String? = null,
    meta: String? = null,
    onSignOut: (() -> Unit)? = null,
    metaAction: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().zIndex(5f)) {
        Row(
            Modifier.fillMaxWidth().height(Sizes.topBar).background(RelayColors.surfaceCanvas)
                .drawBehind { drawLine(RelayColors.borderDefault, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(horizontal = Space.s40),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            RText(
                "Relay", RelayTheme.type.headingSmall,
                Modifier.focusRing(focused, 6.dp, 4.dp).pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Relay home", onClick = onLogo)
            )
            if (projectName != null) {
                Row(
                    Modifier.drawBehind { drawLine(RelayColors.borderDefault, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                        .padding(start = Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s12),
                ) {
                    RText(projectName, RelayTheme.type.uiMedium, maxLines = 1)
                    if (projectStatus != null) StatusPill(projectStatus.first, projectStatus.second)
                }
            }
            Spacer(Modifier.weight(1f))
            if (meta != null) {
                if (metaAction != null) TextLink(meta, metaAction, small = true)
                else RText(meta, RelayTheme.type.metadata, color = RelayColors.textSecondary)
            }
            if (role != null) StatusPill(role, StatusKind.Neutral)
            if (avatarInitials != null) {
                val ai = remember { MutableInteractionSource() }
                val af by ai.collectIsFocusedAsState()
                Box(
                    Modifier.focusRing(af, 24.dp).pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = ai, indication = null, role = Role.Button, onClickLabel = "Account menu") { menuOpen = !menuOpen }
                ) { Avatar(avatarInitials) }
            }
        }
        if (menuOpen && onSignOut != null) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = Sizes.topBar - 4.dp, end = Space.s40).width(220.dp)
                    .shadow(12.dp, RoundedCornerShape(Radius.smallCard), ambientColor = Palette.InkBlack.copy(alpha = 0.08f), spotColor = Palette.InkBlack.copy(alpha = 0.12f))
                    .clip(RoundedCornerShape(Radius.smallCard)).background(RelayColors.surfaceElevated).padding(Space.s8)
            ) {
                MenuItem("Sign out") { menuOpen = false; onSignOut() }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (hovered || emphasized) RelayColors.surfaceCard else RelayColors.surfaceElevated)
            .pointerHoverIcon(PointerIcon.Hand).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s12, vertical = 10.dp)
    ) { RText(label, if (emphasized) RelayTheme.type.uiMedium else RelayTheme.type.ui) }
}

/** Tab row with a 2px ink indicator under the selected tab. */
@Composable
fun TabBar(tabs: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, inset: Boolean = true) {
    Row(
        modifier.fillMaxWidth().height(if (inset) Sizes.tabs else 44.dp)
            .drawBehind { drawLine(RelayColors.borderDefault, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = if (inset) Space.s40 else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s28),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            val interaction = remember(tab) { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Column(
                Modifier.fillMaxHeight().width(androidx.compose.foundation.layout.IntrinsicSize.Max).focusRing(focused, 6.dp, 0.dp).pointerHoverIcon(PointerIcon.Hand)
                    .semantics { this.selected = isSel }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(tab) },
                verticalArrangement = Arrangement.Bottom,
            ) {
                RText(tab, if (isSel) RelayTheme.type.uiMedium else RelayTheme.type.ui, color = if (isSel) RelayColors.textPrimary else RelayColors.textSecondary, maxLines = 1)
                Spacer(Modifier.height(Space.s12))
                Box(Modifier.matchTextWidth().height(2.dp).background(if (isSel) RelayColors.actionPrimary else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
}

private fun Modifier.matchTextWidth(): Modifier = this.fillMaxWidth()

/** Page = scrollable column + overlay layer for dialogs/side panels. */
@Composable
fun Page(
    header: @Composable ColumnScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(RelayColors.surfaceCanvas)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            header()
            content()
        }
        overlay()
    }
}

/** IA §3B: main column + 360px right rail (Next step + context). Stacks on narrow screens, Next step first. */
@Composable
fun WorkspaceLayout(
    main: @Composable ColumnScope.() -> Unit,
    rail: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 960.dp
        if (narrow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.s16, vertical = Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s32)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s24), content = rail)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s32), content = main)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s40, end = Space.s40, top = Space.s40, bottom = Space.s80),
                horizontalArrangement = Arrangement.spacedBy(Space.s40),
            ) {
                Column(Modifier.weight(1f).widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(Space.s32), content = main)
                Column(Modifier.width(Sizes.rail), verticalArrangement = Arrangement.spacedBy(Space.s24), content = rail)
            }
        }
    }
}

/** Next step card: the ONE main action for this person right now (IA §3B). */
@Composable
fun NextStepPanel(title: String, body: String, attribution: String? = null, primary: ActionSpec? = null, secondary: List<ActionSpec> = emptyList()) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        CategoryLabel("Next step")
        AccentCard(title, body, attribution, Modifier.fillMaxWidth())
        if (primary != null) PillButton(primary.label, primary.onClick, Modifier.fillMaxWidth(), enabled = primary.enabled, loading = primary.loading)
        secondary.forEach { PillButton(it.label, it.onClick, Modifier.fillMaxWidth(), appearance = PillAppearance.Ghost, enabled = it.enabled, loading = it.loading) }
    }
}

@Composable
fun ContextPanel(label: String, title: String, headings: Pair<String, String>, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        CategoryLabel(label)
        KeyValueTable(title, headings, rows, Modifier.fillMaxWidth())
    }
}

/** Centered single-message layout (expired links, access denied, sign-in). */
@Composable
fun CenteredColumn(width: androidx.compose.ui.unit.Dp = 560.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.s16, vertical = Space.s96), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = width).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s24), content = content)
    }
}

/** Window-level layer so dialogs always cover the viewport, wherever they are declared. */
private val WindowOrigin = object : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ) = androidx.compose.ui.unit.IntOffset.Zero
}

/** Relay/Modal over an ink scrim. Escape or Cancel closes it. */
@Composable
fun RelayDialog(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    primary: ActionSpec,
    secondary: ActionSpec? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    androidx.compose.ui.window.Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.fillMaxSize().background(RelayColors.scrim)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.padding(top = 120.dp, start = Space.s16, end = Space.s16).widthIn(max = 560.dp).fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(Radius.card), ambientColor = Palette.InkBlack.copy(alpha = 0.06f), spotColor = Palette.InkBlack.copy(alpha = 0.12f))
                    .clip(RoundedCornerShape(Radius.card)).background(RelayColors.surfaceElevated)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(Space.s32),
                verticalArrangement = Arrangement.spacedBy(Space.s24),
            ) {
                RText(title, RelayTheme.type.headingSmall)
                if (body != null) RText(body, RelayTheme.type.ui, color = RelayColors.textSecondary)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8, Alignment.End)) {
                    val sec = secondary ?: ActionSpec("Cancel", onDismiss)
                    PillButton(sec.label, sec.onClick, appearance = PillAppearance.Ghost, enabled = sec.enabled)
                    PillButton(primary.label, primary.onClick, enabled = primary.enabled, loading = primary.loading)
                }
            }
        }
    }
}

/** Right-hand side panel (milestone detail) over an ink scrim. */
@Composable
fun SidePanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().zIndex(20f).background(RelayColors.scrim)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth()
                .background(RelayColors.surfaceElevated)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(Space.s32),
            verticalArrangement = Arrangement.spacedBy(Space.s24),
            content = content,
        )
    }
}

@Composable
fun MistPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(RelayColors.surfaceCard).padding(Space.s24),
        verticalArrangement = Arrangement.spacedBy(Space.s16),
        content = content,
    )
}

@Composable
fun OutlinePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.smallCard)).border(1.dp, RelayColors.borderDefault, RoundedCornerShape(Radius.smallCard)).padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
        content = content,
    )
}
