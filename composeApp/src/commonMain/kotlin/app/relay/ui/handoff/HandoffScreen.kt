package app.relay.ui.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.relay.data.parseRupeesToPaise
import app.relay.data.fileSubtitle
import app.relay.data.rupees
import app.relay.design.ActionSpec
import app.relay.design.Amount
import app.relay.design.CategoryLabel
import app.relay.design.CheckboxRow
import app.relay.design.ContextPanel
import app.relay.design.KeyFacts
import app.relay.design.ListCard
import app.relay.design.ListRowSpec
import app.relay.design.MistPanel
import app.relay.design.NeutralCard
import app.relay.design.NextStepPanel
import app.relay.design.Page
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
import app.relay.design.TabBar
import app.relay.design.TextLink
import app.relay.design.TitleBlock
import app.relay.design.TopBar
import app.relay.design.WorkspaceLayout
import app.relay.model.Handoff
import app.relay.model.HandoffStatus
import app.relay.model.SubmissionLink
import app.relay.nav.HandoffTab
import app.relay.nav.Route
import app.relay.ui.ErrorText
import app.relay.ui.LoadingPage
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.MessagePage
import app.relay.ui.initialsOf
import app.relay.ui.rememberAction
import app.relay.ui.rememberLive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** F6 · Replacement view — scoped to the reviewed brief only (no project history, no other milestones). */
@Composable
fun HandoffScreen(route: Route.Handoff) {
    val s = LocalServices.current
    val user = LocalUser.current
    val live = rememberLive(route.token to user?.uid, null) { s.repo.observeHandoff(route.token) }
    val canReadSubs = user != null && live.value.let { h -> h != null && (h.replacementUid == user.uid || h.ownerId == user.uid) }
    val subs = rememberLive(Triple(route.token, user?.uid, canReadSubs), emptyList()) {
        if (canReadSubs) s.repo.observeHandoffSubmissions(route.token) else flowOf(emptyList())
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(route.token) { runCatching { s.repo.handoffAction(route.token, "open") } }
    val home = { s.router.navigate(Route.Landing) }
    if (!live.loaded) { LoadingPage("Opening handoff…"); return }
    val h = live.value
    if (h == null || live.error != null) {
        MessagePage("Link unavailable" to StatusKind.NotStarted, "This handoff link has expired", "Handoff links expire or can be revoked to keep projects private. Ask the client to reissue it — it takes a few seconds.", "What is Relay?" to home)
        return
    }
    if (user != null && h.ownerId == user.uid) {
        LaunchedEffect(h.id) { s.router.navigate(Route.Project(h.projectId, app.relay.nav.ProjectTab.Handoff), replace = true) }
        LoadingPage(); return
    }
    if (h.status == HandoffStatus.DECLINED) { MessagePage("Declined" to StatusKind.Neutral, "You declined this handoff", "The project is untouched. ${h.ownerName} can invite someone else.", "What is Relay?" to home); return }
    val joined = user != null && h.replacementUid == user.uid && h.status !in listOf(HandoffStatus.PUBLISHED, HandoffStatus.COUNTEROFFER)
    val chip = when (h.status) {
        HandoffStatus.PUBLISHED -> "Handoff · waiting for you" to StatusKind.Pending
        HandoffStatus.COUNTEROFFER -> "New fee proposed" to StatusKind.Pending
        HandoffStatus.ACCEPTED, HandoffStatus.CHANGES -> "Active · ${h.brief.work.joinToString { it.title }}" to StatusKind.Done
        HandoffStatus.SUBMITTED -> "In review" to StatusKind.Pending
        HandoffStatus.APPROVED -> "Approved" to StatusKind.Done
        else -> "Paid (verified)" to StatusKind.Done
    }
    Page(header = {
        TopBar(onLogo = home, projectName = h.brief.projectName, projectStatus = chip, role = "Replacement freelancer",
            avatarInitials = user?.let { initialsOf(it.displayName) }, onSignOut = { scope.launch { s.auth.signOut() } })
        TabBar(HandoffTab.entries.map { it.label }, route.tab.label, { label -> s.router.navigate(Route.Handoff(h.id, HandoffTab.entries.first { it.label == label })) })
    }) {
        when (route.tab) {
            HandoffTab.Brief -> BriefTab(h, joined)
            HandoffTab.Terms -> TermsTab(h)
            HandoffTab.Work -> WorkTab(h, subs.value)
            HandoffTab.Status -> StatusTab(h, subs.value)
        }
    }
}

@Composable
private fun BriefTab(h: Handoff, joined: Boolean) {
    val s = LocalServices.current
    val b = h.brief
    WorkspaceLayout(
        main = {
            TitleBlock("Handoff from ${b.clientName} · ${s.platform.formatDate(h.publishedAt ?: h.createdAt)}", "${b.clientName} needs help finishing ${b.projectName}", "Relay packaged this project so you can continue it without starting over.")
            KeyFacts(listOf("Client" to "${b.clientName} · project owner", "Goal" to b.goal.ifBlank { "—" }, "Your work" to b.work.joinToString { it.title }))
            if (b.approvedWork.isNotEmpty()) Section("Done & approved", "Your starting point") {
                ListCard(b.approvedWork.map { f -> ListRowSpec(f.label, f.note) { StatusPill("Approved", StatusKind.Done); if (f.url.isNotBlank()) TextLink("Open in Figma →", { s.platform.openExternal(f.url) }, small = true) } })
            }
            Section("Your work", b.work.joinToString { "${it.title} · ${rupees(h.agreedFeeMinor ?: h.feeMinor)} · ${it.revisionLimit} revisions" }) {
                ListCard(b.requiredDeliverables.map { r -> ListRowSpec(r, if (r == "Completion note") "What you built and anything to check" else "Figma link to the exact frame") { StatusPill("Required", StatusKind.Neutral) } })
            }
            if (b.decisions.isNotEmpty()) Section("Decisions to keep", "${b.decisions.size} · each linked to its source") {
                b.decisions.forEach { d ->
                    MistPanel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { CategoryLabel("Decision to keep"); StatusPill("Confirmed", StatusKind.Done) }
                        RText(d.title, RelayTheme.type.bodyLarge)
                        if (d.summary.isNotBlank()) RText(d.summary, RelayTheme.type.ui, color = RelayColors.textSecondary)
                        if (d.sourceQuote.isNotBlank()) RText("“${d.sourceQuote}” — ${d.sourceAuthor}, ${s.platform.formatDateTime(d.sourceAt)}", RelayTheme.type.metadata, color = RelayColors.textSecondary)
                    }
                }
            }
            if (b.notInScope.isNotEmpty()) Section("Not in scope") { ListCard(b.notInScope.map { ListRowSpec(it.title, "Not part of this handoff — don’t build ${it.deliverable.lowercase()}.") { StatusPill("Not in scope", StatusKind.Neutral) } }) }
            if (b.files.isNotEmpty()) Section("Files", "Only what ${b.clientName} shared") {
                ListCard(b.files.map { f -> ListRowSpec(f.label, fileSubtitle(f.url, f.note)) { StatusPill(if (f.status == "approved") "Approved" else "Reference", if (f.status == "approved") StatusKind.Done else StatusKind.Neutral); TextLink("Open →", { s.platform.openExternal(f.url) }, small = true) } })
            }
        },
        rail = {
            if (joined) NextStepPanel("You’re on the project", "Go to My work to submit the frames when they’re ready.", null, ActionSpec("Open my work", { s.router.navigate(Route.Handoff(h.id, HandoffTab.Work)) }))
            else NextStepPanel("Continue to terms", "Read the brief, then review the fee. You create an account only when you accept.", "Link expires ${s.platform.formatDate(h.expiresAt)}", ActionSpec("Continue to terms", { s.router.navigate(Route.Handoff(h.id, HandoffTab.Terms)) }))
            ContextPanel("What you can see", "This handoff", "Item" to "Detail", listOf("Shared by" to b.clientName, "Scope" to b.work.joinToString { it.title }, "Decisions" to "${b.decisions.size} included", "Files" to "${b.files.size + b.approvedWork.size} included", "Project history" to "Not shared"))
        },
    )
}

