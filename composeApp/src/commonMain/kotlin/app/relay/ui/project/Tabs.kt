package app.relay.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.relay.data.fileSubtitle
import app.relay.data.isUploadedFile
import app.relay.data.parseRupeesToPaise
import app.relay.data.rupees
import app.relay.design.ActionSpec
import app.relay.design.Amount
import app.relay.design.Avatar
import app.relay.design.CategoryLabel
import app.relay.design.CheckboxRow
import app.relay.design.ContextPanel
import app.relay.design.KeyFacts
import app.relay.design.ListCard
import app.relay.design.ListRowSpec
import app.relay.design.MistPanel
import app.relay.design.NextStepPanel
import app.relay.design.NoticeRow
import app.relay.design.OutlinePanel
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.PillSize
import app.relay.design.RText
import app.relay.design.Radius
import app.relay.design.RelayColors
import app.relay.design.RelayDialog
import app.relay.design.RelayTextField
import app.relay.design.RelayTheme
import app.relay.design.Section
import app.relay.design.SidePanel
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.StatusLine
import app.relay.design.StatusPill
import app.relay.design.TabBar
import app.relay.design.TextLink
import app.relay.design.TitleBlock
import app.relay.design.WorkspaceLayout
import app.relay.model.Decision
import app.relay.model.DecisionStatus
import app.relay.model.HandoffStatus
import app.relay.model.MilestoneStatus
import app.relay.model.PaymentState
import app.relay.model.ProjectStatus
import app.relay.model.SubmissionLink
import app.relay.model.SubmissionStatus
import app.relay.nav.ProjectTab
import app.relay.ui.ErrorText
import app.relay.ui.friendlyError
import app.relay.ui.initialsOf
import app.relay.ui.payment.PaymentScreen
import app.relay.ui.rememberAction
import kotlinx.coroutines.launch

private fun validLink(u: String) = u.trim().let { it.contains('.') && !it.contains(' ') && it.length in 6..2000 }
private fun withScheme(u: String) = u.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }

// ── Milestone side panel (S-05) ──────────────────────────────────────────────
@Composable
fun MilestonePanel(ctx: Ctx, milestoneId: String) {
    val m = ctx.d.milestones.firstOrNull { it.id == milestoneId } ?: return
    val close = { ctx.go(ProjectTab.Overview) }
    val subs = ctx.d.submissions.filter { it.milestoneId == m.id }
    val latest = subs.firstOrNull()
    val mine = m.assigneeId == ctx.user.uid
    var link by remember(m.id) { mutableStateOf("") }
    var note by remember(m.id) { mutableStateOf("") }
    var tried by remember(m.id) { mutableStateOf(false) }
    var recordOpen by remember { mutableStateOf(false) }
    val action = rememberAction()
    SidePanel(close) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CategoryLabel("Milestone ${m.index} · Agreement v${ctx.d.inForce?.version ?: ctx.p.agreementVersion}")
            TextLink("Close", close, small = true)
        }
        RText(m.title, RelayTheme.type.headingSmall)
        val (label, kind) = milestoneStatus(m)
        StatusLine(label, kind, "${rupees(m.replacementFeeMinor ?: m.amountMinor)} · ${m.revisionLimit} revisions included · ${m.revisionsUsed} used")
        RText("Deliverable: ${m.deliverable}", RelayTheme.type.ui, color = RelayColors.textSecondary)
        if (m.assigneeName != null) RText("Assigned to ${m.assigneeName}", RelayTheme.type.metadata, color = RelayColors.textSecondary)

        if (m.status == MilestoneStatus.CHANGES && latest?.reviewNote != null) {
            CategoryLabel("What ${ctx.p.ownerName} asked for")
            MistPanel { RText(latest.reviewNote, RelayTheme.type.body) }
        }
        val canSubmit = mine && !ctx.isOwner && m.handoffId == null && m.status in listOf(MilestoneStatus.IN_PROGRESS, MilestoneStatus.NOT_STARTED, MilestoneStatus.CHANGES)
        if (canSubmit) {
            CategoryLabel("Deliverable")
            RelayTextField(link, { link = it }, "Figma link · ${m.deliverable}", Modifier.fillMaxWidth(), placeholder = "https://figma.com/design/…",
                helper = "Make sure ${ctx.p.ownerName} has view access to this file.", error = if (tried && !validLink(link)) "Add the Figma link to submit." else null)
            RelayTextField(note, { note = it }, "Note for ${ctx.p.ownerName}", Modifier.fillMaxWidth(), placeholder = "What’s in this version", singleLine = false)
            val decisionsKept = ctx.d.confirmedDecisions
            CheckboxRow(validLink(link), {}, "Figma link added", Modifier.fillMaxWidth(), if (validLink(link)) m.deliverable else "Required · not added yet", error = tried && !validLink(link))
            if (decisionsKept.isNotEmpty()) CheckboxRow(true, {}, "Follows the ${decisionsKept.size} confirmed decisions", Modifier.fillMaxWidth(), decisionsKept.joinToString(" · ") { it.title })
            PillButton(if (m.status == MilestoneStatus.CHANGES) "Resubmit for review" else "Submit for review", {
                tried = true
                if (validLink(link)) action.run {
                    ctx.repo.submitWork(ctx.user, ctx.p, m, "${m.title} v${subs.size + 1}", listOf(SubmissionLink(m.deliverable, withScheme(link))), note)
                    link = ""; note = ""; tried = false
                }
            }, Modifier.fillMaxWidth(), loading = action.running)
            ErrorText(action.error)
        }
        if (subs.isNotEmpty()) {
            CategoryLabel("Submissions")
            ListCard(subs.map { sub ->
                val (sl, sk) = when (sub.status) {
                    SubmissionStatus.APPROVED -> "Approved" to StatusKind.Done
                    SubmissionStatus.CHANGES -> "Changes requested" to StatusKind.Attention
                    else -> "In review" to StatusKind.Pending
                }
                ListRowSpec(sub.label, "${sub.submittedByName} · ${ctx.time(sub.submittedAt)}" + (sub.links.firstOrNull()?.url?.let { " · $it" } ?: "")) {
                    StatusPill(sl, sk)
                    if (ctx.isOwner && sub.status == SubmissionStatus.SUBMITTED) TextLink("Review →", { ctx.go(ProjectTab.Review, sub.id) }, small = true)
                }
            })
        }
        if (m.status == MilestoneStatus.SUBMITTED && !ctx.isOwner) RText("${ctx.p.ownerName} has been notified. Their approval or requested changes will appear here.", RelayTheme.type.caption, color = RelayColors.textSecondary)
        if (ctx.isOwner && m.status == MilestoneStatus.APPROVED && m.paymentStatus !in listOf(PaymentState.VERIFIED_TEST, PaymentState.RECORDED_EXTERNALLY)) {
            PillButton("Pay in test mode", { ctx.go(ProjectTab.Payment, m.id) }, Modifier.fillMaxWidth())
            if (m.handoffId == null) PillButton("Record a payment made outside Relay", { recordOpen = true }, Modifier.fillMaxWidth(), appearance = PillAppearance.Ghost)
        }
    }
    if (recordOpen) RecordExternalDialog(ctx, m) { recordOpen = false }
}

