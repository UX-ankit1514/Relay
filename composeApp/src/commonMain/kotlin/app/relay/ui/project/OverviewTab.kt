package app.relay.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.relay.data.rupees
import app.relay.design.ActionSpec
import app.relay.design.Amount
import app.relay.design.Avatar
import app.relay.design.CheckboxRow
import app.relay.design.ContextPanel
import app.relay.design.ListCard
import app.relay.design.ListRowSpec
import app.relay.design.MistPanel
import app.relay.design.NeutralCard
import app.relay.design.NextStepPanel
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.PillSize
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayDialog
import app.relay.design.RelayTextField
import app.relay.design.RelayTheme
import app.relay.design.Section
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.StatusLine
import app.relay.design.StatusPill
import app.relay.design.TextLink
import app.relay.design.TitleBlock
import app.relay.design.WorkspaceLayout
import app.relay.model.HandoffStatus
import app.relay.model.Milestone
import app.relay.model.MilestoneStatus
import app.relay.model.ProjectStatus
import app.relay.nav.ProjectTab
import app.relay.ui.ErrorText
import app.relay.ui.rememberAction

@Composable
fun MilestoneCards(ctx: Ctx, milestones: List<Milestone>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 720.dp
        val cards: @Composable (Modifier) -> Unit = { mod ->
            milestones.forEach { m ->
                val (label, _) = milestoneStatus(m)
                val who = m.assigneeName ?: "Unassigned"
                val fee = if (m.replacementFeeMinor != null && m.replacementFeeMinor != m.amountMinor)
                    "${rupees(m.amountMinor)} agreed · ${rupees(m.replacementFeeMinor)} with replacement" else rupees(m.amountMinor)
                NeutralCard(
                    m.title, mod, category = "Milestone ${m.index} · $label",
                    body = "$fee · ${m.revisionLimit} ${if (m.revisionLimit == 1) "revision" else "revisions"}\n$who · ${m.deliverable}",
                    link = "Open milestone →", onLink = { ctx.go(ProjectTab.Milestone, m.id) },
                )
            }
        }
        if (narrow) Column(verticalArrangement = Arrangement.spacedBy(Space.s20)) { cards(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(Space.s20)) {
            milestones.forEach { m ->
                val (label, _) = milestoneStatus(m)
                val who = m.assigneeName ?: "Unassigned"
                val fee = if (m.replacementFeeMinor != null && m.replacementFeeMinor != m.amountMinor)
                    "${rupees(m.amountMinor)} · replacement ${rupees(m.replacementFeeMinor)}" else rupees(m.amountMinor)
                NeutralCard(
                    m.title, Modifier.weight(1f), category = "Milestone ${m.index} · $label",
                    body = "$fee · ${m.revisionLimit} ${if (m.revisionLimit == 1) "revision" else "revisions"}\n$who · ${m.deliverable}",
                    link = "Open milestone →", onLink = { ctx.go(ProjectTab.Milestone, m.id) },
                )
            }
        }
    }
}

@Composable
fun ProjectContext(ctx: Ctx) {
    val d = ctx.d
    val readiness = when {
        d.confirmedDecisions.isEmpty() -> "Not ready · 0 decisions"
        d.assets.none { it.status == "approved" } && d.milestones.none { it.approvedSubmissionId != null } -> "Decisions ready · no approved file"
        else -> "Ready · ${d.confirmedDecisions.size} decisions"
    }
    ContextPanel(
        "Project status", "At a glance", "Item" to "Status",
        listOf(
            "Agreement" to (d.inForce?.let { "v${it.version} · agreed ${ctx.date(it.acceptedAt)}" } ?: d.pendingVersion?.let { "Draft v${it.version} · proposed" } ?: "Draft"),
            "Milestones" to "${d.milestones.size} · ${rupees(d.totalMinor)}",
            "Paid outside Relay" to rupees(d.paidOutsideMinor),
            "Paid (verified)" to rupees(d.paidVerifiedMinor),
            "Handoff readiness" to readiness,
        ),
    )
}

