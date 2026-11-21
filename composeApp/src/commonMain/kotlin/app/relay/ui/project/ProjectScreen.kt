package app.relay.ui.project

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import app.relay.data.AuthUser
import app.relay.data.RelayRepository
import app.relay.design.Page
import app.relay.design.StatusKind
import app.relay.design.TabBar
import app.relay.design.TopBar
import app.relay.model.AgreementVersion
import app.relay.model.Asset
import app.relay.model.Decision
import app.relay.model.DecisionStatus
import app.relay.model.Handoff
import app.relay.model.Member
import app.relay.model.Message
import app.relay.model.Milestone
import app.relay.model.MilestoneStatus
import app.relay.model.PaymentRecord
import app.relay.model.PaymentState
import app.relay.model.Project
import app.relay.model.ProjectStatus
import app.relay.model.Roles
import app.relay.model.Submission
import app.relay.model.SubmissionStatus
import app.relay.nav.ProjectTab
import app.relay.nav.Route
import app.relay.ui.AppServices
import app.relay.ui.LoadingPage
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.MessagePage
import app.relay.ui.initialsOf
import app.relay.ui.rememberLive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Everything the workspace shows, loaded live from Firestore. */
class ProjectData(
    val project: Project,
    val milestones: List<Milestone>,
    val agreements: List<AgreementVersion>,
    val messages: List<Message>,
    val decisions: List<Decision>,
    val assets: List<Asset>,
    val submissions: List<Submission>,
    val payments: List<PaymentRecord>,
    val handoffs: List<Handoff>,
) {
    val confirmedDecisions get() = decisions.filter { it.status == DecisionStatus.CONFIRMED }
    val suggestedDecisions get() = decisions.filter { it.status == DecisionStatus.SUGGESTED }
    val inForce get() = agreements.firstOrNull { it.status == "accepted" }
    val pendingVersion get() = agreements.firstOrNull { it.status == "proposed" }
    val paidOutsideMinor get() = milestones.sumOf { it.paidExternallyMinor }
    val paidVerifiedMinor get() = milestones.sumOf { it.paidVerifiedMinor }
    val totalMinor get() = milestones.sumOf { it.amountMinor }
    val activeHandoff get() = handoffs.firstOrNull { it.status !in listOf("revoked", "declined") }
    val pendingReviews get() = submissions.filter { it.status == SubmissionStatus.SUBMITTED }
    fun originalFreelancer(): Member? = project.members.values.firstOrNull { it.role == Roles.ORIGINAL }
    fun latestSubmission(milestoneId: String) = submissions.firstOrNull { it.milestoneId == milestoneId }
    fun approvedUnpaid() = milestones.filter { it.status == MilestoneStatus.APPROVED && it.paymentStatus !in listOf(PaymentState.VERIFIED_TEST, PaymentState.RECORDED_EXTERNALLY) }
}

/** Shared context passed to every workspace tab. */
class Ctx(
    val s: AppServices,
    val user: AuthUser,
    val d: ProjectData,
    val route: Route.Project,
) {
    val repo: RelayRepository get() = s.repo
    val p: Project get() = d.project
    val isOwner: Boolean get() = p.ownerId == user.uid
    val me: Member? get() = p.members[user.uid]
    fun go(tab: ProjectTab, sub: String? = null) = s.router.navigate(Route.Project(p.id, tab, sub))
    fun date(ms: Long?) = if (ms == null) "—" else s.platform.formatDate(ms)
    fun time(ms: Long?) = if (ms == null) "—" else s.platform.formatDateTime(ms)
}