@Composable
fun RecordExternalDialog(ctx: Ctx, m: app.relay.model.Milestone, onClose: () -> Unit) {
    var amount by remember { mutableStateOf(app.relay.data.formatInr(m.amountMinor)) }
    var note by remember { mutableStateOf("") }
    val action = rememberAction()
    val paise = parseRupeesToPaise(amount)
    RelayDialog(
        "Record a payment made outside Relay",
        "Saved as “Paid outside Relay”. It is never shown as verified — only Razorpay payments are verified.",
        onClose,
        ActionSpec("Record ${paise?.let(::rupees) ?: ""}", { if (paise != null && paise > 0) action.run { ctx.repo.recordExternalPayment(ctx.user, ctx.p.id, m, paise, note); onClose() } }, enabled = paise != null && paise > 0, loading = action.running),
    ) {
        RelayTextField(amount, { amount = it }, "Amount paid to ${m.assigneeName ?: "the freelancer"}", Modifier.fillMaxWidth(), prefix = "₹", error = if (paise == null || paise <= 0) "Enter an amount above ₹0." else null)
        RelayTextField(note, { note = it }, "How it was paid (optional)", Modifier.fillMaxWidth(), placeholder = "Bank transfer on ${ctx.date(ctx.s.platform.now())}")
        ErrorText(action.error)
    }
}

// ── Agreement (S-02) ─────────────────────────────────────────────────────────
@Composable
fun AgreementTab(ctx: Ctx) {
    val d = ctx.d
    val inForce = d.inForce
    val pending = d.pendingVersion
    val shown = inForce ?: pending
    var changeOpen by remember { mutableStateOf(false) }
    val action = rememberAction()
    WorkspaceLayout(
        main = {
            if (pending != null && inForce != null) {
                TitleBlock("Agreement", "Terms updated", "${pending.proposedByName} proposed Version ${pending.version} on ${ctx.time(pending.proposedAt)}. Version ${inForce.version} stays in force until it’s accepted.")
                Section("What changed") {
                    val before = inForce.terms.associateBy { it.milestoneId }
                    ListCard(pending.terms.map { t ->
                        val b = before[t.milestoneId]
                        val changed = b == null || b.amountMinor != t.amountMinor || b.revisionLimit != t.revisionLimit || b.deliverable != t.deliverable || b.title != t.title
                        ListRowSpec(t.title, if (changed) (pending.changeNote ?: "Changed") else "${t.deliverable} · ${t.revisionLimit} revisions", muted = !changed) {
                            if (changed) { StatusPill("Changed", StatusKind.Attention); if (b != null && b.amountMinor != t.amountMinor) Amount(rupees(b.amountMinor), muted = true, strike = true) } else StatusPill("No change", StatusKind.Neutral)
                            Amount(rupees(t.amountMinor), muted = !changed)
                        }
                    } + ListRowSpec("New total", "Was ${rupees(inForce.totalMinor)} in Version ${inForce.version}") { Amount(rupees(pending.totalMinor)) })
                }
            } else if (shown != null) {
                TitleBlock("Agreement", "Version ${shown.version}", if (shown.status == "accepted") "Accepted by ${shown.acceptedByName} on ${ctx.time(shown.acceptedAt)}. Proposed by ${shown.proposedByName} on ${ctx.time(shown.proposedAt)}." else "Proposed by ${shown.proposedByName} on ${ctx.time(shown.proposedAt)} · not agreed yet.")
                Section("Terms", if (ctx.isOwner) "Changing any term creates a new version" else null) {
                    ListCard(shown.terms.mapIndexed { i, t -> ListRowSpec("${i + 1} · ${t.title}", "${t.deliverable} · ${t.revisionLimit} revisions") { Amount(rupees(t.amountMinor)) } } +
                        ListRowSpec("Total", "Each milestone is paid after the client approves it") { Amount(rupees(shown.totalMinor)) })
                }
            }
            Section("Version history") {
                ListCard(d.agreements.map { a ->
                    val (l, k) = when (a.status) {
                        "accepted" -> "Agreed · in force" to StatusKind.Done
                        "proposed" -> "Proposed · waiting" to StatusKind.Pending
                        "rejected" -> "Rejected" to StatusKind.NotStarted
                        else -> "Superseded" to StatusKind.Neutral
                    }
                    ListRowSpec("Version ${a.version}", if (a.acceptedAt != null) "Accepted by ${a.acceptedByName} · ${ctx.time(a.acceptedAt)}" else "Proposed by ${a.proposedByName} · ${ctx.time(a.proposedAt)}") { StatusPill(l, k) }
                })
            }
            ErrorText(action.error)
        },
        rail = {
            when {
                pending != null && inForce != null && !ctx.isOwner -> NextStepPanel("Review Version ${pending.version}", "Accept to make it binding, or reject the change to keep Version ${inForce.version}.", "Nothing changes until you decide",
                    ActionSpec("Accept Version ${pending.version}", { action.run { ctx.repo.acceptAgreementVersion(ctx.p.id, pending.version) } }, loading = action.running),
                    listOf(ActionSpec("Reject change", { action.run { ctx.repo.rejectAgreementVersion(ctx.p.id, pending.version) } }), ActionSpec("Ask a question", { ctx.go(ProjectTab.Conversation) })))
                ctx.isOwner && inForce != null && pending == null && ctx.p.status == ProjectStatus.ACTIVE -> NextStepPanel("Agreement in force", "Any change to scope or fees becomes a new version the freelancer must accept.", "Version ${inForce.version}", null, listOf(ActionSpec("Propose a change", { changeOpen = true })))
                else -> NextStepPanel("Agreement", if (inForce != null) "Version ${inForce.version} is in force." else "Waiting for the freelancer to accept the proposed terms.", null)
            }
            ContextPanel("Acknowledgments", "Who agreed", "Person" to "Action", listOfNotNull(
                shown?.let { "${it.proposedByName} · client" to "Proposed ${ctx.date(it.proposedAt)}" },
                inForce?.let { "${it.acceptedByName} · freelancer" to "Accepted ${ctx.date(it.acceptedAt)}" },
            ))
        },
    )
    if (changeOpen && inForce != null) ChangeDialog(ctx) { changeOpen = false }
}

