package app.relay.demo

import app.relay.data.AuthUser
import app.relay.data.Backend
import app.relay.data.Platform
import app.relay.data.RJson
import app.relay.data.RelayError
import app.relay.data.WriteOp
import app.relay.data.decodeAs
import app.relay.data.rupees
import app.relay.data.str
import app.relay.data.toJsonObject
import app.relay.model.AgreementVersion
import app.relay.model.Asset
import app.relay.model.AuditEvent
import app.relay.model.Brief
import app.relay.model.BriefDecision
import app.relay.model.BriefFile
import app.relay.model.BriefMilestone
import app.relay.model.Decision
import app.relay.model.DecisionStatus
import app.relay.model.FeeProposal
import app.relay.model.Handoff
import app.relay.model.HandoffStatus
import app.relay.model.Invite
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
import app.relay.model.SubmissionLink
import app.relay.model.SubmissionStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Demo version of Relay's Cloud Functions, run in the browser against [DemoDb].
 * Same rules and the same state changes as `functions/` (invites, agreements, reviews, handoffs,
 * payments), so every screen behaves exactly as it does on the real backend.
 *
 * Two things can't be real without a server, and are labelled as demo:
 *  • decision suggestions come from simple phrase matching, not Claude;
 *  • a Razorpay test payment is trusted from Checkout's callback (no signature check, no key secret here).
 */