// ── Client overview (S-01) ───────────────────────────────────────────────────
@Composable
fun ClientOverview(ctx: Ctx) {
    val d = ctx.d
    val p = d.project
    val freelancer = d.originalFreelancer()
    var interruptOpen by remember { mutableStateOf(false) }
    WorkspaceLayout(
        main = {
            when {
                p.status == ProjectStatus.ACTIVE && d.inForce != null && d.inForce!!.acceptedAt != null && ctx.s.platform.now() - (d.inForce!!.acceptedAt ?: 0) < 86_400_000 ->
                    StatusLine("${d.inForce!!.acceptedByName ?: "Your freelancer"} accepted", StatusKind.Done, "Terms saved as Version ${d.inForce!!.version} · ${ctx.time(d.inForce!!.acceptedAt)}. Any later change creates a new version.")
                p.status == ProjectStatus.INTERRUPTED && freelancer != null ->
                    StatusLine("${freelancer.name} is unavailable", StatusKind.Attention, "Their assignment is paused. History and payments are kept — create a handoff for the remaining work.")
            }
            TitleBlock("Design project · created ${ctx.date(p.createdAt)}", p.name, "Brief: ${p.goal}")
            Section("Milestones", trailing = {
                if (p.status == ProjectStatus.WAITING) StatusPill("Draft v${p.agreementVersion} · not agreed yet", StatusKind.Pending)
                else RText("Agreement v${d.inForce?.version ?: p.agreementVersion} · ${rupees(d.totalMinor)}", RelayTheme.type.metadata, color = RelayColors.textSecondary)
            }) { MilestoneCards(ctx, d.milestones) }

            if (d.pendingReviews.isNotEmpty()) Section("Waiting for your review") {
                ListCard(d.pendingReviews.map { sub ->
                    ListRowSpec(sub.label, "Submitted by ${sub.submittedByName} · ${ctx.time(sub.submittedAt)}") {
                        StatusPill("In review", StatusKind.Pending); TextLink("Review →", { ctx.go(ProjectTab.Review, sub.id) }, small = true)
                    }
                })
            }

            when (p.status) {
                ProjectStatus.WAITING -> Section("What happens next") {
                    ListCard(listOf(
                        ListRowSpec("Invite created for ${p.inviteEmail ?: "your freelancer"}", "Share the private link — it expires in 7 days") { StatusPill("Done", StatusKind.Done) },
                        ListRowSpec("They review and accept the terms", "Saved as Agreement v${p.agreementVersion} with a timestamp") { StatusPill("Waiting", StatusKind.Pending) },
                        ListRowSpec("Project becomes active", "Conversation, decisions and files open up") { StatusPill("Not started", StatusKind.NotStarted) },
                    ))
                }
                ProjectStatus.ACTIVE -> {
                    val hasDecision = d.confirmedDecisions.isNotEmpty()
                    val hasFile = d.assets.isNotEmpty()
                    val done = listOf(hasDecision, hasFile, hasDecision && hasFile).count { it }
                    if (done < 3) Section("Start your project memory", "$done of 3 done") {
                        CheckboxRow(hasDecision, { ctx.go(ProjectTab.Conversation) }, "Record a decision", Modifier.fillMaxWidth(), "Post it in the conversation. Relay suggests a decision card linked to your message.")
                        CheckboxRow(hasFile, { ctx.go(ProjectTab.Files) }, "Attach a file", Modifier.fillMaxWidth(), "Add a Figma or Drive link so approved work has evidence.")
                        CheckboxRow(hasDecision && hasFile, { ctx.go(ProjectTab.Handoff) }, "Check handoff readiness", Modifier.fillMaxWidth(), "See what a replacement would receive if someone became unavailable today.")
                    }
                }
                else -> Unit
            }

            if (freelancer != null && p.status in listOf(ProjectStatus.ACTIVE, ProjectStatus.INTERRUPTED)) Section("Assignments") {
                val remaining = d.milestones.filter { it.assigneeId == freelancer.uid && it.status != MilestoneStatus.APPROVED }
                MistPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s16), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Avatar(app.relay.ui.initialsOf(freelancer.name))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                            RText("${freelancer.name} · Original freelancer", RelayTheme.type.uiMedium)
                            RText(if (remaining.isEmpty()) "All assigned milestones approved" else "Working on: " + remaining.joinToString { it.title }, RelayTheme.type.metadata, color = RelayColors.textSecondary)
                        }
                        if (freelancer.status == "interrupted") StatusPill("Unavailable", StatusKind.Neutral)
                        else if (remaining.isNotEmpty()) PillButton("Mark as interrupted", { interruptOpen = true }, appearance = PillAppearance.Ghost, size = PillSize.Compact)
                    }
                }
            }
        },
        rail = {
            ClientNextStep(ctx) { interruptOpen = true }
            ProjectContext(ctx)
        },
    )
    if (interruptOpen && freelancer != null) InterruptDialog(ctx, freelancer.uid, freelancer.name) { interruptOpen = false }
}