@Composable
private fun ChangeDialog(ctx: Ctx, onClose: () -> Unit) {
    val ms = ctx.d.milestones.filter { it.status != MilestoneStatus.APPROVED }
    var sel by remember { mutableStateOf(ms.firstOrNull()?.id) }
    val m = ms.firstOrNull { it.id == sel }
    var fee by remember(sel) { mutableStateOf(m?.let { app.relay.data.formatInr(it.amountMinor) } ?: "") }
    var note by remember { mutableStateOf("") }
    val action = rememberAction()
    val paise = parseRupeesToPaise(fee)
    RelayDialog("Propose a change", "Creates a new version. The current version stays in force until the freelancer accepts.", onClose,
        ActionSpec("Propose change", { if (m != null && paise != null && paise > 0 && note.isNotBlank()) action.run {
            ctx.repo.proposeChange(ctx.user, ctx.p, ctx.d.milestones.map { if (it.id == m.id) it.copy(amountMinor = paise) else it }, note); onClose()
        } }, enabled = m != null && paise != null && paise > 0 && note.isNotBlank(), loading = action.running)) {
        ms.forEach { x -> CheckboxRow(x.id == sel, { sel = x.id }, x.title, Modifier.fillMaxWidth(), "${rupees(x.amountMinor)} now") }
        RelayTextField(fee, { fee = it }, "New fee", Modifier.fillMaxWidth(), prefix = "₹")
        RelayTextField(note, { note = it }, "Why (shown to the freelancer)", Modifier.fillMaxWidth(), placeholder = "Adding a short handoff call")
        ErrorText(action.error)
    }
}

// ── Conversation (S-03) ──────────────────────────────────────────────────────
@Composable
fun ConversationTab(ctx: Ctx) {
    val d = ctx.d
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var aiState by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun send() {
        val body = draft.trim(); if (body.isEmpty() || sending) return
        val rid = ctx.s.platform.randomToken(12)
        sending = true; sendError = null
        scope.launch {
            try {
                ctx.repo.sendMessage(ctx.user, ctx.p, body, rid)
                draft = ""
                aiState = "Looking for decisions…"
                try {
                    val r = ctx.repo.requestExtraction(ctx.p.id)
                    val created = (r as? kotlinx.serialization.json.JsonObject)?.get("created")?.toString()?.toIntOrNull() ?: 0
                    aiState = if (created > 0) "Review these suggested decisions." else null
                } catch (t: Throwable) { aiState = "Suggestions unavailable — " + friendlyError(t) }
            } catch (t: Throwable) { sendError = friendlyError(t) } finally { sending = false }
        }
    }
    WorkspaceLayout(
        main = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RText("Conversation", RelayTheme.type.headingSmall)
                RText("${d.messages.size} messages", RelayTheme.type.metadata, color = RelayColors.textSecondary)
            }
            if (d.messages.isEmpty()) app.relay.design.NeutralCard("Record decisions as you talk", Modifier.fillMaxWidth(), category = "No discussion yet",
                body = "Post anything you agree on — a layout choice, a copy change, a file link. Relay suggests a decision card linked to your message, and nothing is saved as a decision until the client confirms it.")
            d.messages.forEach { msg ->
                val tagged = d.decisions.filter { msg.id in it.sourceMessageIds && it.status == DecisionStatus.SUGGESTED }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    Avatar(initialsOf(msg.authorName))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically) {
                            RText(msg.authorName, RelayTheme.type.uiMedium)
                            RText(if (msg.authorRole == "owner") "Client" else "Freelancer", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                            RText(ctx.time(msg.createdAt), RelayTheme.type.metadata, color = RelayColors.textTertiary)
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.smallCard))
                            .then(if (msg.authorId == ctx.p.ownerId) Modifier.background(RelayColors.surfaceCard) else Modifier.border(1.dp, RelayColors.borderDefault, RoundedCornerShape(Radius.smallCard)))
                            .padding(horizontal = Space.s16, vertical = Space.s12)) { RText(msg.body, RelayTheme.type.body) }
                        if (tagged.isNotEmpty()) StatusPill(if (tagged.any { it.conflict }) "Conflicting instructions" else "${tagged.size} decision${if (tagged.size > 1) "s" else ""} suggested", if (tagged.any { it.conflict }) StatusKind.Attention else StatusKind.Pending)
                    }
                }
            }
            if (sendError != null) NoticeRow("Message wasn’t saved", StatusKind.Attention, "$sendError Your draft is kept below — nothing was sent.") { PillButton("Retry", { send() }, size = PillSize.Compact) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.input)).border(if (draft.isNotEmpty()) 2.dp else 1.dp, if (draft.isNotEmpty()) RelayColors.borderStrong else RelayColors.borderDefault, RoundedCornerShape(Radius.input)).padding(Space.s16),
                verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth(), textStyle = RelayTheme.type.ui, cursorBrush = SolidColor(RelayColors.textPrimary),
                    decorationBox = { inner -> if (draft.isEmpty()) RText("Write a message or paste an evidence link…", RelayTheme.type.ui, color = RelayColors.textPlaceholder); inner() })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    RText("Messages are sent to Claude (Anthropic) to suggest decisions. AI only suggests.", RelayTheme.type.metadata, color = RelayColors.textSecondary, modifier = Modifier.weight(1f))
                    PillButton("Send", { send() }, size = PillSize.Compact, enabled = draft.isNotBlank(), loading = sending)
                }
            }
            aiState?.let { RText(it, RelayTheme.type.metadata, color = if (it.startsWith("Suggestions")) RelayColors.textOnAccent else RelayColors.textSecondary) }
        },
        rail = {
            val sug = d.suggestedDecisions
            if (sug.isNotEmpty()) NextStepPanel("Review these suggested decisions", "Relay found ${sug.size} in this thread${if (sug.any { it.conflict }) ", including a conflict" else ""}. Only the ones the client confirms become project memory.", "Suggested by AI · needs review", ActionSpec("Open decisions", { ctx.go(ProjectTab.Decisions) }))
            else NextStepPanel(if (d.messages.isEmpty()) "Post your first decision" else "Keep talking", "Try: “Go with the blue hero layout.” Relay will suggest it as a decision for the client to confirm.", null)
            ContextPanel("Project memory", "Decisions", "Type" to "Count", listOf("Suggested" to "${sug.count { !it.conflict }}", "Conflicting" to "${sug.count { it.conflict }}", "Confirmed" to "${d.confirmedDecisions.size}", "Rejected" to "${d.decisions.count { it.status == DecisionStatus.REJECTED }}"))
        },
    )
}