@Composable
fun ProjectScreen(route: Route.Project) {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val pid = route.projectId
    val project = rememberLive(pid, null) { s.repo.observeProject(pid) }
    val milestones = rememberLive(pid, emptyList()) { s.repo.observeMilestones(pid) }
    val agreements = rememberLive(pid, emptyList()) { s.repo.observeAgreements(pid) }
    val messages = rememberLive(pid, emptyList()) { s.repo.observeMessages(pid) }
    val decisions = rememberLive(pid, emptyList()) { s.repo.observeDecisions(pid) }
    val assets = rememberLive(pid, emptyList()) { s.repo.observeAssets(pid) }
    val submissions = rememberLive(pid, emptyList()) { s.repo.observeSubmissions(pid) }
    val payments = rememberLive(pid, emptyList()) { s.repo.observePayments(pid) }
    val isOwner = project.value?.ownerId == user.uid
    val handoffs = rememberLive(pid to isOwner, emptyList()) { if (isOwner) s.repo.observeHandoffs(pid, user.uid) else flowOf(emptyList()) }

    if (project.error != null) {
        MessagePage(
            "Access not permitted" to StatusKind.NotStarted, "This project isn’t shared with you",
            "Only the client and the freelancers on the project can open it. If you were sent a handoff link, open that link instead.",
            "Go to Relay" to { s.router.navigate(Route.Landing) },
        )
        return
    }
    if (!project.loaded) { LoadingPage("Opening project…"); return }
    val p = project.value
    if (p == null) {
        MessagePage("Not found" to StatusKind.NotStarted, "This project doesn’t exist", "It may have been removed, or the link is incomplete.", "Go to Relay" to { s.router.navigate(Route.Landing) })
        return
    }
    if (p.status == ProjectStatus.DRAFT && isOwner) {
        // Unfinished onboarding — continue where the client left off.
        val target = if (milestones.value.isEmpty()) Route.NewMilestones(p.id) else Route.NewInvite(p.id)
        androidx.compose.runtime.LaunchedEffect(p.id) { s.router.navigate(target, replace = true) }
        LoadingPage(); return
    }
    val data = ProjectData(p, milestones.value, agreements.value, messages.value, decisions.value, assets.value, submissions.value, payments.value, handoffs.value)
    val ctx = Ctx(s, user, data, route)
    val scope = rememberCoroutineScope()

    val tabs = if (ctx.isOwner) ProjectTab.clientTabs else ProjectTab.freelancerTabs
    val shownTab = when (route.tab) {
        ProjectTab.Milestone -> ProjectTab.Overview
        ProjectTab.Review, ProjectTab.Payment -> if (ctx.isOwner) ProjectTab.Overview else ProjectTab.Overview
        else -> route.tab
    }
    val tabLabel = { t: ProjectTab -> if (t == ProjectTab.Overview && !ctx.isOwner) "My work" else t.label }

    Page(
        header = {
            TopBar(
                onLogo = { s.router.navigate(Route.Project(p.id)) },
                projectName = p.name,
                projectStatus = statusChip(data),
                role = if (ctx.isOwner) "Client" else "Freelancer",
                avatarInitials = initialsOf(user.displayName),
                onSignOut = { scope.launch { s.auth.signOut(); s.router.navigate(Route.Landing) } },
            )
            TabBar(tabs.map(tabLabel), tabLabel(shownTab), { label -> ctx.go(tabs.first { tabLabel(it) == label }) })
        },
        overlay = {
            if (route.tab == ProjectTab.Milestone && route.sub != null) MilestonePanel(ctx, route.sub)
        },
    ) {
        when (route.tab) {
            ProjectTab.Overview, ProjectTab.Milestone -> if (ctx.isOwner) ClientOverview(ctx) else FreelancerOverview(ctx)
            ProjectTab.Agreement -> AgreementTab(ctx)
            ProjectTab.Conversation -> ConversationTab(ctx)
            ProjectTab.Decisions -> DecisionsTab(ctx)
            ProjectTab.Files -> FilesTab(ctx)
            ProjectTab.Handoff -> if (ctx.isOwner) HandoffTab(ctx) else ClientOverview(ctx)
            ProjectTab.Review -> ReviewPage(ctx, route.sub)
            ProjectTab.Payment -> PaymentPage(ctx, route.sub)
        }
    }
}

fun statusChip(d: ProjectData): Pair<String, StatusKind> {
    val p = d.project
    return when {
        p.status == ProjectStatus.WAITING -> "Waiting for agreement" to StatusKind.Pending
        p.agreementStatus == "proposed" && d.inForce != null -> "Terms updated" to StatusKind.Pending
        p.status == ProjectStatus.INTERRUPTED -> "Interrupted" to StatusKind.Pending
        p.status == ProjectStatus.HANDOFF -> "Handoff in progress" to StatusKind.Pending
        p.status == ProjectStatus.COMPLETED -> "Completed" to StatusKind.Done
        p.status == ProjectStatus.DRAFT -> "Draft" to StatusKind.Pending
        else -> "Active" to StatusKind.Done
    }
}

/** Status label for a milestone, in words (never colour alone). */
fun milestoneStatus(m: Milestone): Pair<String, StatusKind> = when {
    m.paymentStatus == PaymentState.VERIFIED_TEST -> "Approved · Paid (verified)" to StatusKind.Done
    m.status == MilestoneStatus.APPROVED && m.paymentStatus == PaymentState.RECORDED_EXTERNALLY -> "Approved · Paid outside Relay" to StatusKind.Neutral
    m.status == MilestoneStatus.APPROVED -> "Approved" to StatusKind.Done
    m.status == MilestoneStatus.SUBMITTED -> "In review" to StatusKind.Pending
    m.status == MilestoneStatus.CHANGES -> "Changes requested" to StatusKind.Attention
    m.status == MilestoneStatus.IN_PROGRESS -> "In progress" to StatusKind.Pending
    m.status == MilestoneStatus.REASSIGNED -> "Pending reassignment" to StatusKind.Pending
    m.status == MilestoneStatus.INTERRUPTED -> "Interrupted" to StatusKind.Pending
    m.status == MilestoneStatus.DRAFT -> "Draft" to StatusKind.NotStarted
    else -> "Not started" to StatusKind.NotStarted
}
