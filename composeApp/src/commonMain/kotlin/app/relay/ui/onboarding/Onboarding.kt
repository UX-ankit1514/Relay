package app.relay.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.relay.data.formatInr
import app.relay.data.parseRupeesToPaise
import app.relay.data.rupees
import app.relay.design.CategoryLabel
import app.relay.design.FloatingCard
import app.relay.design.KeyValueTable
import app.relay.design.MistPanel
import app.relay.design.NeutralCard
import app.relay.design.OrDivider
import app.relay.design.Page
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTextField
import app.relay.design.RelayTheme
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.TextLink
import app.relay.design.TopBar
import app.relay.model.Milestone
import app.relay.nav.Route
import app.relay.ui.ErrorText
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.initialsOf
import app.relay.ui.rememberAction
import kotlinx.coroutines.launch

@Composable
private fun OnboardingPage(step: Int?, projectName: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalServices.current
    val user = LocalUser.current
    val scope = rememberCoroutineScope()
    Page(header = {
        TopBar(
            onLogo = { s.router.navigate(Route.Landing) },
            projectName = projectName,
            projectStatus = if (projectName != null) "Draft" to StatusKind.Pending else null,
            meta = step?.let { "Step $it of 5" },
            avatarInitials = user?.let { initialsOf(it.displayName) },
            onSignOut = { scope.launch { s.auth.signOut(); s.router.navigate(Route.Landing) } },
        )
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pad = if (maxWidth < 900.dp) Space.s16 else Space.s120
            Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = Space.s80), verticalArrangement = Arrangement.spacedBy(Space.s40), content = content)
        }
    }
}

// ── F1-03 · Hiring or freelancing? ───────────────────────────────────────────
@Composable
fun WelcomeScreen() {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val profile by s.repo.observeProfile(user.uid).collectAsState(null)
    val action = rememberAction()
    if (profile?.intent == "freelancing") { FreelancerWaitingScreen(); return }
    OnboardingPage(step = 1) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            RText("Are you hiring or freelancing?", RelayTheme.type.heading)
            RText("This sets up Relay around how you work. You can change it later.", RelayTheme.type.body, color = RelayColors.textSecondary)
        }
        Row(Modifier.fillMaxWidth().widthIn(max = 840.dp).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(Space.s24)) {
            NeutralCard(
                "I’m hiring", Modifier.weight(1f), category = "For clients",
                body = "Create a project, agree milestones with a freelancer and keep every decision on record.",
                link = "Continue as client →", selected = true,
                onClick = { action.run { s.repo.updateProfile(user.uid, intent = "hiring"); s.router.navigate(Route.Intro) } },
            )
            NeutralCard(
                "I’m freelancing", Modifier.weight(1f), category = "For freelancers",
                body = "Most freelancers join through a client’s invite. You can also start a project and invite your client.",
                link = "Continue as freelancer →",
                onClick = { action.run { s.repo.updateProfile(user.uid, intent = "freelancing") } },
            )
        }
        ErrorText(action.error)
    }
}

/** Freelancer with no project yet — they normally arrive through an invite link. */
@Composable
fun FreelancerWaitingScreen() {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val action = rememberAction()
    OnboardingPage(step = null) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
            CategoryLabel("Freelancer")
            RText("Waiting for an invite", RelayTheme.type.heading)
            RText("Open the invite link your client sent you — it takes you straight to the proposed terms. You can also start a project yourself and invite your client.", RelayTheme.type.body, color = RelayColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                PillButton("Start a project instead", { action.run { s.repo.updateProfile(user.uid, intent = "hiring"); s.router.navigate(Route.Intro) } })
            }
            ErrorText(action.error)
        }
    }
}

// ── F1-04 · How Relay works ──────────────────────────────────────────────────
@Composable
fun IntroScreen() {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val action = rememberAction()
    val go = { action.run { s.repo.updateProfile(user.uid, introSeen = true); s.router.navigate(Route.NewProject) } }
    OnboardingPage(step = 2) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            CategoryLabel("How Relay works")
            RText("Three steps. One project that never loses its place.", RelayTheme.type.heading)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s24)) {
            NeutralCard("Agree on milestones and fees", Modifier.weight(1f), category = "Step 1 · Agree", body = "You and your freelancer accept the same scope, fees and revisions. Any change becomes a new version.")
            NeutralCard("Relay remembers every decision", Modifier.weight(1f), category = "Step 2 · Remember", body = "Decisions from your conversation are suggested with their source. You confirm what counts.")
            NeutralCard("Hand off in one click", Modifier.weight(1f), category = "Step 3 · Hand off", body = "If someone becomes unavailable, share a scoped brief so a replacement can continue without starting over.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            PillButton("Create my first project", go, loading = action.running)
            PillButton("Skip", go, appearance = PillAppearance.Ghost)
        }
        ErrorText(action.error)
    }
}