@Composable
private fun ClientNextStep(ctx: Ctx, onInterrupt: () -> Unit) {
    val d = ctx.d
    val p = d.project
    val h = d.activeHandoff
    val freelancer = d.originalFreelancer()
    when {
        p.status == ProjectStatus.WAITING -> NextStepPanel(
            "Waiting for ${p.inviteEmail?.substringBefore('@')?.replaceFirstChar { it.uppercase() } ?: "your freelancer"}",
            "Copy the private invite link and send it. You can still edit the draft until they accept.",
            "Invite expires 7 days after it was created",
            ActionSpec("Copy invite link", { p.inviteToken?.let { ctx.s.platform.copyToClipboard(ctx.s.platform.origin() + "/invite/" + it) } }),
            listOf(ActionSpec("Edit terms", { ctx.s.router.navigate(app.relay.nav.Route.NewMilestones(p.id)) })) +
                listOfNotNull(p.inviteToken?.let { t -> app.relay.ui.auth.demoAction(ctx.s, p.inviteEmail, null, { "Open invite as ${it.name}" }, app.relay.nav.Route.Invite(t)) }),
        )
        d.pendingVersion != null && d.inForce != null -> NextStepPanel(
            "Waiting on Version ${d.pendingVersion!!.version}",
            "${freelancer?.name ?: "Your freelancer"} needs to accept the change. Version ${d.inForce!!.version} stays in force until then.",
            "Proposed ${ctx.time(d.pendingVersion!!.proposedAt)}",
            ActionSpec("View agreement", { ctx.go(ProjectTab.Agreement) }),
        )
        d.pendingReviews.isNotEmpty() -> {
            val sub = d.pendingReviews.first()
            NextStepPanel("Review ${sub.label}", "${sub.submittedByName} submitted work for review. Approve it or ask for changes — AI never approves.", "Submitted ${ctx.time(sub.submittedAt)}", ActionSpec("Review submission", { ctx.go(ProjectTab.Review, sub.id) }))
        }
        d.approvedUnpaid().isNotEmpty() -> {
            val m = d.approvedUnpaid().first()
            NextStepPanel("Pay for ${m.title}", "The work is approved. Pay in Razorpay test mode, or record a payment you made outside Relay.", "${rupees(m.replacementFeeMinor ?: m.amountMinor)} · approved ${ctx.date(m.approvedAt)}", ActionSpec("Go to payment", { ctx.go(ProjectTab.Payment, m.id) }))
        }
        p.status == ProjectStatus.INTERRUPTED -> NextStepPanel("Hand off the remaining work", "Relay turns confirmed decisions, approved files and the remaining scope into a brief a replacement can start from.", "History and payments are kept", ActionSpec("Create handoff", { ctx.go(ProjectTab.Handoff) }))
        p.status == ProjectStatus.HANDOFF && h != null -> when (h.status) {
            HandoffStatus.COUNTEROFFER -> NextStepPanel("Review latest proposal", "${h.replacementName ?: "The replacement"} proposed a different fee. Nothing is binding until you both agree.", null, ActionSpec("Open handoff", { ctx.go(ProjectTab.Handoff) }))
            HandoffStatus.PUBLISHED -> NextStepPanel("Waiting for the replacement", "Share the private handoff link. You’ll see when it’s opened and accepted.", "Link expires ${ctx.date(h.expiresAt)}", ActionSpec("Open handoff", { ctx.go(ProjectTab.Handoff) }),
                listOfNotNull(app.relay.ui.auth.demoAction(ctx.s, h.replacementEmail ?: app.relay.ui.auth.demoOutsider(p.members.values.map { it.email })?.email, null, { "Open link as ${it.name}" }, app.relay.nav.Route.Handoff(h.id))))
            else -> NextStepPanel("${h.replacementName ?: "The replacement"} is on the project", "They see only the brief you shared. Their submission will appear here for review.", null, ActionSpec("Open handoff", { ctx.go(ProjectTab.Handoff) }))
        }
        d.suggestedDecisions.isNotEmpty() -> NextStepPanel("Review ${d.suggestedDecisions.size} suggested ${if (d.suggestedDecisions.size == 1) "decision" else "decisions"}", "AI only suggests. Nothing becomes project memory until you confirm it.", "Suggested by AI · needs your review", ActionSpec("Open decisions", { ctx.go(ProjectTab.Decisions) }))
        d.confirmedDecisions.isEmpty() -> NextStepPanel("Record your first decision", "Decisions you confirm are what Relay uses to brief anyone who joins later.", "Takes about a minute", ActionSpec("Open conversation", { ctx.go(ProjectTab.Conversation) }))
        else -> NextStepPanel("Project memory is live", "If ${freelancer?.name ?: "your freelancer"} becomes unavailable, Relay can brief a replacement from these decisions.", "${d.confirmedDecisions.size} confirmed · each linked to its source", ActionSpec("Check handoff readiness", { ctx.go(ProjectTab.Handoff) }), if (freelancer != null && freelancer.status != "interrupted") listOf(ActionSpec("Mark as interrupted", onInterrupt)) else emptyList())
    }
}

