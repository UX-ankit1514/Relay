package app.relay.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.relay.data.AuthUser
import app.relay.design.ActionSpec
import app.relay.design.FloatingCard
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.PillSize
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.Space
import app.relay.design.TextLink
import app.relay.nav.ProjectTab
import app.relay.nav.Route
import app.relay.ui.AppServices
import app.relay.ui.LocalServices
import app.relay.ui.friendlyError
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Demo mode (local emulators only — `s.auth.demoMode`). Lets one presenter play every role:
 *  • a floating "View as" switcher that changes the signed-in persona and keeps you in context,
 *  • `?as=priya` links that open any page already signed in as that persona,
 *  • "Switch to Priya to review"-style nudges on the waiting states.
 * In production none of this renders: sign-in stays Google-only.
 */

data class DemoPersona(val key: String, val name: String, val role: String, val email: String)

// Emails are fixed: emulator data is keyed on them.
val DemoCast = listOf(
    DemoPersona("priya", "Priya", "client", "priya@example.com"),
    DemoPersona("rohan", "Rohan", "freelancer", "rohan@example.com"),
    DemoPersona("maya", "Maya", "replacement", "maya@example.com"),
)

fun demoPersona(key: String?): DemoPersona? = DemoCast.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
fun demoPersonaByEmail(email: String?): DemoPersona? = DemoCast.firstOrNull { it.email.equals(email?.trim(), ignoreCase = true) }
private fun demoPersonaFor(email: String?, name: String?): DemoPersona? =
    demoPersonaByEmail(email) ?: DemoCast.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

/** App-wide demo state. The switch runs in its own scope so it survives the screen being rebuilt for the new user. */
object DemoState {
    var switchingTo by mutableStateOf<DemoPersona?>(null)
    var error by mutableStateOf<String?>(null)
    internal val scope = MainScope()
}

/** Signs in as [to] (replacing the current account) and then opens [target] — or stays on the current page. */
fun demoSwitch(s: AppServices, to: DemoPersona, target: Route? = null, resolve: Boolean = target == null) {
    if (!s.auth.demoMode || DemoState.switchingTo != null) return
    DemoState.error = null
    val from = s.router.route.value
    DemoState.switchingTo = to
    DemoState.scope.launch {
        try {
            // Work out where this persona belongs while the current account can still read the project.
            val dest = target ?: if (resolve) demoTarget(s, from, to) else null
            s.auth.signInAsDemoPersona(to.name, to.email)
            if (dest != null && dest != from) s.router.navigate(dest, replace = true)
        } catch (t: Throwable) {
            DemoState.error = friendlyError(t)
        } finally {
            DemoState.switchingTo = null
        }
    }
}

/** Thrown when a persona has no place on the current project yet (e.g. Maya before a handoff exists). */
class DemoBlocked(message: String) : Exception(message)

/** The page [to] should land on when switching from [route]: same page where it makes sense, otherwise their side of it. */
suspend fun demoTarget(s: AppServices, route: Route, to: DemoPersona): Route? = runCatching {
    when (route) {
        is Route.Project -> {
            val p = s.repo.observeProject(route.projectId).first() ?: return@runCatching null
            val ownerEmail = p.members[p.ownerId]?.email
            val memberEmails = p.members.values.map { it.email.lowercase() }
            when {
                to.email.equals(ownerEmail, ignoreCase = true) -> route
                to.email.lowercase() in memberEmails ->
                    if (route.tab in listOf(ProjectTab.Review, ProjectTab.Payment, ProjectTab.Handoff)) Route.Project(p.id) else route
                p.activeHandoffId != null -> Route.Handoff(p.activeHandoffId)
                p.inviteToken != null && to.email.equals(p.inviteEmail, ignoreCase = true) -> Route.Invite(p.inviteToken)
                else -> throw DemoBlocked("${to.name} isn’t on this project yet. ${to.name} joins through ${if (to.key == "maya") "a published handoff link" else "an invite"}.")
            }
        }
        is Route.Handoff -> {
            val h = s.repo.observeHandoff(route.token).first() ?: return@runCatching null
            when {
                to.email.equals(h.replacementEmail, ignoreCase = true) -> route
                to.name.equals(h.ownerName, ignoreCase = true) -> Route.Project(h.projectId, ProjectTab.Handoff)
                h.replacementEmail == null && to.key == "maya" -> route
                else -> Route.Project(h.projectId)
            }
        }
        is Route.Invite, Route.Landing, is Route.NotFound -> route
        else -> Route.Landing // onboarding pages belong to whoever was creating the project
    }
}.getOrElse { if (it is DemoBlocked) throw it else null }

/** A demo-only next-step action ("Switch to Priya to review"). Null outside demo mode or when no persona matches. */
fun demoAction(s: AppServices, email: String?, name: String? = null, label: (DemoPersona) -> String, target: Route): ActionSpec? {
    if (!s.auth.demoMode) return null
    val p = demoPersonaFor(email, name) ?: return null
    return ActionSpec(label(p), { demoSwitch(s, p, target) })
}