// ── Decisions (S-04) ─────────────────────────────────────────────────────────
@Composable
fun DecisionsTab(ctx: Ctx) {
    val d = ctx.d
    var filter by remember { mutableStateOf("Suggested") }
    var manualOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Decision?>(null) }
    val action = rememberAction()
    var extractNote by remember { mutableStateOf<String?>(null) }
    val list = when (filter) {
        "Confirmed" -> d.confirmedDecisions
        "Rejected" -> d.decisions.filter { it.status == DecisionStatus.REJECTED || it.status == DecisionStatus.SUPERSEDED }
        else -> d.suggestedDecisions.sortedByDescending { it.conflict }
    }
    WorkspaceLayout(
        main = {
            TitleBlock("Decisions", "Project memory", if (filter == "Suggested") "Review these suggested decisions. Only the ones the client confirms become project memory." else "${d.confirmedDecisions.size} confirmed decisions, each linked to the message it came from.")
            TabBar(listOf("Suggested · ${d.suggestedDecisions.size}", "Confirmed · ${d.confirmedDecisions.size}", "Rejected · ${d.decisions.count { it.status != DecisionStatus.SUGGESTED && it.status != DecisionStatus.CONFIRMED }}"),
                listOf("Suggested", "Confirmed", "Rejected").first { filter == it }.let { f -> when (f) { "Suggested" -> "Suggested · ${d.suggestedDecisions.size}"; "Confirmed" -> "Confirmed · ${d.confirmedDecisions.size}"; else -> "Rejected · ${d.decisions.count { it.status != DecisionStatus.SUGGESTED && it.status != DecisionStatus.CONFIRMED }}" } },
                { filter = it.substringBefore(" ·") }, inset = false)
            extractNote?.let { NoticeRow(if (it.startsWith("!")) "Suggestions unavailable" else "Done", if (it.startsWith("!")) StatusKind.NotStarted else StatusKind.Done, it.removePrefix("!"), action = { PillButton("Add manually", { manualOpen = true }, appearance = PillAppearance.Ghost, size = PillSize.Compact) }) }
            if (list.isEmpty()) app.relay.design.NeutralCard(if (filter == "Confirmed") "No confirmed decisions yet" else "Nothing here", Modifier.fillMaxWidth(), category = filter,
                body = if (filter == "Suggested") "Post in the conversation and Relay suggests decisions with their source. You can also add one manually." else "Confirmed decisions appear here with the message they came from.")
            list.forEach { dec -> DecisionCard(ctx, dec, onEdit = { editing = dec }) }
            ErrorText(action.error)
        },
        rail = {
            val conflicts = d.suggestedDecisions.count { it.conflict }
            when {
                conflicts > 0 -> NextStepPanel("Resolve $conflicts conflict${if (conflicts > 1) "s" else ""} first", "Two messages disagree. A person picks one — Relay won’t choose.", "AI only suggests · you decide", null)
                d.suggestedDecisions.isNotEmpty() -> NextStepPanel("Review ${d.suggestedDecisions.size} suggestions", "Confirm, edit or reject each one. Confirmed items are recorded with reviewer, source and time.", "AI only suggests · you decide", null)
                d.confirmedDecisions.isNotEmpty() -> NextStepPanel("Project memory is live", "If someone becomes unavailable, Relay can brief a replacement from these decisions.", "${d.confirmedDecisions.size} confirmed", if (ctx.isOwner) ActionSpec("Check handoff readiness", { ctx.go(ProjectTab.Handoff) }) else null)
                else -> NextStepPanel("No decisions yet", "Talk in the conversation, or add a decision yourself.", null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                PillButton("Find decisions in the conversation", { action.run {
                    extractNote = try {
                        val r = ctx.repo.backend.call("extractDecisions", kotlinx.serialization.json.buildJsonObject { put("projectId", kotlinx.serialization.json.JsonPrimitive(ctx.p.id)); put("force", kotlinx.serialization.json.JsonPrimitive(true)) })
                        val n = (r as? kotlinx.serialization.json.JsonObject)?.get("created")?.toString()?.toIntOrNull() ?: 0
                        if (n == 0) "No new decisions found." else "$n new suggestion${if (n > 1) "s" else ""}."
                    } catch (t: Throwable) { "!" + friendlyError(t) }
                } }, Modifier.fillMaxWidth(), appearance = PillAppearance.Ghost, loading = action.running)
                PillButton("Add a decision manually", { manualOpen = true }, Modifier.fillMaxWidth(), appearance = PillAppearance.Ghost)
            }
            ContextPanel("Project memory", "Decisions", "Type" to "Count", listOf("Suggested" to "${d.suggestedDecisions.size}", "Confirmed" to "${d.confirmedDecisions.size}", "Rejected" to "${d.decisions.count { it.status == DecisionStatus.REJECTED }}"))
        },
    )
    if (manualOpen) ManualDecisionDialog(ctx) { manualOpen = false }
    editing?.let { dec -> EditDecisionDialog(ctx, dec) { editing = null } }
}

@Composable
private fun DecisionCard(ctx: Ctx, dec: Decision, onEdit: () -> Unit) {
    val action = rememberAction()
    val confirmed = dec.status == DecisionStatus.CONFIRMED
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.smallCard))
            .then(if (confirmed) Modifier.background(RelayColors.surfaceCard) else Modifier.border(1.dp, RelayColors.borderDefault, RoundedCornerShape(Radius.smallCard)))
            .padding(Space.s20),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CategoryLabel(when { dec.origin == "manual" -> "Added manually · ${dec.type.replace('_', ' ')}"; dec.conflict -> "Suggested by AI · conflict"; else -> "Suggested by AI · ${dec.type.replace('_', ' ')}" })
            when (dec.status) {
                DecisionStatus.CONFIRMED -> StatusPill("Confirmed", StatusKind.Done)
                DecisionStatus.SUGGESTED -> if (dec.conflict) StatusPill("Conflicting instructions", StatusKind.Attention) else StatusPill("Needs review", StatusKind.Pending)
                else -> StatusPill("Rejected", StatusKind.NotStarted)
            }
        }
        RText(dec.title, RelayTheme.type.bodyLarge, color = if (dec.status == DecisionStatus.REJECTED) RelayColors.textSecondary else RelayColors.textPrimary)
        if (dec.summary.isNotBlank()) RText(dec.summary, RelayTheme.type.ui, color = RelayColors.textSecondary)
        if (dec.sourceQuote.isNotBlank()) RText("“${dec.sourceQuote}” — ${dec.sourceAuthor}, ${ctx.time(dec.sourceAt)}", RelayTheme.type.metadata, color = RelayColors.textSecondary)
        dec.reviewNote?.let { if (!confirmed) RText("Note: $it", RelayTheme.type.metadata, color = RelayColors.textSecondary) }
        if (dec.status == DecisionStatus.SUGGESTED && ctx.isOwner) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                if (dec.conflict && dec.options.isNotEmpty()) {
                    dec.options.take(3).forEachIndexed { i, opt -> PillButton("Use: $opt", { action.run { ctx.repo.resolveConflict(ctx.user, ctx.p.id, dec, opt) } }, appearance = if (i == 0) PillAppearance.Filled else PillAppearance.Ghost, size = PillSize.Compact) }
                } else {
                    PillButton("Confirm", { action.run { ctx.repo.reviewDecision(ctx.user, ctx.p.id, dec, DecisionStatus.CONFIRMED) } }, size = PillSize.Compact, loading = action.running)
                    PillButton("Edit", onEdit, appearance = PillAppearance.Ghost, size = PillSize.Compact)
                }
                PillButton("Reject", { action.run { ctx.repo.reviewDecision(ctx.user, ctx.p.id, dec, DecisionStatus.REJECTED) } }, appearance = PillAppearance.Ghost, size = PillSize.Compact)
            }
        } else if (dec.status == DecisionStatus.SUGGESTED) RText("Waiting for ${ctx.p.ownerName} to confirm.", RelayTheme.type.metadata, color = RelayColors.textSecondary)
        else if (dec.confirmedByName != null) RText("${if (confirmed) "Confirmed" else "Rejected"} by ${dec.confirmedByName} · ${ctx.time(dec.confirmedAt)}" + (dec.reviewNote?.let { " · $it" } ?: ""), RelayTheme.type.metadata, color = RelayColors.textSecondary)
        ErrorText(action.error)
    }
}