@Composable
private fun TermsTab(h: Handoff) {
    val s = LocalServices.current
    val user = LocalUser.current
    val action = rememberAction()
    var proposeOpen by remember { mutableStateOf(false) }
    var declineOpen by remember { mutableStateOf(false) }
    val pending = h.proposals.lastOrNull { it.status == "proposed" }
    val offer = h.proposals.lastOrNull { it.status == "offered" || it.status == "accepted" }
    val fee = h.agreedFeeMinor ?: offer?.amountMinor ?: h.feeMinor
    val signThen: (suspend () -> Unit) -> Unit = { block -> action.run { if (s.auth.state.value !is app.relay.data.AuthState.SignedIn) s.auth.signInWithGoogle(); block() } }
    WorkspaceLayout(
        main = {
            if (pending != null) StatusLine("New fee proposed. Waiting for ${h.ownerName}.", StatusKind.Pending, "Sent ${s.platform.formatDateTime(pending.at)}")
            TitleBlock(if (pending != null) "Terms · your proposal" else "Terms · offer from ${h.ownerName}", "${rupees(pending?.amountMinor ?: fee)} for ${h.brief.work.joinToString { it.title }}",
                if (h.status == HandoffStatus.PUBLISHED || h.status == HandoffStatus.COUNTEROFFER) "Accepting saves this version with a timestamp. A different fee is binding only if ${h.ownerName} accepts it too." else "Agreed ${s.platform.formatDateTime(h.acceptedAt ?: 0)}.")
            Section("Scope") { ListCard(h.brief.work.map { w -> ListRowSpec(w.title, "${w.deliverable} · ${w.revisionLimit} revisions · Figma links and completion note") { Amount(rupees(fee)) } }) }
            if (h.proposals.size > 1) Section("Proposals") {
                ListCard(h.proposals.reversed().map { pr -> ListRowSpec("Version ${pr.version} · ${rupees(pr.amountMinor)}", "${if (pr.byRole == "owner") "Offered by" else "Proposed by"} ${pr.byName} · ${s.platform.formatDateTime(pr.at)}" + if (pr.reason.isNotBlank()) " · “${pr.reason}”" else "") {
                    StatusPill(when (pr.status) { "proposed" -> "Waiting for ${h.ownerName}"; "accepted" -> "Agreed"; "offered" -> "Offered"; "rejected" -> "Rejected"; else -> "Kept in history" }, if (pr.status == "accepted") StatusKind.Done else if (pr.status == "proposed") StatusKind.Pending else StatusKind.Neutral)
                } })
            }
            if (h.brief.earlierPayments.isNotEmpty()) Section("Earlier payments", "Not part of your fee") { ListCard(h.brief.earlierPayments.map { ListRowSpec(it, null, muted = true) {} }) }
            NeutralCard("Paid after ${h.ownerName} approves", Modifier.fillMaxWidth(), category = "How payment works",
                body = "${h.ownerName} pays through Razorpay (test mode in this demo) after approving your submission. Relay shows “Paid (verified)” only after Razorpay confirms it on the server. Payout to you isn’t automated in this demo.")
            ErrorText(action.error)
        },
        rail = {
            when {
                h.status !in listOf(HandoffStatus.PUBLISHED, HandoffStatus.COUNTEROFFER) -> NextStepPanel("Terms agreed", "You accepted ${rupees(fee)}. Submit your work from My work.", null, ActionSpec("Open my work", { s.router.navigate(Route.Handoff(h.id, HandoffTab.Work)) }))
                pending != null -> NextStepPanel("Waiting for ${h.ownerName}", "You’ll see here when ${h.ownerName} accepts or rejects ${rupees(pending.amountMinor)}. You can withdraw it.", "Proposal v${pending.version} · pending", null,
                    listOf(ActionSpec("Withdraw proposal", { signThen { s.repo.handoffAction(h.id, "withdraw") } })) +
                        listOfNotNull(app.relay.ui.auth.demoAction(s, null, h.ownerName, { "Switch to ${it.name} to respond" }, Route.Project(h.projectId, app.relay.nav.ProjectTab.Handoff))))
                else -> NextStepPanel("Accept ${rupees(fee)}?", if (user == null) "You’ll sign in with Google to lock in this version and its timestamp." else "Signed in as ${user.email}. Accepting locks in this version and its timestamp.", "Offer from ${h.ownerName}",
                    ActionSpec(if (user == null) "Continue with Google & accept" else "Accept ${rupees(fee)}", { signThen { s.repo.handoffAction(h.id, "accept"); s.router.navigate(Route.Handoff(h.id, HandoffTab.Work)) } }, loading = action.running),
                    listOf(ActionSpec("Propose a different fee", { proposeOpen = true }), ActionSpec("Decline", { declineOpen = true })))
            }
            if (user == null) app.relay.ui.auth.DemoPersonas()
            ContextPanel("Offer", "Replacement terms", "Item" to "Detail", listOf("Offer" to "v${offer?.version ?: 1} · ${rupees(offer?.amountMinor ?: h.feeMinor)}", "From" to h.ownerName, "Scope" to h.brief.work.joinToString { it.title }, "Link expires" to s.platform.formatDate(h.expiresAt)))
        },
    )
    if (proposeOpen) {
        var amount by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("") }
        val paise = parseRupeesToPaise(amount)
        RelayDialog("Propose a different fee", "${h.ownerName} sees your proposal and can accept or reject it. Nothing is binding until you both agree, and the ${rupees(fee)} offer stays in history.", { proposeOpen = false },
            ActionSpec("Send proposal", { signThen { s.repo.handoffAction(h.id, "propose", paise, reason); proposeOpen = false } }, enabled = paise != null && paise > 0, loading = action.running)) {
            RelayTextField(amount, { amount = it }, "Your proposed fee", Modifier.fillMaxWidth(), prefix = "₹", placeholder = "6,500")
            RelayTextField(reason, { reason = it }, "Reason for ${h.ownerName}", Modifier.fillMaxWidth(), placeholder = "The 390px frame needs its own form layout")
            ErrorText(action.error)
        }
    }
    if (declineOpen) {
        var reason by remember { mutableStateOf("") }
        RelayDialog("Decline this handoff?", "The project stays untouched and ${h.ownerName} can invite someone else.", { declineOpen = false },
            ActionSpec("Decline", { signThen { s.repo.handoffAction(h.id, "decline", reason = reason); declineOpen = false } }, loading = action.running)) {
            RelayTextField(reason, { reason = it }, "Reason (optional)", Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WorkTab(h: Handoff, subs: List<app.relay.model.Submission>) {
    val s = LocalServices.current
    val user = LocalUser.current
    val action = rememberAction()
    val links = remember(h.id) { mutableStateMapOf<String, String>() }
    var note by remember(h.id) { mutableStateOf("") }
    var tried by remember(h.id) { mutableStateOf(false) }
    val mine = user != null && h.replacementUid == user.uid
    val frames = h.brief.requiredDeliverables.filter { it != "Completion note" }
    fun ok(u: String?) = u != null && u.trim().contains('.') && !u.trim().contains(' ')
    val canSubmit = mine && h.status in listOf(HandoffStatus.ACCEPTED, HandoffStatus.CHANGES)
    val latest = subs.firstOrNull()
    WorkspaceLayout(
        main = {
            if (!mine) { TitleBlock("My work", "Accept the terms first", "Your workspace opens after you accept the fee."); return@WorkspaceLayout }
            if (h.acceptedAt != null && s.platform.now() - h.acceptedAt < 86_400_000) StatusLine("You’re on the project. Terms saved.", StatusKind.Done, "Replacement terms · ${rupees(h.agreedFeeMinor ?: h.feeMinor)} · ${s.platform.formatDateTime(h.acceptedAt)} · ${h.ownerName} notified")
            TitleBlock("Your work", h.brief.work.joinToString { it.title }, "${rupees(h.agreedFeeMinor ?: h.feeMinor)} · start from the approved work in the brief")
            if (h.status == HandoffStatus.CHANGES && latest?.reviewNote != null) Section("What ${h.ownerName} asked for") { MistPanel { RText(latest.reviewNote, RelayTheme.type.body) } }
            if (canSubmit) Section("Submission", "Everything ${h.ownerName} will review") {
                MistPanel {
                    frames.forEach { f ->
                        RelayTextField(links[f] ?: "", { links[f] = it }, "Figma link · $f", Modifier.fillMaxWidth(), placeholder = "https://figma.com/design/…",
                            error = if (tried && !ok(links[f])) "Add $f." else null, helper = "${h.ownerName} needs view access.")
                    }
                    RelayTextField(note, { note = it }, "Completion note", Modifier.fillMaxWidth(), singleLine = false, error = if (tried && note.isBlank()) "Add a completion note." else null)
                }
                frames.forEach { f -> CheckboxRow(ok(links[f]), {}, "$f linked", Modifier.fillMaxWidth(), if (ok(links[f])) "Required" else "Required · missing", error = tried && !ok(links[f])) }
                CheckboxRow(note.isNotBlank(), {}, "Completion note added", Modifier.fillMaxWidth(), "Required")
                if (h.brief.decisions.isNotEmpty()) CheckboxRow(true, {}, "Kept the ${h.brief.decisions.size} decisions", Modifier.fillMaxWidth(), h.brief.decisions.joinToString(" · ") { it.title })
            } else {
                Section("Your submission") { ListCard(subs.map { sub -> ListRowSpec(sub.label, sub.links.joinToString(" · ") { it.url }) { StatusPill(when (sub.status) { "approved" -> "Approved"; "revision_requested" -> "Changes requested"; else -> "In review" }, if (sub.status == "approved") StatusKind.Done else StatusKind.Pending) } }.ifEmpty { listOf(ListRowSpec("Nothing submitted yet") {}) }) }
            }
            ErrorText(action.error)
        },
        rail = {
            if (canSubmit) {
                val missing = frames.firstOrNull { !ok(links[it]) }
                NextStepPanel(if (missing != null && tried) "Add $missing" else "Submit for review", "${h.ownerName} reviews your links against the brief. Payment unlocks only after approval.", "${h.brief.work.firstOrNull()?.revisionLimit ?: 2} revisions included",
                    ActionSpec(if (h.status == HandoffStatus.CHANGES) "Resubmit for review" else "Submit for review", {
                        tried = true
                        if (frames.all { ok(links[it]) } && note.isNotBlank()) action.run {
                            s.repo.submitHandoffWork(h.id, frames.map { SubmissionLink(it, links[it]!!.trim().let { u -> if (u.startsWith("http")) u else "https://$u" }) }, note)
                            s.router.navigate(Route.Handoff(h.id, HandoffTab.Status))
                        }
                    }, loading = action.running))
            } else NextStepPanel(if (h.status == HandoffStatus.SUBMITTED) "Waiting for ${h.ownerName}" else "Nothing to do right now", "If ${h.ownerName} requests changes, you’ll see the note here and can resubmit.", null, ActionSpec("View status", { s.router.navigate(Route.Handoff(h.id, HandoffTab.Status)) }),
                listOfNotNull(if (h.status == HandoffStatus.SUBMITTED) app.relay.ui.auth.demoAction(s, null, h.ownerName, { "Switch to ${it.name} to review" },
                    subs.firstOrNull()?.let { Route.Project(h.projectId, app.relay.nav.ProjectTab.Review, it.id) } ?: Route.Project(h.projectId)) else null))
            ContextPanel("Status", h.brief.work.joinToString { it.title }, "Step" to "Status", statusRows(h, subs))
        },
    )
}

private fun statusRows(h: Handoff, subs: List<app.relay.model.Submission>) = listOf(
    "Terms" to (h.agreedFeeMinor?.let { "${rupees(it)} · accepted" } ?: "Not accepted"),
    "Submission" to (subs.firstOrNull()?.let { "${it.label}" } ?: "Not submitted"),
    "Approval" to when (h.status) { HandoffStatus.APPROVED, HandoffStatus.COMPLETED -> "Approved"; HandoffStatus.CHANGES -> "Changes requested"; HandoffStatus.SUBMITTED -> "Pending"; else -> "—" },
    "Payment" to if (h.status == HandoffStatus.COMPLETED) "Paid (verified)" else "Not paid",
)

@Composable
private fun StatusTab(h: Handoff, subs: List<app.relay.model.Submission>) {
    val s = LocalServices.current
    val done = { b: Boolean -> if (b) "Done" to StatusKind.Done else "Not yet" to StatusKind.NotStarted }
    val approved = h.status in listOf(HandoffStatus.APPROVED, HandoffStatus.COMPLETED)
    val paid = h.status == HandoffStatus.COMPLETED
    WorkspaceLayout(
        main = {
            TitleBlock("Status", h.brief.work.joinToString { it.title }, if (paid) "Approved by ${h.ownerName} and paid. Thanks for picking this up." else "Your progress on this handoff.")
            Section("Progress") {
                ListCard(listOf(
                    Triple("Terms accepted", h.agreedFeeMinor?.let { "${rupees(it)} · ${s.platform.formatDateTime(h.acceptedAt ?: 0)}" } ?: "Accept the fee on the Terms tab", done(h.agreedFeeMinor != null)),
                    Triple("Submitted for review", subs.firstOrNull()?.let { "${it.label} · ${s.platform.formatDateTime(it.submittedAt)}" } ?: "Links and completion note", done(subs.isNotEmpty())),
                    Triple(if (h.status == HandoffStatus.CHANGES) "Changes requested" else "In review by ${h.ownerName}", subs.firstOrNull()?.reviewNote ?: "You’ll be notified when they approve or request changes", if (h.status == HandoffStatus.SUBMITTED) "In review" to StatusKind.Pending else if (h.status == HandoffStatus.CHANGES) "Changes requested" to StatusKind.Attention else done(approved)),
                    Triple("Approved", "${h.ownerName} approves the submission", done(approved)),
                    Triple("Paid (verified)", "Only after Razorpay confirms the payment on Relay’s server", done(paid)),
                ).map { (t, m, st) -> ListRowSpec(t, m) { StatusPill(st.first, st.second) } })
            }
            if (paid) NeutralCard("Test payment, verified", Modifier.fillMaxWidth(), category = "About this payment", body = "This demo uses Razorpay test mode, so no real money moved. Payout to your bank isn’t automated in Relay.")
        },
        rail = {
            NextStepPanel(if (paid) "All done" else if (approved) "Approved — payment next" else "Keep going", if (paid) "${h.ownerName} approved your work and the payment is verified." else "Relay updates this page as things happen.", null)
            ContextPanel("Status", "Your milestone", "Step" to "Status", statusRows(h, subs))
        },
    )
}

@Composable
private fun Unused() = Column { RText("", RelayTheme.type.ui) }