@Composable
fun InterruptDialog(ctx: Ctx, freelancerUid: String, name: String, onClose: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    val action = rememberAction()
    RelayDialog(
        "Mark $name’s assignment as interrupted?",
        "History and payments are kept: the conversation, confirmed decisions, approved files and payment records. $name’s remaining work is paused so you can hand it off.",
        onDismiss = onClose,
        primary = ActionSpec("Mark as interrupted", { action.run { ctx.repo.markInterrupted(ctx.user, ctx.p, freelancerUid, reason); onClose(); ctx.go(ProjectTab.Handoff) } }, loading = action.running),
    ) {
        RelayTextField(reason, { reason = it }, "Reason (private, not shared with the replacement)", Modifier.fillMaxWidth(), placeholder = "$name is unavailable from today", helper = "Saved in the project history with today’s date.")
        ErrorText(action.error)
    }
}

// ── Freelancer overview ("My work") ──────────────────────────────────────────
@Composable
fun FreelancerOverview(ctx: Ctx) {
    val d = ctx.d
    val mine = d.milestones.filter { it.assigneeId == ctx.user.uid }
    val current = mine.firstOrNull { it.status != MilestoneStatus.APPROVED }
    val interrupted = ctx.me?.status == "interrupted"
    WorkspaceLayout(
        main = {
            val accepted = d.agreements.firstOrNull { it.acceptedBy == ctx.user.uid }
            when {
                interrupted -> StatusLine("Assignment paused", StatusKind.Neutral, "${d.project.ownerName} marked your assignment as interrupted. Your submitted and approved work stays on record.")
                d.pendingVersion != null && d.inForce != null -> StatusLine("Terms updated", StatusKind.Pending, "${d.project.ownerName} proposed Version ${d.pendingVersion!!.version}. Review it on the Agreement tab.")
                accepted != null && ctx.s.platform.now() - (accepted.acceptedAt ?: 0) < 86_400_000 -> StatusLine("Agreement v${accepted.version} accepted", StatusKind.Done, "${ctx.time(accepted.acceptedAt)} · ${d.project.ownerName} has been notified")
            }
            if (current == null) {
                TitleBlock("Your work", if (mine.isEmpty()) "Nothing assigned yet" else "All your milestones are approved", "Approved work and payments stay on record in this project.")
            } else {
                TitleBlock("Your milestone", current.title, "${rupees(current.amountMinor)} · ${current.revisionLimit} revisions · ${current.deliverable}")
                val posted = d.messages.any { it.authorId == ctx.user.uid }
                val submitted = d.submissions.any { it.milestoneId == current.id && it.submittedBy == ctx.user.uid }
                Section("Your checklist", "${1 + listOf(posted, submitted).count { it }} of 3 done") {
                    CheckboxRow(true, {}, "Accept the agreement", Modifier.fillMaxWidth(), "Done · ${ctx.time(accepted?.acceptedAt)}")
                    CheckboxRow(posted, { ctx.go(ProjectTab.Conversation) }, "Share progress in the conversation", Modifier.fillMaxWidth(), "Decisions you and ${d.project.ownerName} agree on are saved with the message they came from.")
                    CheckboxRow(submitted, { ctx.go(ProjectTab.Milestone, current.id) }, "Submit ${current.title.lowercase()} for review", Modifier.fillMaxWidth(), "Paste the Figma link and a short note. ${d.project.ownerName} approves or asks for changes.")
                }
            }
            val others = mine.filter { it.id != current?.id }
            if (others.isNotEmpty()) Section(if (current == null) "Your milestones" else "Your other milestones") {
                ListCard(others.map { m ->
                    val (label, kind) = milestoneStatus(m)
                    ListRowSpec(m.title, "${m.deliverable} · ${m.revisionLimit} revisions", muted = m.status == MilestoneStatus.NOT_STARTED) { StatusPill(label, kind); Amount(rupees(m.amountMinor), muted = true) }
                })
            }
        },
        rail = {
            when {
                interrupted -> NextStepPanel("Nothing to do right now", "Your assignment is paused. Your submitted work and payment records stay on record.", null)
                current == null -> NextStepPanel("All done for now", "${d.project.ownerName} will assign or add milestones when there’s more work.", null)
                current.status == MilestoneStatus.CHANGES -> NextStepPanel("Changes requested", "${d.project.ownerName} asked for changes on ${current.title}. Read the note and resubmit.", "Revision ${current.revisionsUsed} of ${current.revisionLimit}", ActionSpec("Open milestone", { ctx.go(ProjectTab.Milestone, current.id) }))
                current.status == MilestoneStatus.SUBMITTED -> NextStepPanel("Waiting for ${d.project.ownerName}", "Your submission is in review. You’ll see the approval or requested changes here.", null, ActionSpec("Open milestone", { ctx.go(ProjectTab.Milestone, current.id) }),
                    listOfNotNull(app.relay.ui.auth.demoAction(ctx.s, d.project.members[d.project.ownerId]?.email, d.project.ownerName, { "Switch to ${it.name} to review" },
                        d.pendingReviews.firstOrNull { it.milestoneId == current.id }?.let { app.relay.nav.Route.Project(d.project.id, ProjectTab.Review, it.id) } ?: app.relay.nav.Route.Project(d.project.id))))
                else -> NextStepPanel("Start ${current.title}", "When it’s ready, submit the Figma link for ${d.project.ownerName}’s review.", "Agreed in Version ${d.inForce?.version ?: 1} · ${current.revisionLimit} revisions included", ActionSpec("Open milestone", { ctx.go(ProjectTab.Milestone, current.id) }))
            }
            ContextPanel("Payments · view only", "Your payments", "Milestone" to "Status", mine.map { m ->
                m.title to when (m.paymentStatus) {
                    app.relay.model.PaymentState.VERIFIED_TEST -> "Paid (verified)"
                    app.relay.model.PaymentState.RECORDED_EXTERNALLY -> "Paid outside Relay"
                    else -> if (m.status == MilestoneStatus.APPROVED) "Approved · unpaid" else "${rupees(m.amountMinor)} · not due"
                }
            } + ("Agreement" to (d.inForce?.let { "v${it.version} · accepted" } ?: "—")))
        },
    )
}

@Composable
fun VSpace(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))