/** The persona that isn't on the project yet — the natural "replacement" for a handoff link. */
fun demoOutsider(memberEmails: Collection<String>): DemoPersona? =
    DemoCast.lastOrNull { p -> memberEmails.none { it.equals(p.email, ignoreCase = true) } }

/** A shareable link that opens [route] already signed in as [p]. In the Demo version the link also turns the Demo version on. */
fun demoLink(s: AppServices, p: DemoPersona, route: Route): String =
    s.platform.origin() + route.path + (if ('?' in route.path) "&" else "?") + "as=" + p.key +
        (if (s.mode?.isDemo == true) "&mode=demo" else "")

/**
 * The presenter's bar, floating at the bottom of every page:
 *  • Original ⇄ Demo version switch (Original = real data and Google sign-in; Demo = the ready-made story),
 *  • "View as" persona switch (Demo version, or the local emulators),
 *  • in the Demo version: the three scenarios, a copyable persona link and Reset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoBar(user: AuthUser?, modifier: Modifier = Modifier) {
    val s = LocalServices.current
    val mode = s.mode
    val demoData = mode?.isDemo == true
    val personas = s.auth.demoMode && user != null
    if (mode == null && !personas) return
    var open by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied != null) { delay(2500); copied = null } }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(3500); confirmReset = false } }
    val current = demoPersonaByEmail(user?.email)
    val linkFor = if (current?.key == "priya") DemoCast[1] else DemoCast[0]
    val busy = DemoState.switchingTo != null

    FloatingCard(modifier.widthIn(max = 1040.dp)) {
        if (!open) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
                val who = current?.let { " · ${it.name} (${it.role})" } ?: user?.let { " · ${it.displayName}" } ?: ""
                RText((if (demoData) "Demo version" else "Original") + who, RelayTheme.type.metadata, color = RelayColors.textSecondary)
                TextLink("Show", { open = true }, small = true)
            }
            return@FloatingCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s8), itemVerticalAlignment = Alignment.CenterVertically) {
            if (mode != null) {
                PillButton("Original", { if (demoData) mode.switchTo(false) }, appearance = if (!demoData) PillAppearance.Filled else PillAppearance.Ghost, size = PillSize.Compact, enabled = !busy)
                PillButton("Demo version", { if (!demoData) mode.switchTo(true) }, appearance = if (demoData) PillAppearance.Filled else PillAppearance.Ghost, size = PillSize.Compact, enabled = !busy)
            }
            if (personas) {
                RText(if (mode != null) "   View as" else "Demo · view as", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                DemoCast.forEach { p ->
                    val selected = p.key == current?.key
                    PillButton(
                        "${p.name} · ${p.role}",
                        { if (!selected) demoSwitch(s, p) },
                        appearance = if (selected) PillAppearance.Filled else PillAppearance.Ghost,
                        size = PillSize.Compact,
                        enabled = !busy || selected,
                        loading = DemoState.switchingTo == p,
                    )
                }
            } else if (demoData) {
                // Demo version, signed out: one click back into the story.
                PillButton("Start as Priya", { demoSwitch(s, DemoCast[0], Route.Project(app.relay.demo.DemoIds.CLEARINVOICE)) }, appearance = PillAppearance.Ghost, size = PillSize.Compact, enabled = !busy)
            }
            TextLink("Hide", { open = false }, small = true)
        }
        if (demoData) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8), itemVerticalAlignment = Alignment.CenterVertically) {
                RText("Scenarios", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                app.relay.demo.DemoScenarios.forEach { sc ->
                    TextLink("${sc.number} · ${sc.title}", { demoSwitch(s, sc.persona, app.relay.demo.demoScenarioTarget(s, sc)) }, small = true, enabled = !busy)
                }
                if (personas) CopyPersonaLink(s, linkFor, copied) { copied = it }
                TextLink(if (confirmReset) "Click again to reset" else "Reset demo", {
                    if (confirmReset) mode.resetDemo() else confirmReset = true
                }, small = true)
            }
        } else if (personas) {
            CopyPersonaLink(s, linkFor, copied) { copied = it }
        }
        DemoState.error?.let { RText(it, RelayTheme.type.metadata, color = RelayColors.textSecondary) }
    }
}

@Composable
private fun CopyPersonaLink(s: AppServices, linkFor: DemoPersona, copied: String?, onCopied: (String) -> Unit) {
    TextLink(if (copied == linkFor.key) "Link copied" else "Copy ${linkFor.name}’s link", {
        DemoState.scope.launch {
            val route = s.router.route.value
            try {
                val target = demoTarget(s, route, linkFor) ?: route
                s.platform.copyToClipboard(demoLink(s, linkFor, target))
                onCopied(linkFor.key); DemoState.error = null
            } catch (t: DemoBlocked) { DemoState.error = t.message }
        }
    }, small = true)
}