class DemoBackend(
    private val db: DemoDb,
    private val auth: DemoAuthService,
    private val platform: Platform,
) : Backend {

    override suspend fun call(name: String, data: JsonObject): JsonElement {
        delay(350) // feels like a network round trip; spinners get a moment to show
        if (name == "handoffAction" && data.s("action") == "open") return openHandoff(data.req("token"))
        val user = auth.user ?: fail("Your session expired. Sign in again.")
        return when (name) {
            "acceptInvite" -> acceptInvite(user, data.req("token"))
            "acceptAgreementVersion" -> acceptAgreementVersion(user, data.req("projectId"), data.long("version")?.toInt() ?: 0)
            "rejectAgreementVersion" -> rejectAgreementVersion(user, data.req("projectId"), data.long("version")?.toInt() ?: 0)
            "extractDecisions" -> extractDecisions(user, data.req("projectId"))
            "reviewSubmission" -> reviewSubmission(user, data)
            "publishHandoff" -> publishHandoff(user, data)
            "handoffAction" -> handoffAction(user, data)
            "submitHandoffWork" -> submitHandoffWork(user, data)
            "createPaymentOrder" -> createPaymentOrder(user, data.req("projectId"), data.req("milestoneId"))
            "verifyPayment" -> verifyPayment(user, data)
            "refreshPaymentStatus" -> refreshPaymentStatus(data.req("projectId"), data.req("milestoneId"))
            else -> fail("That action isn’t part of the demo version.")
        }
    }

    // ── Agreements ──────────────────────────────────────────────────────────
    private suspend fun acceptInvite(user: AuthUser, token: String): JsonObject {
        val inv = load<Invite>("invites/$token") ?: fail("This invite link isn’t valid. Ask for a new link.")
        if (inv.status == "accepted") {
            if (inv.acceptedBy == user.uid) return obj { put("projectId", inv.projectId); put("already", true) }
            fail("This invite was already accepted by someone else.")
        }
        if (inv.status != "pending") fail("This invite was revoked. Ask for a new link.")
        if (inv.expiresAt < now()) fail("This invite has expired. Ask for a new link.")
        if (inv.ownerId == user.uid) fail("This is your own invite — send the link to your freelancer.")
        val p = project(inv.projectId)
        val v = p.agreementVersion
        val ag = load<AgreementVersion>("projects/${p.id}/agreements/v$v")
        if (ag == null || ag.status != "proposed") fail("These terms changed since the invite was sent. Ask for a new link.")
        val t = now()
        val ops = mutableListOf<WriteOp>(
            update("invites/$token") { put("status", "accepted"); put("acceptedBy", user.uid); put("acceptedAt", t) },
            update("projects/${p.id}/agreements/v$v") { put("status", "accepted"); put("acceptedBy", user.uid); put("acceptedByName", user.displayName); put("acceptedAt", t) },
            update("projects/${p.id}") {
                put("memberIds", RJson.encodeToJsonElement((p.memberIds + user.uid).distinct()))
                put("members", RJson.encodeToJsonElement(p.members + (user.uid to Member(user.uid, user.displayName, user.email, Roles.ORIGINAL, "active", t))))
                put("status", ProjectStatus.ACTIVE); put("agreementStatus", "accepted"); put("updatedAt", t)
            },
        )
        milestones(p.id).forEachIndexed { i, m ->
            ops += update(msPath(p.id, m.id)) {
                put("assigneeId", user.uid); put("assigneeName", user.displayName); put("assigneeRole", Roles.ORIGINAL)
                put("status", if (i == 0) MilestoneStatus.IN_PROGRESS else MilestoneStatus.NOT_STARTED)
            }
        }
        ops += audit(p.id, user.uid, user.displayName, "agreement_accepted", "v$v", "Version $v accepted")
        db.write(ops)
        return obj { put("projectId", p.id) }
    }

    private fun freelancerCheck(user: AuthUser, p: Project) {
        if (user.uid !in p.memberIds) fail("Access not permitted.")
        if (p.members[user.uid]?.role != Roles.ORIGINAL) fail("Only the freelancer on this agreement can respond to a change.")
    }

    private suspend fun acceptAgreementVersion(user: AuthUser, pid: String, version: Int): JsonObject {
        val p = project(pid); freelancerCheck(user, p)
        val a = load<AgreementVersion>("projects/$pid/agreements/v$version") ?: fail("That agreement version doesn’t exist.")
        if (a.status == "accepted" && a.acceptedBy == user.uid) return obj { put("already", true) }
        if (a.status != "proposed") fail("This version is no longer open.")
        val t = now()
        val ops = mutableListOf<WriteOp>()
        db.children("projects/$pid/agreements").forEach { (id, d) ->
            if (id != "v$version" && d.s("status") == "accepted") ops += update("projects/$pid/agreements/$id") { put("status", "superseded") }
        }
        ops += update("projects/$pid/agreements/v$version") { put("status", "accepted"); put("acceptedBy", user.uid); put("acceptedByName", user.displayName); put("acceptedAt", t) }
        val terms = a.terms.associateBy { it.milestoneId }
        milestones(pid).forEach { m ->
            terms[m.id]?.let { term ->
                ops += update(msPath(pid, m.id)) { put("title", term.title); put("deliverable", term.deliverable); put("amountMinor", term.amountMinor); put("revisionLimit", term.revisionLimit) }
            }
        }
        ops += update("projects/$pid") { put("agreementStatus", "accepted"); put("updatedAt", t) }
        ops += audit(pid, user.uid, user.displayName, "agreement_accepted", "v$version", "Version $version accepted")
        db.write(ops)
        return obj { put("accepted", version) }
    }

    private suspend fun rejectAgreementVersion(user: AuthUser, pid: String, version: Int): JsonObject {
        val p = project(pid); freelancerCheck(user, p)
        val a = load<AgreementVersion>("projects/$pid/agreements/v$version") ?: fail("That agreement version doesn’t exist.")
        if (a.status != "proposed") fail("This version is no longer open.")
        val t = now()
        db.write(listOf(
            update("projects/$pid/agreements/v$version") { put("status", "rejected"); put("acceptedBy", null as String?) },
            update("projects/$pid") { put("agreementStatus", "accepted"); put("updatedAt", t) },
            audit(pid, user.uid, user.displayName, "agreement_change_rejected", "v$version", "Version $version rejected — previous version stays in force"),
        ))
        return obj { put("rejected", version) }
    }

    // ── Decision suggestions (demo: phrase matching instead of Claude) ───────
    private suspend fun extractDecisions(user: AuthUser, pid: String): JsonObject {
        val p = project(pid)
        if (user.uid !in p.memberIds) fail("Access not permitted.")
        val statePath = "projects/$pid/private/extraction"
        val since = db.doc(statePath)?.long("lastRunAt") ?: 0L
        val all = list<Message>("projects/$pid/messages").sortedBy { it.createdAt }
        if (all.isEmpty()) return obj { put("created", 0); put("reason", "no_messages") }
        val existing = list<Decision>("projects/$pid/decisions").filter { it.status != DecisionStatus.REJECTED }
        val cited = existing.flatMap { it.sourceMessageIds }.toSet()
        val seen = existing.map { norm(it.title) }.toMutableSet()
        val t = now()
        val ops = mutableListOf<WriteOp>()
        for (m in all.filter { it.createdAt > since && it.id !in cited }) {
            if (ops.size >= 5) break
            val sentence = sentencesOf(m.body).firstOrNull { s -> CUES.any { s.lowercase().contains(it) } } ?: continue
            val title = titleOf(sentence)
            if (title.length < 6 || !seen.add(norm(title))) continue
            val id = db.newId()
            ops += WriteOp.Set("projects/$pid/decisions/$id", Decision(
                id = id, type = typeOf(sentence), title = title,
                summary = "Suggested from ${m.authorName}’s message. Check the wording before you confirm it.",
                sourceMessageIds = listOf(m.id), sourceQuote = sentence.take(300), sourceAuthor = m.authorName, sourceAt = m.createdAt,
                status = DecisionStatus.SUGGESTED, origin = "ai", createdAt = t,
            ).toJsonObject())
        }
        val created = ops.size
        ops += WriteOp.Set(statePath, buildJsonObject { put("lastRunAt", t); put("lastCreated", created) }, merge = true)
        if (created > 0) ops += audit(pid, "relay-ai", "Relay (demo suggestions)", "decisions_suggested", all.last().id, "$created suggested — waiting for review")
        db.write(ops)
        return obj { put("created", created) }
    }

    // ── Review ──────────────────────────────────────────────────────────────
    private suspend fun reviewSubmission(user: AuthUser, data: JsonObject): JsonObject {
        val pid = data.req("projectId"); val sid = data.req("submissionId")
        val approve = data["approve"]?.jsonPrimitive?.content == "true"
        val note = data.s("note")?.trim().orEmpty()
        val approvedLink = data.s("approvedLink")?.trim().orEmpty()
        if (!approve && note.isEmpty()) fail("Add a note.")
        val p = project(pid)
        if (p.ownerId != user.uid) fail("Only the client who owns this project can do that.")
        val s = load<Submission>("projects/$pid/submissions/$sid")?.copy(id = sid) ?: fail("That submission no longer exists.")
        if (s.status != SubmissionStatus.SUBMITTED) {
            if (approve && s.status == SubmissionStatus.APPROVED) return obj { put("status", "approved"); put("already", true) }
            fail("This submission was already reviewed.")
        }
        val m = load<Milestone>(msPath(pid, s.milestoneId)) ?: fail("That milestone no longer exists.")
        val handoffExists = s.handoffId != null && db.doc("handoffs/${s.handoffId}") != null
        var links = s.links.filter { isHttpUrl(it.url) }
        if (approve && approvedLink.isNotEmpty()) {
            if (!isHttpUrl(approvedLink)) fail("Enter a valid link to the approved file.")
            links = links + SubmissionLink("Approved file", approvedLink)
        }
        val linksJson = RJson.encodeToJsonElement(links)
        val t = now()
        val ops = mutableListOf<WriteOp>()
        if (approve) {
            if (links.isEmpty()) fail("Add the approved file before approving.")
            ops += update("projects/$pid/submissions/$sid") { put("status", SubmissionStatus.APPROVED); put("reviewedAt", t); put("reviewNote", note.ifEmpty { null }); put("links", linksJson) }
            ops += update(msPath(pid, s.milestoneId)) { put("status", MilestoneStatus.APPROVED); put("approvedSubmissionId", sid); put("approvedLabel", s.label); put("approvedAt", t) }
            val aid = db.newId()
            ops += WriteOp.Set("projects/$pid/assets/$aid", Asset(aid, s.label, links.first().url, "approved", s.milestoneId, user.uid, user.displayName, "Approved by ${user.displayName}", t).toJsonObject())
            if (handoffExists) {
                ops += update("handoffs/${s.handoffId}") { put("status", HandoffStatus.APPROVED) }
                ops += WriteOp.Set("handoffs/${s.handoffId}/submissions/$sid", buildJsonObject { put("status", SubmissionStatus.APPROVED); put("reviewedAt", t); put("reviewNote", note.ifEmpty { null }); put("links", linksJson) }, merge = true)
            }
            ops += audit(pid, user.uid, user.displayName, "milestone_approved", s.milestoneId, "${s.label} approved.")
            db.write(ops)
            return obj { put("status", "approved") }
        }
        ops += update("projects/$pid/submissions/$sid") { put("status", SubmissionStatus.CHANGES); put("reviewedAt", t); put("reviewNote", note) }
        ops += update(msPath(pid, s.milestoneId)) { put("status", MilestoneStatus.CHANGES); put("revisionsUsed", m.revisionsUsed + 1) }
        if (handoffExists) {
            ops += update("handoffs/${s.handoffId}") { put("status", HandoffStatus.CHANGES) }
            ops += WriteOp.Set("handoffs/${s.handoffId}/submissions/$sid", buildJsonObject { put("status", SubmissionStatus.CHANGES); put("reviewedAt", t); put("reviewNote", note) }, merge = true)
        }
        ops += audit(pid, user.uid, user.displayName, "changes_requested", s.milestoneId, note)
        db.write(ops)
        return obj { put("status", "revision_requested") }
    }

    // ── Handoffs ────────────────────────────────────────────────────────────
    private suspend fun publishHandoff(user: AuthUser, data: JsonObject): JsonObject {
        val pid = data.req("projectId")
        val milestoneIds = data.strings("milestoneIds")
        if (milestoneIds.isEmpty()) fail("Define remaining work — select at least one milestone.")
        val fee = data.long("feeMinor")?.takeIf { it > 0 } ?: fail("Enter a fee for the replacement above ₹0.")
        val include = data.strings("includeSections").ifEmpty { listOf("goal", "done", "decisions", "scope", "files") }.toSet()
        val excludedAssets = data.strings("excludedAssetIds").toSet()
        val excludedDecisions = data.strings("excludedDecisionIds").toSet()
        val sendTo = data.s("sendTo")?.trim().orEmpty()
        val p = project(pid)
        if (p.ownerId != user.uid) fail("Only the client who owns this project can do that.")
        p.activeHandoffId?.let { prev ->
            val st = db.doc("handoffs/$prev")?.s("status")
            if (st != null && st !in listOf(HandoffStatus.REVOKED, HandoffStatus.DECLINED, HandoffStatus.COMPLETED)) {
                fail("A handoff for this project is already open. Revoke it before creating another.")
            }
        }
        val ms = milestones(pid)
        val byId = ms.associateBy { it.id }
        for (id in milestoneIds) {
            val m = byId[id] ?: fail("One of the selected milestones no longer exists.")
            if (m.status == MilestoneStatus.APPROVED) fail("${m.title} is already approved — it can’t be handed off.")
        }
        val subs = list<Submission>("projects/$pid/submissions").associateBy { it.id }
        val assets = list<Asset>("projects/$pid/assets")
        val decisions = list<Decision>("projects/$pid/decisions")
        val includedFiles = if ("files" in include) assets.filter { it.id !in excludedAssets } else emptyList()
        includedFiles.firstOrNull { it.status == "no_access" }?.let { fail("Request access to this file: ${it.label}. Or exclude it from the brief.") }
        val approved = ms.filter { it.status == MilestoneStatus.APPROVED }
        fun bm(m: Milestone) = BriefMilestone(m.id, m.title, m.deliverable, m.amountMinor, m.revisionLimit)
        val brief = Brief(
            projectName = p.name,
            goal = if ("goal" in include) p.goal else "",
            clientName = p.ownerName,
            approvedWork = if ("done" in include) approved.map { m ->
                val url = m.approvedSubmissionId?.let { subs[it] }?.links?.firstOrNull { isHttpUrl(it.url) }?.url ?: ""
                BriefFile(m.approvedLabel ?: m.title, url, "approved", "${m.title} · approved by ${p.ownerName}")
            } else emptyList(),
            work = milestoneIds.map { bm(byId.getValue(it)) },
            requiredDeliverables = requiredDeliverables(milestoneIds.map { byId.getValue(it) }),
            decisions = if ("decisions" in include) decisions.filter { it.status == DecisionStatus.CONFIRMED && it.id !in excludedDecisions }
                .map { BriefDecision(it.title, it.summary, it.sourceQuote, it.sourceAuthor, it.sourceAt) } else emptyList(),
            notInScope = if ("scope" in include) ms.filter { it.id !in milestoneIds && it.status != MilestoneStatus.APPROVED }.map { bm(it) } else emptyList(),
            files = includedFiles.filter { a -> a.status != "approved" || approved.none { it.approvedLabel == a.label } }.map { BriefFile(it.label, it.url, it.status, it.note) },
            earlierPayments = approved.map { m ->
                when (m.paymentStatus) {
                    PaymentState.VERIFIED_TEST -> "${m.title} · Paid (verified) · ${rupees(m.paidVerifiedMinor.takeIf { it > 0 } ?: m.amountMinor)}"
                    PaymentState.RECORDED_EXTERNALLY -> "${m.title} · Paid outside Relay · ${rupees(m.paidExternallyMinor.takeIf { it > 0 } ?: m.amountMinor)}"
                    else -> "${m.title} · approved, not yet paid"
                }
            },
        )
        val excluded = assets.filter { it.id in excludedAssets }.map { "File: ${it.label}" } +
            decisions.filter { it.id in excludedDecisions }.map { "Decision: ${it.title}" } + "Full conversation history"
        val t = now()
        val token = platform.randomToken(30)
        val h = Handoff(
            id = token, projectId = pid, ownerId = user.uid, ownerName = p.ownerName.ifBlank { user.displayName }, status = HandoffStatus.PUBLISHED,
            milestoneIds = milestoneIds, brief = brief, excluded = excluded, feeMinor = fee,
            proposals = listOf(FeeProposal(1, fee, user.uid, user.displayName, Roles.OWNER, "", t, "offered")),
            sentTo = sendTo.ifBlank { null }, createdAt = t, publishedAt = t, expiresAt = t + SEVEN_DAYS,
        )
        val ops = mutableListOf<WriteOp>(
            WriteOp.Set("handoffs/$token", h.toJsonObject()),
            update("projects/$pid") { put("status", ProjectStatus.HANDOFF); put("activeHandoffId", token); put("updatedAt", t) },
        )
        milestoneIds.forEach { mid ->
            ops += update(msPath(pid, mid)) {
                put("status", MilestoneStatus.REASSIGNED); put("handoffId", token)
                put("assigneeId", null as String?); put("assigneeName", null as String?); put("assigneeRole", null as String?)
            }
        }
        ops += audit(pid, user.uid, user.displayName, "handoff_published", token, "${brief.work.joinToString { it.title }} · ${rupees(fee)} · ${excluded.size} items excluded")
        db.write(ops)
        return obj { put("token", token) }
    }

    private suspend fun openHandoff(token: String): JsonObject {
        val h = handoff(token)
        if (h.status in LIVE && h.expiresAt > now() && h.openedAt == null) db.write(listOf(update("handoffs/$token") { put("openedAt", now()) }))
        return obj { put("status", h.status) }
    }

    private suspend fun handoffAction(user: AuthUser, data: JsonObject): JsonObject {
        val token = data.req("token")
        val action = data.s("action").orEmpty()
        val h = handoff(token)
        val pid = h.projectId
        val t = now()
        val proposalsJson = { list: List<FeeProposal> -> RJson.encodeToJsonElement(list) }

        if (action in listOf("acceptProposal", "rejectProposal", "revoke", "reissue")) {
            if (h.ownerId != user.uid) fail("Only the client can do that.")
            val p = load<Project>("projects/$pid")?.copy(id = pid)
            when (action) {
                "acceptProposal", "rejectProposal" -> {
                    val pend = pendingProposal(h)
                    if (h.status != HandoffStatus.COUNTEROFFER || pend == null) fail("There’s no proposal waiting for you.")
                    val previous = currentOffer(h)?.amountMinor ?: h.feeMinor
                    if (action == "acceptProposal") {
                        db.write(listOf(
                            update("handoffs/$token") { put("status", HandoffStatus.PUBLISHED); put("feeMinor", pend.amountMinor); put("proposals", proposalsJson(setLatest(h, "proposed", "accepted"))) },
                            audit(pid, user.uid, user.displayName, "fee_proposal_accepted", token, "${rupees(pend.amountMinor)} accepted (v${pend.version}); ${rupees(previous)} offer kept in history"),
                        ))
                    } else {
                        db.write(listOf(
                            update("handoffs/$token") { put("status", HandoffStatus.PUBLISHED); put("proposals", proposalsJson(setLatest(h, "proposed", "rejected"))) },
                            audit(pid, user.uid, user.displayName, "fee_proposal_rejected", token, "${rupees(pend.amountMinor)} rejected; ${rupees(previous)} still offered"),
                        ))
                    }
                    return obj { put("status", HandoffStatus.PUBLISHED) }
                }
                "revoke" -> {
                    if (h.status !in LIVE) fail("Only a handoff that hasn’t been accepted can be revoked.")
                    val ops = mutableListOf<WriteOp>(update("handoffs/$token") { put("status", HandoffStatus.REVOKED) })
                    if (p?.activeHandoffId == token) ops += update("projects/$pid") { put("status", ProjectStatus.INTERRUPTED); put("activeHandoffId", null as String?); put("updatedAt", t) }
                    h.milestoneIds.forEach { ops += update(msPath(pid, it)) { put("status", MilestoneStatus.INTERRUPTED); put("handoffId", null as String?) } }
                    ops += audit(pid, user.uid, user.displayName, "handoff_revoked", token, "Link revoked — it no longer opens")
                    db.write(ops)
                    return obj { put("status", HandoffStatus.REVOKED) }
                }
                else -> { // reissue
                    if (h.status !in listOf(HandoffStatus.REVOKED, HandoffStatus.DECLINED) && h.expiresAt > t) fail("This link is still live — no need to reissue it.")
                    val other = p?.activeHandoffId?.takeIf { it != token }?.let { db.doc("handoffs/$it")?.s("status") }
                    if (other != null && other !in listOf(HandoffStatus.REVOKED, HandoffStatus.DECLINED, HandoffStatus.COMPLETED)) fail("Another handoff is already open.")
                    val newId = platform.randomToken(30)
                    val fee = currentOffer(h)?.amountMinor ?: h.feeMinor
                    val fresh = h.copy(
                        id = newId, status = HandoffStatus.PUBLISHED, feeMinor = fee,
                        proposals = listOf(FeeProposal(1, fee, user.uid, user.displayName, Roles.OWNER, "", t, "offered")),
                        agreedFeeMinor = null, replacementUid = null, replacementName = null, replacementEmail = null, sentTo = null,
                        openedAt = null, acceptedAt = null, createdAt = t, publishedAt = t, expiresAt = t + SEVEN_DAYS,
                    )
                    val ops = mutableListOf<WriteOp>(WriteOp.Set("handoffs/$newId", fresh.toJsonObject()))
                    if (h.status != HandoffStatus.REVOKED && h.status != HandoffStatus.DECLINED) ops += update("handoffs/$token") { put("status", HandoffStatus.REVOKED) }
                    ops += update("projects/$pid") { put("status", ProjectStatus.HANDOFF); put("activeHandoffId", newId); put("updatedAt", t) }
                    h.milestoneIds.forEach { ops += update(msPath(pid, it)) { put("status", MilestoneStatus.REASSIGNED); put("handoffId", newId) } }
                    ops += audit(pid, user.uid, user.displayName, "handoff_reissued", newId, "New private link issued")
                    db.write(ops)
                    return obj { put("token", newId) }
                }
            }
        }
        if (action !in listOf("accept", "propose", "withdraw", "decline")) fail("Unknown action.")

        // Replacement actions
        if (h.ownerId == user.uid) fail("This is your own handoff — share the link with the replacement.")
        if (h.replacementUid != null && h.replacementUid != user.uid) fail("This handoff was already taken by someone else.")
        if (action == "accept" && h.status == HandoffStatus.ACCEPTED && h.replacementUid == user.uid) return obj { put("status", "accepted"); put("already", true) }
        if (h.status !in LIVE) fail(when (h.status) {
            HandoffStatus.REVOKED -> "This handoff link has been revoked."
            HandoffStatus.DECLINED -> "This handoff was declined."
            else -> "This handoff is no longer open."
        })
        if (h.expiresAt < t) fail("This handoff link has expired. Ask the client to reissue it.")
        val who: JsonObjectBuilder.() -> Unit = { put("replacementUid", user.uid); put("replacementName", user.displayName); put("replacementEmail", user.email) }

        when (action) {
            "accept" -> {
                if (pendingProposal(h) != null) fail("Review latest proposal — a fee proposal is still waiting for the client.")
                val offer = currentOffer(h) ?: fail("There’s no fee on the table yet.")
                val ops = mutableListOf<WriteOp>(update("handoffs/$token") {
                    who(); put("status", HandoffStatus.ACCEPTED); put("acceptedAt", t); put("agreedFeeMinor", offer.amountMinor)
                    put("proposals", proposalsJson(h.proposals.map { if (it.version == offer.version) it.copy(status = "accepted") else it }))
                })
                h.milestoneIds.forEach { mid ->
                    ops += update(msPath(pid, mid)) {
                        put("assigneeId", user.uid); put("assigneeName", user.displayName); put("assigneeRole", Roles.REPLACEMENT)
                        put("status", MilestoneStatus.IN_PROGRESS); put("handoffId", token); put("replacementFeeMinor", offer.amountMinor); put("revisionsUsed", 0)
                    }
                }
                if (db.doc("projects/$pid") != null) ops += update("projects/$pid") { put("status", ProjectStatus.ACTIVE); put("updatedAt", t) }
                ops += audit(pid, user.uid, user.displayName, "handoff_accepted", token, "${user.displayName} accepted ${rupees(offer.amountMinor)} (terms v${offer.version})")
                db.write(ops)
                return obj { put("status", "accepted"); put("agreedFeeMinor", offer.amountMinor) }
            }
            "propose" -> {
                val amount = data.long("amountMinor")?.takeIf { it > 0 } ?: fail("Enter a fee above ₹0.")
                if (pendingProposal(h) != null) fail("Review latest proposal — only one proposal can be open at a time.")
                val reason = data.s("reason")?.trim().orEmpty()
                val version = (h.proposals.maxOfOrNull { it.version } ?: 0) + 1
                db.write(listOf(
                    update("handoffs/$token") {
                        who(); put("status", HandoffStatus.COUNTEROFFER)
                        put("proposals", proposalsJson(h.proposals + FeeProposal(version, amount, user.uid, user.displayName, Roles.REPLACEMENT, reason, t, "proposed")))
                    },
                    audit(pid, user.uid, user.displayName, "fee_proposed", token, "${user.displayName} proposed ${rupees(amount)} (v$version)"),
                ))
                return obj { put("status", HandoffStatus.COUNTEROFFER) }
            }
            "withdraw" -> {
                if (h.status != HandoffStatus.COUNTEROFFER || h.replacementUid != user.uid) fail("There’s no proposal of yours to withdraw.")
                db.write(listOf(
                    update("handoffs/$token") { put("status", HandoffStatus.PUBLISHED); put("proposals", proposalsJson(setLatest(h, "proposed", "withdrawn"))) },
                    audit(pid, user.uid, user.displayName, "fee_proposal_withdrawn", token, "${user.displayName} withdrew the proposal"),
                ))
                return obj { put("status", HandoffStatus.PUBLISHED) }
            }
            else -> { // decline
                val reason = data.s("reason")?.trim().orEmpty()
                val ops = mutableListOf<WriteOp>(update("handoffs/$token") { who(); put("status", HandoffStatus.DECLINED) })
                if (load<Project>("projects/$pid")?.activeHandoffId == token) ops += update("projects/$pid") { put("status", ProjectStatus.INTERRUPTED); put("activeHandoffId", null as String?); put("updatedAt", t) }
                h.milestoneIds.forEach { ops += update(msPath(pid, it)) { put("status", MilestoneStatus.INTERRUPTED) } }
                ops += audit(pid, user.uid, user.displayName, "handoff_declined", token, "${user.displayName} declined" + (if (reason.isNotEmpty()) ": $reason" else ""))
                db.write(ops)
                return obj { put("status", HandoffStatus.DECLINED) }
            }
        }
    }

    private suspend fun submitHandoffWork(user: AuthUser, data: JsonObject): JsonObject {
        val token = data.req("token")
        val note = data.s("note")?.trim().orEmpty()
        val links = ((data["links"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            SubmissionLink(o.s("label")?.trim()?.take(80).orEmpty(), o.s("url")?.trim().orEmpty()).takeIf { it.url.isNotEmpty() }
        }
        links.firstOrNull { !isHttpUrl(it.url) }?.let { fail("“${it.label.ifEmpty { it.url }}” isn’t a valid link.") }
        val h = handoff(token)
        if (h.replacementUid != user.uid) fail("Only the person who accepted this handoff can submit work.")
        if (h.status !in listOf(HandoffStatus.ACCEPTED, HandoffStatus.CHANGES)) {
            fail(if (h.status == HandoffStatus.SUBMITTED) "Your submission is already in review." else "This handoff isn’t open for submissions.")
        }
        for (r in h.brief.requiredDeliverables) {
            if (r == "Completion note") { if (note.isEmpty()) fail("Add a completion note."); continue }
            val size = Regex("(\\d{3,4})px").find(r)?.groupValues?.get(1)
            val ok = if (size != null) links.any { it.label.contains(size) } else links.isNotEmpty()
            if (!ok) fail("Add $r.")
        }
        val pid = h.projectId
        val mid = h.milestoneIds.firstOrNull() ?: fail("This handoff has no milestone.")
        val prior = list<Submission>("projects/$pid/submissions").count { it.handoffId == token }
        val t = now()
        val sid = db.newId()
        val title = h.brief.work.joinToString { it.title }.ifEmpty { "Handoff work" }
        val sub = Submission(sid, mid, "$title · v${prior + 1}", user.uid, user.displayName, Roles.REPLACEMENT, links, note, SubmissionStatus.SUBMITTED, t, handoffId = token).toJsonObject()
        val ops = mutableListOf<WriteOp>(
            WriteOp.Set("projects/$pid/submissions/$sid", sub),
            WriteOp.Set("handoffs/$token/submissions/$sid", sub),
        )
        h.milestoneIds.forEach { ops += update(msPath(pid, it)) { put("status", MilestoneStatus.SUBMITTED) } }
        ops += update("handoffs/$token") { put("status", HandoffStatus.SUBMITTED) }
        ops += audit(pid, user.uid, user.displayName, "submission_created", sid, "$title · v${prior + 1} submitted by ${user.displayName}")
        db.write(ops)
        return obj { put("submissionId", sid) }
    }

    // ── Payments (Razorpay test mode, demo verification) ────────────────────
    private fun testRecords(pid: String, mid: String) =
        list<PaymentRecord>("projects/$pid/payments").filter { it.milestoneId == mid && it.mode == "test" }.sortedByDescending { it.createdAt }

    private fun verifiedResult(r: PaymentRecord) = obj {
        put("status", "verified"); put("orderId", r.orderId); put("paymentId", r.paymentId); put("verifiedAt", r.verifiedAt)
        put("amountMinor", r.amountMinor); put("currency", r.currency)
    }

    private suspend fun createPaymentOrder(user: AuthUser, pid: String, mid: String): JsonObject {
        val p = project(pid)
        if (p.ownerId != user.uid) fail("Only the client who owns this project can pay.")
        val m = load<Milestone>(msPath(pid, mid))?.copy(id = mid) ?: fail("That milestone no longer exists.")
        if (m.paymentStatus == PaymentState.VERIFIED_TEST) {
            val rec = testRecords(pid, mid).firstOrNull { it.status == PaymentState.VERIFIED_TEST }
            return JsonObject((rec?.let { verifiedResult(it) } ?: obj { put("status", "verified") }) + ("alreadyPaid" to JsonPrimitive(true)))
        }
        if (m.paymentStatus == PaymentState.RECORDED_EXTERNALLY) fail("This milestone is already recorded as paid outside Relay, so there’s nothing to pay here.")
        if (m.status != MilestoneStatus.APPROVED) fail("Approve this milestone before paying. Payment unlocks after approval.")
        val amount = m.replacementFeeMinor?.takeIf { it > 0 } ?: m.amountMinor
        val payload = { orderId: String -> obj {
            put("status", "created"); put("orderId", orderId); put("amountMinor", amount); put("currency", m.currency.ifBlank { "INR" })
            put("keyId", platform.razorpayKeyId.ifBlank { "rzp_test_demo" }); put("name", "Relay")
            put("description", "${m.title} · ${p.name} · test mode (demo)")
        } }
        testRecords(pid, mid).firstOrNull { it.status in OPEN && it.amountMinor == amount }?.orderId?.let { return payload(it) }
        val orderId = "order_demo_" + platform.randomToken(14)
        val t = now()
        db.write(listOf(
            WriteOp.Set("projects/$pid/payments/$orderId", PaymentRecord(
                id = orderId, milestoneId = mid, amountMinor = amount, currency = "INR", mode = "test", status = PaymentState.CREATED,
                orderId = orderId, payee = m.assigneeName ?: "", recordedBy = user.uid, createdAt = t, updatedAt = t,
            ).toJsonObject()),
            audit(pid, user.uid, user.displayName, "payment_order_created", mid, "${rupees(amount)} · $orderId · test mode"),
        ))
        return payload(orderId)
    }

    private suspend fun verifyPayment(user: AuthUser, data: JsonObject): JsonObject {
        val pid = data.req("projectId"); val mid = data.req("milestoneId"); val orderId = data.req("orderId")
        val paymentId = data.s("paymentId").orEmpty()
        if (project(pid).ownerId != user.uid) fail("Only the client who owns this project can pay.")
        val rec = load<PaymentRecord>("projects/$pid/payments/$orderId")
        if (rec == null || rec.milestoneId != mid || rec.mode != "test") fail("This payment doesn’t belong to this milestone.")
        if (rec.status == PaymentState.VERIFIED_TEST) return verifiedResult(rec)
        if (paymentId.isBlank()) {
            db.write(listOf(update("projects/$pid/payments/$orderId") { put("status", PaymentState.NOT_VERIFIED); put("failureReason", "missing_payment"); put("updatedAt", now()) }))
            return obj { put("status", "not_verified"); put("orderId", orderId); put("reason", "missing_payment") }
        }
        val m = load<Milestone>(msPath(pid, mid))
        val t = now()
        val ops = mutableListOf<WriteOp>(
            update("projects/$pid/payments/$orderId") {
                put("status", PaymentState.VERIFIED_TEST); put("paymentId", paymentId); put("verifiedAt", t); put("failureReason", null as String?); put("updatedAt", t)
            },
            update(msPath(pid, mid)) { put("paymentStatus", PaymentState.VERIFIED_TEST); put("paidVerifiedMinor", rec.amountMinor) },
        )
        m?.handoffId?.let { hid -> if (db.doc("handoffs/$hid") != null) ops += update("handoffs/$hid") { put("status", HandoffStatus.COMPLETED) } }
        ops += audit(pid, "relay-server", "Relay server", "payment_verified", mid, "${rupees(rec.amountMinor)} · $paymentId · test mode")
        db.write(ops)
        return obj { put("status", "verified"); put("orderId", orderId); put("paymentId", paymentId); put("verifiedAt", t); put("amountMinor", rec.amountMinor); put("currency", rec.currency) }
    }

    private fun refreshPaymentStatus(pid: String, mid: String): JsonObject {
        val records = testRecords(pid, mid)
        records.firstOrNull { it.status == PaymentState.VERIFIED_TEST }?.let { return verifiedResult(it) }
        val latest = records.firstOrNull() ?: return obj { put("status", "none") }
        return obj {
            put("status", when (latest.status) {
                PaymentState.PENDING -> "pending"
                PaymentState.FAILED -> "failed"
                PaymentState.NOT_VERIFIED -> "not_verified"
                else -> "not_paid"
            })
            put("orderId", latest.orderId); put("reason", latest.failureReason)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun now() = platform.now()
    private fun fail(message: String): Nothing = throw RelayError(message)
    private inline fun <reified T> load(path: String): T? = db.doc(path)?.decodeAs<T>()
    private inline fun <reified T> list(collection: String): List<T> = db.children(collection).map { (id, d) -> withId(d, id).decodeAs<T>() }
    private fun withId(d: JsonObject, id: String) = JsonObject(d + ("id" to JsonPrimitive(id)))
    private fun project(pid: String): Project = load<Project>("projects/$pid")?.copy(id = pid) ?: fail("This project doesn’t exist.")
    private fun handoff(token: String): Handoff = load<Handoff>("handoffs/$token")?.copy(id = token) ?: fail("This handoff link isn’t valid.")
    private fun milestones(pid: String) = list<Milestone>("projects/$pid/milestones").sortedBy { it.index }
    private fun msPath(pid: String, mid: String) = "projects/$pid/milestones/$mid"
    private fun update(path: String, block: JsonObjectBuilder.() -> Unit) = WriteOp.Update(path, buildJsonObject(block))
    private fun obj(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    private fun audit(pid: String, actorId: String, actorName: String, type: String, entityId: String, detail: String): WriteOp {
        val id = db.newId()
        return WriteOp.Set("projects/$pid/events/$id", AuditEvent(id, actorId, actorName, type, entityId, detail.take(300), now()).toJsonObject())
    }

    private fun currentOffer(h: Handoff) = h.proposals.lastOrNull { it.status == "offered" || it.status == "accepted" }
    private fun pendingProposal(h: Handoff) = h.proposals.firstOrNull { it.status == "proposed" }
    private fun setLatest(h: Handoff, from: String, to: String): List<FeeProposal> {
        val i = h.proposals.indexOfLast { it.status == from }
        return h.proposals.mapIndexed { idx, x -> if (idx == i) x.copy(status = to) else x }
    }

    private fun requiredDeliverables(ms: List<Milestone>): List<String> {
        val out = mutableListOf<String>()
        for (m in ms) {
            val sizes = Regex("(\\d{3,4})\\s?px", RegexOption.IGNORE_CASE).findAll(m.deliverable).map { "${it.groupValues[1]}px frame" }.toList()
            if (sizes.isNotEmpty()) out += sizes else out += m.deliverable.ifBlank { m.title }
        }
        out += "Completion note"
        return out.distinct()
    }

    private fun isHttpUrl(u: String): Boolean {
        val v = u.trim()
        if (v.isEmpty() || v.length > 2048 || v.any { it.isWhitespace() }) return false
        val rest = v.removePrefix("https://").removePrefix("http://")
        return rest.substringBefore('/').contains('.')
    }

    private fun sentencesOf(body: String) =
        Regex("[^.!?\\n]+[.!?]?").findAll(body).map { it.value.trim() }.filter { it.length >= 8 }.toList()

    private fun titleOf(sentence: String): String {
        var s = sentence.trim().trimEnd('.', '!')
        var changed = true
        while (changed) {
            changed = false
            for (f in FILLERS) if (s.lowercase().startsWith(f)) { s = s.substring(f.length).trimStart(); changed = true }
        }
        s = s.replaceFirstChar { it.uppercase() }
        if (s.length > 80) s = s.take(80).substringBeforeLast(' ') + "…"
        return s
    }

    private fun typeOf(s: String): String {
        val l = s.lowercase()
        return when {
            "?" in l -> "open_question"
            "approve" in l -> "approval_candidate"
            listOf("replace", "change", "instead", "switch", "remove").any { it in l } -> "revision"
            listOf("must", "need", "should", "please").any { it in l } -> "requirement"
            else -> "decision"
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun JsonObject.s(k: String): String? = this[k].str()
    private fun JsonObject.req(k: String): String = s(k)?.takeIf { it.isNotBlank() } ?: fail("Missing or invalid $k.")
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()

    companion object {
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
        private val LIVE = listOf(HandoffStatus.PUBLISHED, HandoffStatus.COUNTEROFFER)
        private val OPEN = setOf(PaymentState.CREATED, PaymentState.PENDING, PaymentState.FAILED, PaymentState.NOT_VERIFIED)
        private val CUES = listOf(
            "keep", "use ", "let’s", "let's", "lets ", "should", "must", "need", "drop", "replace", "remove", "stack",
            "go with", "approve", "make ", "add ", "change", "switch", "instead", "please", "decided", "agree",
        )
        private val FILLERS = listOf("yes, ", "yes ", "ok, ", "okay, ", "and ", "also, ", "also ", "please ", "so ", "hmm, ", "i think ", "let’s ", "let's ", "lets ")
    }
}