@Composable
private fun EditDecisionDialog(ctx: Ctx, dec: Decision, onClose: () -> Unit) {
    var title by remember { mutableStateOf(dec.title) }
    var summary by remember { mutableStateOf(dec.summary) }
    val action = rememberAction()
    RelayDialog("Edit and confirm", "Your wording is what a replacement will read. The source message stays linked.", onClose,
        ActionSpec("Confirm decision", { action.run { ctx.repo.reviewDecision(ctx.user, ctx.p.id, dec, DecisionStatus.CONFIRMED, title = title.trim(), summary = summary.trim(), note = "Edited before confirming"); onClose() } }, enabled = title.isNotBlank(), loading = action.running)) {
        RelayTextField(title, { title = it }, "Decision", Modifier.fillMaxWidth())
        RelayTextField(summary, { summary = it }, "Summary", Modifier.fillMaxWidth(), singleLine = false)
        ErrorText(action.error)
    }
}

@Composable
private fun ManualDecisionDialog(ctx: Ctx, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    val action = rememberAction()
    RelayDialog("Add a decision manually", "Manual decisions are saved the same way: reviewer, source and time.", onClose,
        ActionSpec(if (ctx.isOwner) "Save decision" else "Suggest decision", { action.run { ctx.repo.addManualDecision(ctx.user, ctx.p.id, title, source, ctx.user.displayName, confirm = ctx.isOwner); onClose() } }, enabled = title.isNotBlank() && source.isNotBlank(), loading = action.running)) {
        RelayTextField(title, { title = it }, "Decision", Modifier.fillMaxWidth(), placeholder = "Keep “Book a demo” near the top", helper = "Write it the way you’d want a new freelancer to read it.")
        RelayTextField(source, { source = it }, "Source (quote the message or call)", Modifier.fillMaxWidth(), placeholder = "Priya, call on 12 Nov: “keep the demo button near the top”", helper = "Every decision links to where it came from.")
        ErrorText(action.error)
    }
}

// ── Files & evidence ─────────────────────────────────────────────────────────
@Composable
fun FilesTab(ctx: Ctx) {
    val d = ctx.d
    var addOpen by remember { mutableStateOf(false) }
    WorkspaceLayout(
        main = {
            TitleBlock("Files & evidence", "Approved work and references", "Relay stores links, not copies. Access problems are always shown — files are never hidden silently.")
            listOf("approved" to "Approved versions", "reference" to "References", "no_access" to "Needs attention").forEach { (st, label) ->
                val items = d.assets.filter { it.status == st }
                if (items.isNotEmpty()) Section(label) {
                    ListCard(items.map { a ->
                        // Approvals recorded before the server fix carry the reviewer's name; the file belongs to whoever submitted it.
                        val approvedSub = if (st == "approved") d.milestones.firstOrNull { it.id == a.milestoneId }?.approvedSubmissionId?.let { sid -> d.submissions.firstOrNull { it.id == sid } } else null
                        val addedBy = approvedSub?.takeIf { sub -> sub.links.any { it.url == a.url && it.label != "Approved file" } }?.submittedByName ?: a.addedByName
                        ListRowSpec(a.label, "${if (isUploadedFile(a.url)) "Uploaded document" else a.url} · added by $addedBy · ${ctx.date(a.createdAt)}" + (if (a.note.isNotBlank()) " · ${a.note}" else "")) {
                            // "Approved" is a record, not a control: an outlined tag, so it isn't mistaken for the ink Approve button (which lives on the Review page).
                            when (st) { "approved" -> StatusPill("✓ Approved", StatusKind.Neutral); "no_access" -> StatusPill("Request access to this file", StatusKind.Attention); else -> StatusPill("Reference", StatusKind.Neutral) }
                            TextLink("Open →", { ctx.s.platform.openExternal(a.url) }, small = true)
                        }
                    })
                }
            }
            if (d.assets.isEmpty()) app.relay.design.NeutralCard("No files yet", Modifier.fillMaxWidth(), category = "Files", body = "Add Figma or Drive links so approved work has evidence. Approved submissions are added here automatically.")
        },
        rail = {
            NextStepPanel("Add evidence", "Link the exact file or frame. Mark files you can’t open so a handoff never promises them.", null, ActionSpec("Add a link", { addOpen = true }))
            ContextPanel("Files", "Evidence", "Type" to "Count", listOf("Approved versions" to "${d.assets.count { it.status == "approved" }}", "References" to "${d.assets.count { it.status == "reference" }}", "Access warnings" to "${d.assets.count { it.status == "no_access" }}"))
        },
    )
    if (addOpen) {
        var label by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var noAccess by remember { mutableStateOf(false) }
        val action = rememberAction()
        RelayDialog("Add a link", "Paste a Figma, Drive or Docs link.", { addOpen = false },
            ActionSpec("Add link", { action.run { ctx.repo.addAsset(ctx.user, ctx.p.id, label, withScheme(url), if (noAccess) "no_access" else "reference", ""); addOpen = false } }, enabled = label.isNotBlank() && validLink(url), loading = action.running)) {
            RelayTextField(label, { label = it }, "Name", Modifier.fillMaxWidth(), placeholder = "Brand and copy guidelines")
            RelayTextField(url, { url = it }, "Link", Modifier.fillMaxWidth(), placeholder = "https://…", error = if (url.isNotEmpty() && !validLink(url)) "Enter a valid link." else null)
            CheckboxRow(noAccess, { noAccess = it }, "I can’t open this file yet", Modifier.fillMaxWidth(), "Shown as “Request access to this file”")
            ErrorText(action.error)
        }
    }
}

