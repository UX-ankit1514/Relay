package app.relay.ui.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.relay.data.rupees
import app.relay.design.ActionSpec
import app.relay.design.Amount
import app.relay.design.Avatar
import app.relay.design.CategoryLabel
import app.relay.design.ContextPanel
import app.relay.design.ListCard
import app.relay.design.ListRowSpec
import app.relay.design.MistPanel
import app.relay.design.NeutralCard
import app.relay.design.NextStepPanel
import app.relay.design.Page
import app.relay.design.PillButton
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.Section
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.StatusPill
import app.relay.design.TitleBlock
import app.relay.design.TopBar
import app.relay.design.WorkspaceLayout
import app.relay.model.Invite
import app.relay.nav.Route
import app.relay.ui.ErrorText
import app.relay.ui.LoadingPage
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.MessagePage
import app.relay.ui.friendlyError
import app.relay.ui.initialsOf
import app.relay.ui.rememberAction

/** F2 · Invite link — the freelancer sees the project and terms first, and signs up only to accept. */
@Composable
fun InviteScreen(token: String) {
    val s = LocalServices.current
    val user = LocalUser.current
    var invite by remember(token) { mutableStateOf<Invite?>(null) }
    var loaded by remember(token) { mutableStateOf(false) }
    var loadError by remember(token) { mutableStateOf<String?>(null) }
    val action = rememberAction()
    LaunchedEffect(token, user?.uid) {
        try { invite = s.repo.getInvite(token) } catch (t: Throwable) { loadError = friendlyError(t) }
        loaded = true
    }
    if (!loaded) { LoadingPage("Opening invite…"); return }
    val inv = invite
    val home = { s.router.navigate(Route.Landing) }
    when {
        inv == null || loadError != null -> { MessagePage("Link unavailable" to StatusKind.NotStarted, "This invite isn’t available", "The link may be mistyped, revoked or already used. Ask the client for a new link.", "What is Relay?" to home); return }
        inv.status == "accepted" && user != null && inv.acceptedBy == user.uid -> { LaunchedEffect(inv.projectId) { s.router.navigate(Route.Project(inv.projectId), replace = true) }; LoadingPage(); return }
        inv.status != "pending" -> { MessagePage("Link unavailable" to StatusKind.NotStarted, "This invite was already used", "Ask ${inv.ownerName} for a new link if you still need access.", "What is Relay?" to home); return }
        inv.expiresAt < s.platform.now() -> { MessagePage("Link unavailable" to StatusKind.NotStarted, "This invite has expired", "Invite links last 7 days to keep projects private. Ask ${inv.ownerName} for a new link — the project and its proposed terms are waiting for you.", "What is Relay?" to home, meta = "Expired ${s.platform.formatDateTime(inv.expiresAt)}"); return }
    }
    inv!!
    val accept = {
        action.run {
            if (LocalUserHolder.current == null) s.auth.signInWithGoogle()
            val pid = s.repo.acceptInvite(token)
            s.router.navigate(Route.Project(pid), replace = true)
        }
    }
    LocalUserHolder.current = user
    Page(header = {
        TopBar(onLogo = home, projectName = inv.projectName, projectStatus = "Waiting for your acceptance" to StatusKind.Pending, role = "Invited freelancer", avatarInitials = user?.let { initialsOf(it.displayName) })
    }) {
        WorkspaceLayout(
            main = {
                TitleBlock("Invited by ${inv.ownerName} · ${s.platform.formatDate(inv.createdAt)}", inv.projectName, "Goal: ${inv.projectGoal}")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(initialsOf(inv.ownerName))
                    Column { RText(inv.ownerName, RelayTheme.type.uiMedium); RText("Client · project owner", RelayTheme.type.metadata, color = RelayColors.textSecondary) }
                }
                NeutralCard("Relay keeps your agreed scope and approvals on record.", Modifier.fillMaxWidth(), category = "Why Relay",
                    body = "Once you accept, these terms are saved as Version ${inv.agreementVersion} with a timestamp. Any later change to scope or fees needs your acceptance again — nothing is silently rewritten.")
                Section("Proposed terms", trailing = { StatusPill("Draft v${inv.agreementVersion} · proposed by ${inv.ownerName}", StatusKind.Pending) }) {
                    ListCard(inv.terms.mapIndexed { i, t -> ListRowSpec("${i + 1} · ${t.title}", "${t.deliverable} · ${t.revisionLimit} revisions") { Amount(rupees(t.amountMinor)) } } +
                        ListRowSpec("Total", "Each milestone is paid after ${inv.ownerName} approves it") { Amount(rupees(inv.totalMinor)) })
                }
            },
            rail = {
                if (user == null) {
                    NextStepPanel("Review and accept", "Accepting creates your Relay account with Google and saves these terms as Version ${inv.agreementVersion}.", "No payment is taken from you",
                        ActionSpec("Continue with Google & accept", { accept() }, loading = action.running))
                } else {
                    NextStepPanel("Review and accept", "You’re signed in as ${user.email}. Accepting saves these terms as Version ${inv.agreementVersion} with a timestamp, and ${inv.ownerName} is notified.", "No payment is taken from you",
                        ActionSpec("Accept terms", { accept() }, loading = action.running))
                }
                ErrorText(action.error)
                if (user == null) app.relay.ui.auth.DemoPersonas()
                ContextPanel("Agreement", "At a glance", "Item" to "Detail", listOf("Proposed by" to "${inv.ownerName} · ${s.platform.formatDate(inv.createdAt)}", "Version" to "Draft v${inv.agreementVersion}", "Milestones" to "${inv.terms.size}", "Total" to rupees(inv.totalMinor), "Your first milestone" to (inv.terms.firstOrNull()?.title ?: "—")))
            },
        )
    }
}

/** Tracks the signed-in user for callbacks that run after a Google popup. */
private object LocalUserHolder { var current: app.relay.data.AuthUser? = null }

@Composable
private fun Unused() = MistPanel { CategoryLabel(""); PillButton("", {}) }