// ── F1-05 · New project ──────────────────────────────────────────────────────
@Composable
fun NewProjectScreen() {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val action = rememberAction()
    val nameErr = if (tried && name.isBlank()) "Give the project a name." else null
    val goalErr = if (tried && goal.isBlank()) "Add a one-line goal." else null
    OnboardingPage(step = 3) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s80)) {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                CategoryLabel("New project")
                RText("What are you working on?", RelayTheme.type.heading)
                RText("Two details are enough to start. You can add everything else later.", RelayTheme.type.body, color = RelayColors.textSecondary)
                RelayTextField(name, { name = it }, "Project name", Modifier.fillMaxWidth(), placeholder = "ClearInvoice landing page", helper = "Everyone you invite sees this name.", error = nameErr)
                RelayTextField(goal, { goal = it }, "Goal (one line)", Modifier.fillMaxWidth(), placeholder = "Convert LinkedIn visitors into demo bookings.", helper = "Shown at the top of every agreement and handoff.", error = goalErr)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    PillButton("Next", {
                        tried = true
                        if (name.isNotBlank() && goal.isNotBlank()) action.run {
                            val id = s.repo.createProject(user, name, goal)
                            s.router.navigate(Route.NewMilestones(id))
                        }
                    }, loading = action.running)
                    PillButton("Back", { s.router.navigate(Route.Intro) }, appearance = PillAppearance.Ghost)
                }
                ErrorText(action.error)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                CategoryLabel("Live preview")
                NeutralCard(
                    name.ifBlank { "Your project" }, Modifier.fillMaxWidth(), category = "Project · Draft",
                    body = "Goal: " + goal.ifBlank { "—" } + "\n\nNext you’ll add milestones and invite your freelancer.",
                )
            }
        }
    }
}

// ── F1-06 · Milestones ───────────────────────────────────────────────────────
private class MilestoneRow(id: String, title: String, deliverable: String, fee: String, revisions: String) {
    val id = id
    var title by mutableStateOf(title)
    var deliverable by mutableStateOf(deliverable)
    var fee by mutableStateOf(fee)
    var revisions by mutableStateOf(revisions)
    val feePaise get() = parseRupeesToPaise(fee)
    val titleError get() = if (title.isBlank()) "Name the milestone." else null
    val deliverableError get() = if (deliverable.isBlank()) "Add a deliverable." else null
    val feeError get() = feePaise.let { if (it == null || it <= 0) "Enter a fee above ₹0." else null }
    val revisionsError get() = if (revisions.toIntOrNull()?.takeIf { it in 0..20 } == null) "0–20" else null
    val valid get() = titleError == null && deliverableError == null && feeError == null && revisionsError == null
}