// ── Review submission (S-11) ─────────────────────────────────────────────────
@Composable
fun ReviewPage(ctx: Ctx, submissionId: String?) {
    val sub = ctx.d.submissions.firstOrNull { it.id == submissionId }
    if (sub == null) { WorkspaceLayout(main = { TitleBlock("Review", "Submission not found", "It may have been reviewed already.") }, rail = {}); return }
    val m = ctx.d.milestones.firstOrNull { it.id == sub.milestoneId }
    val criteria = remember(sub.id) { mutableStateMapOf<String, Boolean>() }
    var changesOpen by remember { mutableStateOf(false) }
    var approvedLink by remember { mutableStateOf("") }
    val action = rememberAction()
    val validLinks = sub.links.filter { validLink(it.url) }
    val blocked = validLinks.isEmpty() && !validLink(approvedLink)
    val crit = listOfNotNull(m?.let { "${it.deliverable}" to "Agreement · ${it.title}" }) + ctx.d.confirmedDecisions.map { it.title to "Confirmed decision · ${ctx.date(it.confirmedAt)}" }
    WorkspaceLayout(
        main = {
            TextLink("← Back to overview", { ctx.go(ProjectTab.Overview) }, small = true)
            TitleBlock("Review submission · Milestone ${m?.index ?: ""}", sub.label, "Submitted by ${sub.submittedByName} on ${ctx.time(sub.submittedAt)} · ${m?.revisionsUsed ?: 0} of ${m?.revisionLimit ?: 0} revisions used")
            Section("Submitted work", "${validLinks.size} link${if (validLinks.size == 1) "" else "s"}") {
                if (validLinks.isEmpty()) {
                    ListCard(listOf(ListRowSpec("No approved file attached", "Approval needs the exact file.") { StatusPill("Add the approved file", StatusKind.Attention) }))
                    RelayTextField(approvedLink, { approvedLink = it }, "Approved file link", Modifier.fillMaxWidth(), error = if (blocked) "Add the approved file before approving." else null)
                } else ListCard(validLinks.map { l -> ListRowSpec(l.label.ifBlank { "Link" }, "${l.url} · link only — Relay hasn’t opened the file") { StatusPill("Link", StatusKind.Pending); TextLink("Open in Figma →", { ctx.s.platform.openExternal(l.url) }, small = true) } })
            }
            Section("Acceptance criteria", "${criteria.count { it.value }} of ${crit.size} checked by you") {
                crit.forEach { (l, desc) -> CheckboxRow(criteria[l] == true, { criteria[l] = it }, l, Modifier.fillMaxWidth(), desc) }
            }
            if (sub.note.isNotBlank()) Section("${sub.submittedByName}’s note") { MistPanel { RText(sub.note, RelayTheme.type.body) } }
        },
        rail = {
            if (sub.status != SubmissionStatus.SUBMITTED) NextStepPanel(if (sub.status == SubmissionStatus.APPROVED) "${sub.label} approved." else "Changes requested", "Recorded ${ctx.time(sub.reviewedAt)}.", null, ActionSpec("Back to overview", { ctx.go(ProjectTab.Overview) }))
            else NextStepPanel(if (blocked) "Add the approved file" else "Approve ${sub.label}?", if (blocked) "Approval needs evidence: a link to the exact version you’re approving." else "Approving records this version and its link as approved. Payment becomes available afterwards.", "Only you can approve · AI never approves",
                ActionSpec("Approve", { action.run {
                    ctx.repo.reviewSubmission(ctx.p.id, sub.id, true, "", approvedLink = if (validLinks.isEmpty()) withScheme(approvedLink) else "")
                    m?.let { ctx.go(ProjectTab.Payment, it.id) }
                } }, enabled = !blocked, loading = action.running),
                listOf(ActionSpec("Request changes", { changesOpen = true })))
            // Next to the button, so a rejected approval is never off-screen.
            ErrorText(action.error)
            m?.let { ContextPanel("Milestone ${it.index}", it.title, "Item" to "Status", listOf("Fee" to rupees(it.replacementFeeMinor ?: it.amountMinor), "Revisions" to "${it.revisionsUsed} of ${it.revisionLimit} used", "Evidence" to if (validLinks.isEmpty()) "Missing" else "${validLinks.size} link(s)", "Payment" to "Locked until approval")) }
        },
    )
    if (changesOpen) {
        var note by remember { mutableStateOf("") }
        val a2 = rememberAction()
        RelayDialog("Request changes", "${sub.submittedByName} sees your note and can resubmit. The work isn’t approved and payment stays locked.", { changesOpen = false },
            ActionSpec("Send request", { a2.run { ctx.repo.reviewSubmission(ctx.p.id, sub.id, false, note); changesOpen = false; ctx.go(ProjectTab.Overview) } }, enabled = note.isNotBlank(), loading = a2.running)) {
            RelayTextField(note, { note = it }, "What should change?", Modifier.fillMaxWidth(), singleLine = false)
            ErrorText(a2.error)
        }
    }
}

@Composable
fun PaymentPage(ctx: Ctx, milestoneId: String?) = PaymentScreen(ctx.p.id, milestoneId ?: "")