@Composable
fun MilestonesScreen(projectId: String) {
    val s = LocalServices.current
    val project by s.repo.observeProject(projectId).collectAsState(null)
    val existing by s.repo.observeMilestones(projectId).collectAsState(null)
    val rows = remember { mutableStateListOf<MilestoneRow>() }
    val removed = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    val action = rememberAction()
    LaunchedEffect(existing) {
        val ex = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        loaded = true
        if (ex.isNotEmpty()) ex.forEach { rows += MilestoneRow(it.id, it.title, it.deliverable, formatInr(it.amountMinor), it.revisionLimit.toString()) }
        else listOf(
            MilestoneRow("", "Desktop design", "Desktop v2 · 1440px frames", "7,000", "2"),
            MilestoneRow("", "Mobile design", "360px and 390px frames", "6,000", "2"),
            MilestoneRow("", "Developer handoff", "Specs, assets and redlines", "2,000", "1"),
        ).forEach { rows += it }
    }
    val invalidCount = rows.count { !it.valid }
    val total = rows.sumOf { it.feePaise ?: 0 }
    OnboardingPage(step = 4, projectName = project?.name) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s40)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                CategoryLabel("Agreement · Draft")
                RText("Add milestones", RelayTheme.type.heading)
                RText("We suggested three for a landing page. Edit anything — nothing is final until your freelancer accepts.", RelayTheme.type.body, color = RelayColors.textSecondary)
                rows.forEachIndexed { i, r ->
                    MistPanel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            CategoryLabel("Milestone ${i + 1}")
                            if (rows.size > 1) TextLink("Remove", { if (r.id.isNotEmpty()) removed += r.id; rows.removeAt(i) }, small = true)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
                            RelayTextField(r.title, { r.title = it }, "Milestone", Modifier.width(184.dp), error = r.titleError)
                            RelayTextField(r.deliverable, { r.deliverable = it }, "Deliverable", Modifier.weight(1f), error = r.deliverableError)
                            RelayTextField(r.fee, { r.fee = it }, "Fee", Modifier.width(140.dp), prefix = "₹", error = r.feeError)
                            RelayTextField(r.revisions, { r.revisions = it.filter(Char::isDigit) }, "Revisions", Modifier.width(104.dp), error = r.revisionsError)
                        }
                    }
                }
                TextLink("+ Add milestone", { rows += MilestoneRow("", "", "", "", "1") })
            }
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(Space.s20)) {
                KeyValueTable("Running total", "Milestone" to "Fee", rows.map { it.title.ifBlank { "Untitled" } to (it.feePaise?.let(::rupees) ?: "—") } + ("Total" to rupees(total)), Modifier.fillMaxWidth())
                RText(
                    if (invalidCount > 0) "Fix $invalidCount ${if (invalidCount == 1) "milestone" else "milestones"} to continue." else "Fees are in INR. Payments happen only after a milestone is approved.",
                    RelayTheme.type.metadata, color = if (invalidCount > 0) RelayColors.textOnAccent else RelayColors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    PillButton("Next", {
                        action.run {
                            val items = rows.map { Milestone(id = it.id, title = it.title.trim(), deliverable = it.deliverable.trim(), amountMinor = it.feePaise ?: 0, revisionLimit = it.revisions.toInt()) }
                            s.repo.saveDraftMilestones(projectId, items, removed.toList())
                            s.router.navigate(Route.NewInvite(projectId))
                        }
                    }, enabled = rows.isNotEmpty() && invalidCount == 0, loading = action.running)
                    PillButton("Back", { s.router.navigate(Route.NewProject) }, appearance = PillAppearance.Ghost)
                }
                ErrorText(action.error)
            }
        }
    }
}

// ── F1-07 · Invite your freelancer ───────────────────────────────────────────
@Composable
fun InviteFreelancerScreen(projectId: String) {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val project by s.repo.observeProject(projectId).collectAsState(null)
    val milestones by s.repo.observeMilestones(projectId).collectAsState(emptyList())
    var email by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val action = rememberAction()
    val emailOk = email.trim().let { it.contains('@') && it.substringAfter('@').contains('.') && !it.contains(' ') }
    OnboardingPage(step = 5, projectName = project?.name) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s80)) {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
                CategoryLabel("Invite")
                RText("Invite your freelancer", RelayTheme.type.heading)
                RText("They see the project and the terms first, and create an account only when they accept.", RelayTheme.type.body, color = RelayColors.textSecondary)
                RelayTextField(
                    email, { email = it }, "Freelancer’s email", Modifier.fillMaxWidth(), placeholder = "rohan@example.com",
                    helper = "Recorded on the invite. Relay doesn’t send emails yet — you’ll copy the private link next.",
                    error = if (tried && !emailOk) "Enter a valid email address." else null,
                )
                RText("Nothing is final until they accept. You can still edit the draft after creating the invite.", RelayTheme.type.caption, color = RelayColors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    PillButton("Create invite", {
                        tried = true
                        val p = project
                        if (emailOk && p != null && milestones.isNotEmpty()) action.run {
                            s.repo.proposeAgreement(user, p, milestones, email)
                            s.router.navigate(Route.Project(projectId))
                        }
                    }, loading = action.running, enabled = milestones.isNotEmpty())
                    PillButton("Back", { s.router.navigate(Route.NewMilestones(projectId)) }, appearance = PillAppearance.Ghost)
                }
                ErrorText(action.error)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                CategoryLabel("Preview · what your freelancer will see")
                FloatingCard(Modifier.fillMaxWidth()) {
                    RText("${user.displayName} invited you to ${project?.name ?: "a project"} on Relay", RelayTheme.type.ui)
                    RText(
                        "Goal: ${project?.goal ?: ""}. Review ${milestones.size} milestones · ${rupees(milestones.sumOf { it.amountMinor })} total. Relay keeps your agreed scope and approvals on record — you create an account only when you accept.",
                        RelayTheme.type.metadata, color = RelayColors.textSecondary,
                    )
                }
                KeyValueTable("Proposed milestones", "Milestone" to "Fee · revisions", milestones.map { it.title to "${rupees(it.amountMinor)} · ${it.revisionLimit}" } + ("Total" to rupees(milestones.sumOf { it.amountMinor })), Modifier.fillMaxWidth())
            }
        }
    }
}