// ── Handoff (S-06 / S-07) — client ───────────────────────────────────────────
@Composable
fun HandoffTab(ctx: Ctx) {
    val d = ctx.d
    val latest = d.handoffs.firstOrNull()
    val freelancer = d.originalFreelancer()
    val remaining = d.milestones.filter { it.status != MilestoneStatus.APPROVED }
    val selected = remember { mutableStateListOf<String>().apply { addAll(remaining.filter { it.status != MilestoneStatus.NOT_STARTED || remaining.size == 1 }.map { it.id }.ifEmpty { remaining.take(1).map { it.id } }) } }
    var fee by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val sections = remember { mutableStateMapOf("goal" to true, "done" to true, "decisions" to true, "scope" to true, "files" to true) }
    val excludedAssets = remember { mutableStateListOf<String>().apply { addAll(d.assets.filter { it.status == "no_access" }.map { it.id }) } }
    var interruptOpen by remember { mutableStateOf(false) }
    var shareToken by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    val feePaise = parseRupeesToPaise(fee.ifBlank { app.relay.data.formatInr(remaining.filter { it.id in selected }.sumOf { it.amountMinor }) })
    val link = { t: String -> ctx.s.platform.origin() + "/h/" + t }
    val live = latest?.takeIf { it.status !in listOf(HandoffStatus.REVOKED, HandoffStatus.DECLINED) }

    WorkspaceLayout(
        main = {
            when {
                live != null -> {
                    val h = live
                    TitleBlock("Handoff · ${h.brief.work.joinToString { it.title }}", when (h.status) {
                        HandoffStatus.PUBLISHED -> "Waiting for the replacement"
                        HandoffStatus.COUNTEROFFER -> "${h.replacementName} proposed ${rupees(h.proposals.last { it.status == "proposed" }.amountMinor)}"
                        HandoffStatus.ACCEPTED -> "${h.replacementName} is on the project"
                        HandoffStatus.SUBMITTED -> "Work submitted for review"
                        HandoffStatus.CHANGES -> "Changes requested"
                        HandoffStatus.APPROVED -> "Approved · ready to pay"
                        else -> "Handoff completed"
                    }, "Only the reviewed brief is shared. ${h.excluded.size} private items stay excluded.")
                    if (h.status in listOf(HandoffStatus.PUBLISHED, HandoffStatus.COUNTEROFFER)) Section("Share link") {
                        MistPanel {
                            RelayTextField(link(h.id), {}, "Private handoff link", Modifier.fillMaxWidth(), helper = "Expires ${ctx.date(h.expiresAt)} · single recipient · ${if (h.openedAt != null) "opened ${ctx.time(h.openedAt)}" else "not opened yet"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                                PillButton("Copy link", { ctx.s.platform.copyToClipboard(link(h.id)) })
                                PillButton("Revoke link", { action.run { ctx.repo.handoffAction(h.id, "revoke") } }, appearance = PillAppearance.Ghost)
                            }
                        }
                    }
                    Section("Fee proposals") {
                        ListCard(h.proposals.reversed().map { pr ->
                            ListRowSpec("Version ${pr.version} · ${rupees(pr.amountMinor)}", "${if (pr.byRole == "owner") "Offered" else "Proposed"} by ${pr.byName} · ${ctx.time(pr.at)}" + (if (pr.reason.isNotBlank()) " · “${pr.reason}”" else "")) {
                                StatusPill(when (pr.status) { "proposed" -> "Waiting for you"; "accepted" -> "Agreed"; "offered" -> "Offered"; "rejected" -> "Rejected"; else -> pr.status.replaceFirstChar { it.uppercase() } },
                                    when (pr.status) { "proposed" -> StatusKind.Attention; "accepted" -> StatusKind.Done; else -> StatusKind.Neutral })
                            }
                        })
                        val pend = h.proposals.lastOrNull { it.status == "proposed" }
                        if (pend != null) RText("The original agreement (Version ${d.inForce?.version ?: 1}) isn’t rewritten — any increase is shown as a replacement-scope adjustment of ${rupees(pend.amountMinor - (d.milestones.firstOrNull { it.id in h.milestoneIds }?.amountMinor ?: pend.amountMinor))}.", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                    }
                    Section("What the replacement sees") {
                        ListCard(listOf(
                            ListRowSpec("Done & approved", h.brief.approvedWork.joinToString { it.label }.ifBlank { "—" }) {},
                            ListRowSpec("Their work", h.brief.requiredDeliverables.joinToString(" · ")) {},
                            ListRowSpec("Decisions to keep (${h.brief.decisions.size})", h.brief.decisions.joinToString(" · ") { it.title }.ifBlank { "—" }) {},
                            ListRowSpec("Not in scope", h.brief.notInScope.joinToString { it.title }.ifBlank { "—" }) {},
                            ListRowSpec("Excluded", h.excluded.joinToString(" · "), muted = true) {},
                        ))
                    }
                }
                ctx.p.status == ProjectStatus.INTERRUPTED || latest?.status in listOf(HandoffStatus.REVOKED, HandoffStatus.DECLINED) -> {
                    if (latest?.status == HandoffStatus.DECLINED) StatusLine("${latest.replacementName ?: "The replacement"} declined", StatusKind.Neutral, "The project and its history are untouched. Create a new handoff for someone else.")
                    if (latest?.status == HandoffStatus.REVOKED) StatusLine("Previous link revoked", StatusKind.Neutral, "It no longer opens. Create a new handoff below.")
                    TitleBlock("Handoff · draft", "Choose the remaining work", "Relay builds the brief from what’s already confirmed. You review everything before anything is shared.")
                    Section("Done & approved", "Included as context, not as new work") {
                        val done = d.milestones.filter { it.status == MilestoneStatus.APPROVED }
                        if (done.isEmpty()) RText("Nothing approved yet.", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                        else ListCard(done.map { m -> ListRowSpec("${m.title} · ${m.approvedLabel ?: ""}", "Approved ${ctx.date(m.approvedAt)} · " + if (m.paymentStatus == PaymentState.RECORDED_EXTERNALLY) "Paid outside Relay" else if (m.paymentStatus == PaymentState.VERIFIED_TEST) "Paid (verified)" else "Unpaid") { StatusPill("Approved", StatusKind.Done) } })
                    }
                    Section("Remaining work", "Select what the replacement will do") {
                        remaining.forEach { m -> CheckboxRow(m.id in selected, { on -> if (on) selected.add(m.id) else selected.remove(m.id) }, m.title, Modifier.fillMaxWidth(), "${m.deliverable} · ${rupees(m.amountMinor)} · ${m.revisionLimit} revisions") }
                        if (selected.isEmpty()) RText("! Define remaining work — select at least one milestone.", RelayTheme.type.metadata, color = RelayColors.textOnAccent)
                        RelayTextField(fee.ifBlank { app.relay.data.formatInr(remaining.filter { it.id in selected }.sumOf { it.amountMinor }) }, { fee = it }, "Proposed fee for the replacement", Modifier.fillMaxWidth(), prefix = "₹", helper = "From the agreement. The replacement can accept or propose a different fee.", error = if (feePaise == null || feePaise <= 0) "Enter a fee above ₹0." else null)
                        RelayTextField(email, { email = it }, "Replacement’s email (optional)", Modifier.fillMaxWidth(), placeholder = "maya@example.com", helper = "Recorded on the handoff. You’ll copy the private link to send it.")
                    }
                    Section("Included in the brief") {
                        listOf("goal" to "Project goal", "done" to "Done & approved work", "decisions" to "Decisions to keep (${d.confirmedDecisions.size})", "scope" to "Not in scope", "files" to "Files (${d.assets.count { it.status != "no_access" && it.id !in excludedAssets }})").forEach { (k, l) ->
                            CheckboxRow(sections[k] == true, { sections[k] = it }, l, Modifier.fillMaxWidth())
                        }
                    }
                    if (d.assets.isNotEmpty()) Section("Files", "Untick private items — excluded items are never reachable through the link") {
                        d.assets.forEach { a ->
                            CheckboxRow(a.id !in excludedAssets && a.status != "no_access", { on -> if (a.status != "no_access") { if (on) excludedAssets.remove(a.id) else excludedAssets.add(a.id) } }, a.label, Modifier.fillMaxWidth(),
                                if (a.status == "no_access") "Request access to this file — excluded until someone can open it" else fileSubtitle(a.url, a.note), error = a.status == "no_access")
                        }
                    }
                    ErrorText(action.error)
                    PillButton("Publish handoff", { action.run {
                        val t = ctx.repo.publishHandoff(ctx.p.id, selected.toList(), feePaise ?: 0, sections.filterValues { it }.keys.toList(), excludedAssets.toList(), emptyList(), email.trim())
                        shareToken = t
                    } }, enabled = selected.isNotEmpty() && (feePaise ?: 0) > 0, loading = action.running)
                }
                else -> {
                    TitleBlock("Handoff", "Handoff readiness", "If ${freelancer?.name ?: "your freelancer"} became unavailable today, this is what a replacement would receive.")
                    ListCard(listOf(
                        ListRowSpec("Confirmed decisions", "${d.confirmedDecisions.size} · each linked to its source") { StatusPill(if (d.confirmedDecisions.isEmpty()) "Missing" else "Ready", if (d.confirmedDecisions.isEmpty()) StatusKind.Attention else StatusKind.Done) },
                        ListRowSpec("Approved work", d.milestones.filter { it.status == MilestoneStatus.APPROVED }.joinToString { it.approvedLabel ?: it.title }.ifBlank { "Nothing approved yet" }) { StatusPill(if (d.milestones.any { it.status == MilestoneStatus.APPROVED }) "Ready" else "Not yet", if (d.milestones.any { it.status == MilestoneStatus.APPROVED }) StatusKind.Done else StatusKind.NotStarted) },
                        ListRowSpec("Files & evidence", "${d.assets.size} links · ${d.assets.count { it.status == "no_access" }} access warnings") { StatusPill(if (d.assets.any { it.status == "no_access" }) "Check access" else "Ready", if (d.assets.any { it.status == "no_access" }) StatusKind.Attention else StatusKind.Done) },
                    ))
                    if (freelancer != null && freelancer.status != "interrupted") PillButton("Mark ${freelancer.name} as interrupted", { interruptOpen = true }, appearance = PillAppearance.Ghost)
                }
            }
        },
        rail = {
            when {
                live?.status == HandoffStatus.COUNTEROFFER -> NextStepPanel("Review latest proposal", "Accept to make the new fee binding (the replacement then confirms), or reject to keep the current offer.", "Only one proposal can be open at a time",
                    ActionSpec("Accept ${rupees(live.proposals.last { it.status == "proposed" }.amountMinor)}", { action.run { ctx.repo.handoffAction(live.id, "acceptProposal") } }, loading = action.running),
                    listOf(ActionSpec("Reject", { action.run { ctx.repo.handoffAction(live.id, "rejectProposal") } })))
                live?.status == HandoffStatus.PUBLISHED -> NextStepPanel("Waiting for the replacement", "You’ll see when they open the brief and accept, propose a fee or decline.", "Link expires ${ctx.date(live.expiresAt)}", ActionSpec("Copy link", { ctx.s.platform.copyToClipboard(link(live.id)) }),
                    listOfNotNull(app.relay.ui.auth.demoAction(ctx.s, live.replacementEmail ?: app.relay.ui.auth.demoOutsider(ctx.p.members.values.map { it.email })?.email, null, { "Open link as ${it.name}" }, app.relay.nav.Route.Handoff(live.id))))
                live?.status == HandoffStatus.SUBMITTED -> { val sub = d.pendingReviews.firstOrNull { it.handoffId == live.id }; NextStepPanel("Review ${sub?.label ?: "the submission"}", "${live.replacementName} submitted work for review.", null, sub?.let { ActionSpec("Review submission", { ctx.go(ProjectTab.Review, it.id) }) }) }
                live?.status == HandoffStatus.APPROVED -> { val m = d.milestones.firstOrNull { it.id in live.milestoneIds }; NextStepPanel("Pay ${live.replacementName}", "The work is approved. Pay ${rupees(live.agreedFeeMinor ?: live.feeMinor)} in Razorpay test mode.", null, m?.let { ActionSpec("Go to payment", { ctx.go(ProjectTab.Payment, it.id) }) }) }
                live != null -> NextStepPanel("${live.replacementName ?: "Replacement"} is working", "They see only the brief you shared. Their submission will appear here for review.", "Agreed ${rupees(live.agreedFeeMinor ?: live.feeMinor)}", null)
                ctx.p.status == ProjectStatus.INTERRUPTED || latest != null -> NextStepPanel("Publish when it looks right", "Publishing creates a private, expiring link. Nothing is shared until you copy or send it.", "Draft · only you can see this", null)
                else -> NextStepPanel("Ready if you need it", "Mark the freelancer as interrupted to start a handoff. History and payments are kept.", null, freelancer?.let { ActionSpec("Mark as interrupted", { interruptOpen = true }) })
            }
            ContextPanel("This handoff", "Brief summary", "Item" to "Detail", listOf(
                "Scope" to (live?.brief?.work?.joinToString { it.title } ?: remaining.filter { it.id in selected }.joinToString { it.title }.ifBlank { "Not defined" }),
                "Fee" to rupees(live?.agreedFeeMinor ?: live?.feeMinor ?: feePaise ?: 0),
                "Decisions" to "${live?.brief?.decisions?.size ?: d.confirmedDecisions.size} included",
                "Private items" to "${live?.excluded?.size ?: (excludedAssets.size + 1)} excluded",
            ))
        },
    )
    if (interruptOpen && freelancer != null) InterruptDialog(ctx, freelancer.uid, freelancer.name) { interruptOpen = false }
    shareToken?.let { t ->
        RelayDialog("Handoff ready to share", "Only the reviewed brief is shared. The link is private, for one recipient, and expires in 7 days. You can revoke it at any time.", { shareToken = null },
            ActionSpec("Copy link", { ctx.s.platform.copyToClipboard(link(t)); shareToken = null }), ActionSpec("Done", { shareToken = null })) {
            RelayTextField(link(t), {}, "Private handoff link", Modifier.fillMaxWidth(), helper = "Not indexed by search engines")
        }
    }
}

@Composable
private fun Unused() { Spacer(Modifier.width(0.dp)); OutlinePanel {}; KeyFacts(emptyList()) }
