package app.relay.data

import app.relay.model.AgreementVersion
import app.relay.model.Asset
import app.relay.model.AuditEvent
import app.relay.model.Decision
import app.relay.model.DecisionStatus
import app.relay.model.Handoff
import app.relay.model.Invite
import app.relay.model.Member
import app.relay.model.Message
import app.relay.model.Milestone
import app.relay.model.MilestoneStatus
import app.relay.model.MilestoneTerms
import app.relay.model.PaymentRecord
import app.relay.model.PaymentState
import app.relay.model.Project
import app.relay.model.ProjectStatus
import app.relay.model.Roles
import app.relay.model.Submission
import app.relay.model.SubmissionLink
import app.relay.model.SubmissionStatus
import app.relay.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

val RJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }

inline fun <reified T> JsonObject.decodeAs(): T = RJson.decodeFromJsonElement(this)
inline fun <reified T> T.toJsonObject(): JsonObject = RJson.encodeToJsonElement(this).jsonObject

/** Thrown when a write is rejected — the UI shows the message and never a false success. */
class RelayError(message: String) : Exception(message)

/**
 * All Relay business logic that runs in the client. Privileged, cross-party or money-related
 * transitions go through Cloud Functions (`backend.call`) so they are verified server-side.
 */
class RelayRepository(val db: Db, val backend: Backend, val platform: Platform) {

    // ── Users ───────────────────────────────────────────────────────────────
    fun observeProfile(uid: String): Flow<UserProfile?> = db.observe("users/$uid").map { it?.decodeAs<UserProfile>() }

    suspend fun ensureProfile(user: AuthUser): UserProfile {
        val existing = db.get("users/${user.uid}")?.decodeAs<UserProfile>()
        if (existing != null) return existing
        val p = UserProfile(user.uid, user.displayName, user.email, user.photoUrl, createdAt = platform.now())
        db.write(listOf(WriteOp.Set("users/${user.uid}", p.toJsonObject())))
        return p
    }

    suspend fun updateProfile(uid: String, intent: String? = null, introSeen: Boolean? = null) {
        val data = buildJsonObject {
            if (intent != null) put("intent", intent)
            if (introSeen != null) put("introSeen", introSeen)
        }
        db.write(listOf(WriteOp.Set("users/$uid", data, merge = true)))
    }

    // ── Projects ────────────────────────────────────────────────────────────
    fun observeMyProjects(uid: String): Flow<List<Project>> =
        db.observeQuery(QuerySpec("projects", listOf(Where("memberIds", "array-contains", JsonPrimitive(uid)))))
            .map { docs -> docs.map { it.data.decodeAs<Project>().copy(id = it.id) }.sortedByDescending { it.updatedAt } }

    fun observeProject(pid: String): Flow<Project?> = db.observe("projects/$pid").map { it?.decodeAs<Project>()?.copy(id = pid) }

    suspend fun getProject(pid: String): Project? = db.get("projects/$pid")?.decodeAs<Project>()?.copy(id = pid)

    suspend fun createProject(user: AuthUser, name: String, goal: String): String {
        val id = db.newId()
        val now = platform.now()
        val project = Project(
            id = id, name = name.trim(), goal = goal.trim(), ownerId = user.uid, ownerName = user.displayName,
            status = ProjectStatus.DRAFT, memberIds = listOf(user.uid),
            members = mapOf(user.uid to Member(user.uid, user.displayName, user.email, Roles.OWNER, "active", now)),
            createdAt = now, updatedAt = now,
        )
        db.write(listOf(WriteOp.Set("projects/$id", project.toJsonObject()), audit(id, user, "project_created", id, name)))
        return id
    }

    suspend fun updateProjectBasics(pid: String, name: String, goal: String) {
        db.write(listOf(WriteOp.Update("projects/$pid", buildJsonObject {
            put("name", name.trim()); put("goal", goal.trim()); put("updatedAt", platform.now())
        })))
    }

    // ── Milestones ──────────────────────────────────────────────────────────
    fun observeMilestones(pid: String): Flow<List<Milestone>> =
        db.observeQuery(QuerySpec("projects/$pid/milestones")).map { docs -> docs.map { it.data.decodeAs<Milestone>().copy(id = it.id) }.sortedBy { it.index } }

    suspend fun saveDraftMilestones(pid: String, items: List<Milestone>, removedIds: List<String>) {
        val ops = mutableListOf<WriteOp>()
        items.forEachIndexed { i, m ->
            val id = m.id.ifEmpty { db.newId() }
            ops += WriteOp.Set("projects/$pid/milestones/$id", m.copy(id = id, index = i + 1, status = MilestoneStatus.DRAFT).toJsonObject())
        }
        removedIds.forEach { ops += WriteOp.Delete("projects/$pid/milestones/$it") }
        ops += WriteOp.Update("projects/$pid", buildJsonObject { put("updatedAt", platform.now()) })
        db.write(ops)
    }

    // ── Agreement & invite (F1 → F2) ────────────────────────────────────────
    fun observeAgreements(pid: String): Flow<List<AgreementVersion>> =
        db.observeQuery(QuerySpec("projects/$pid/agreements")).map { docs -> docs.map { it.data.decodeAs<AgreementVersion>().copy(id = it.id) }.sortedByDescending { it.version } }

    /** Saves the terms as a *proposed* version and creates an unguessable, expiring invite. Nothing is binding yet. */
    suspend fun proposeAgreement(user: AuthUser, project: Project, milestones: List<Milestone>, inviteEmail: String): String {
        val now = platform.now()
        val version = project.agreementVersion + 1
        val token = project.inviteToken?.takeIf { project.agreementStatus != "accepted" } ?: platform.randomToken(28)
        val terms = milestones.map { MilestoneTerms(it.id, it.title, it.deliverable, it.amountMinor, it.revisionLimit) }
        val agreement = AgreementVersion(
            id = "v$version", version = version, status = "proposed", terms = terms, totalMinor = terms.sumOf { it.amountMinor },
            proposedBy = user.uid, proposedByName = user.displayName, proposedAt = now,
        )
        val invite = Invite(
            token = token, projectId = project.id, projectName = project.name, projectGoal = project.goal,
            ownerId = user.uid, ownerName = user.displayName, email = inviteEmail.trim(), role = Roles.ORIGINAL,
            terms = terms, totalMinor = agreement.totalMinor, agreementVersion = version,
            status = "pending", createdAt = now, expiresAt = now + SEVEN_DAYS,
        )
        val ops = mutableListOf<WriteOp>(
            WriteOp.Set("projects/${project.id}/agreements/v$version", agreement.toJsonObject()),
            WriteOp.Set("invites/$token", invite.toJsonObject()),
            WriteOp.Update("projects/${project.id}", buildJsonObject {
                put("status", ProjectStatus.WAITING); put("agreementStatus", "proposed"); put("agreementVersion", version)
                put("inviteToken", token); put("inviteEmail", inviteEmail.trim()); put("updatedAt", now)
            }),
            audit(project.id, user, "agreement_proposed", "v$version", "₹" + formatInr(agreement.totalMinor)),
        )
        milestones.forEach { ops += WriteOp.Update("projects/${project.id}/milestones/${it.id}", buildJsonObject { put("status", MilestoneStatus.DRAFT) }) }
        db.write(ops)
        return token
    }

    /** Proposes a change to an accepted agreement → a new version that needs re-acceptance (PRD AC-002). */
    suspend fun proposeChange(user: AuthUser, project: Project, milestones: List<Milestone>, note: String) {
        val now = platform.now()
        val version = project.agreementVersion + 1
        val terms = milestones.map { MilestoneTerms(it.id, it.title, it.deliverable, it.amountMinor, it.revisionLimit) }
        val agreement = AgreementVersion(
            id = "v$version", version = version, status = "proposed", terms = terms, totalMinor = terms.sumOf { it.amountMinor },
            proposedBy = user.uid, proposedByName = user.displayName, proposedAt = now, changeNote = note,
        )
        db.write(listOf(
            WriteOp.Set("projects/${project.id}/agreements/v$version", agreement.toJsonObject()),
            WriteOp.Update("projects/${project.id}", buildJsonObject { put("agreementStatus", "proposed"); put("agreementVersion", version); put("updatedAt", now) }),
            audit(project.id, user, "agreement_change_proposed", "v$version", note),
        ))
    }

    suspend fun getInvite(token: String): Invite? = db.get("invites/$token")?.decodeAs<Invite>()

    /** Server-side: verifies the token, adds the freelancer, records acceptance + timestamp as Agreement vN. */
    suspend fun acceptInvite(token: String): String =
        (backend.call("acceptInvite", buildJsonObject { put("token", token) }) as JsonObject)["projectId"].toString().trim('"')

    suspend fun acceptAgreementVersion(projectId: String, version: Int) {
        backend.call("acceptAgreementVersion", buildJsonObject { put("projectId", projectId); put("version", version) })
    }

    suspend fun rejectAgreementVersion(projectId: String, version: Int) {
        backend.call("rejectAgreementVersion", buildJsonObject { put("projectId", projectId); put("version", version) })
    }

    // ── Conversation & decisions (F3) ───────────────────────────────────────
    fun observeMessages(pid: String): Flow<List<Message>> =
        db.observeQuery(QuerySpec("projects/$pid/messages", orderBy = "createdAt")).map { d -> d.map { it.data.decodeAs<Message>().copy(id = it.id) } }

    suspend fun sendMessage(user: AuthUser, project: Project, body: String, requestId: String): String {
        val id = "m_$requestId"
        val role = project.members[user.uid]?.role ?: ""
        val msg = Message(id, user.uid, user.displayName, role, body.trim(), platform.now(), requestId)
        db.write(listOf(WriteOp.Set("projects/${project.id}/messages/$id", msg.toJsonObject())))
        return id
    }

    fun observeDecisions(pid: String): Flow<List<Decision>> =
        db.observeQuery(QuerySpec("projects/$pid/decisions")).map { d -> d.map { it.data.decodeAs<Decision>().copy(id = it.id) }.sortedByDescending { it.createdAt } }

    suspend fun requestExtraction(pid: String): JsonElement = backend.call("extractDecisions", buildJsonObject { put("projectId", pid) })

    suspend fun reviewDecision(user: AuthUser, pid: String, decision: Decision, status: String, title: String? = null, summary: String? = null, note: String? = null) {
        val now = platform.now()
        db.write(listOf(
            WriteOp.Update("projects/$pid/decisions/${decision.id}", buildJsonObject {
                put("status", status)
                if (title != null) put("title", title)
                if (summary != null) put("summary", summary)
                if (note != null) put("reviewNote", note)
                put("confirmedBy", user.uid); put("confirmedByName", user.displayName); put("confirmedAt", now)
            }),
            audit(pid, user, "decision_$status", decision.id, decision.title),
        ))
    }

    /** Resolve a conflict: confirm the chosen option, reject the alternative. */
    suspend fun resolveConflict(user: AuthUser, pid: String, decision: Decision, chosen: String) {
        reviewDecision(user, pid, decision, DecisionStatus.CONFIRMED, title = chosen, note = "Chosen over: " + decision.options.filter { it != chosen }.joinToString())
    }

    suspend fun addManualDecision(user: AuthUser, pid: String, title: String, sourceQuote: String, sourceAuthor: String, confirm: Boolean) {
        val id = db.newId(); val now = platform.now()
        val d = Decision(
            id = id, type = "decision", title = title.trim(), summary = "", sourceQuote = sourceQuote.trim(), sourceAuthor = sourceAuthor,
            sourceAt = now, status = if (confirm) DecisionStatus.CONFIRMED else DecisionStatus.SUGGESTED, origin = "manual",
            confirmedBy = if (confirm) user.uid else null, confirmedByName = if (confirm) user.displayName else null, confirmedAt = if (confirm) now else null,
            createdAt = now,
        )
        db.write(listOf(WriteOp.Set("projects/$pid/decisions/$id", d.toJsonObject()), audit(pid, user, "decision_added_manually", id, title)))
    }

    // ── Files & evidence ────────────────────────────────────────────────────
    fun observeAssets(pid: String): Flow<List<Asset>> =
        db.observeQuery(QuerySpec("projects/$pid/assets")).map { d -> d.map { it.data.decodeAs<Asset>().copy(id = it.id) }.sortedByDescending { it.createdAt } }

    suspend fun addAsset(user: AuthUser, pid: String, label: String, url: String, status: String, note: String, milestoneId: String? = null) {
        val id = db.newId()
        val a = Asset(id, label.trim(), url.trim(), status, milestoneId, user.uid, user.displayName, note.trim(), platform.now())
        db.write(listOf(WriteOp.Set("projects/$pid/assets/$id", a.toJsonObject()), audit(pid, user, "asset_added", id, label)))
    }

    // ── Submissions & review (F4) ───────────────────────────────────────────
    fun observeSubmissions(pid: String): Flow<List<Submission>> =
        db.observeQuery(QuerySpec("projects/$pid/submissions")).map { d -> d.map { it.data.decodeAs<Submission>().copy(id = it.id) }.sortedByDescending { it.submittedAt } }

    suspend fun submitWork(user: AuthUser, project: Project, milestone: Milestone, label: String, links: List<SubmissionLink>, note: String) {
        val id = db.newId(); val now = platform.now()
        val sub = Submission(id, milestone.id, label, user.uid, user.displayName, project.members[user.uid]?.role ?: Roles.ORIGINAL, links, note.trim(), SubmissionStatus.SUBMITTED, now)
        db.write(listOf(
            WriteOp.Set("projects/${project.id}/submissions/$id", sub.toJsonObject()),
            WriteOp.Update("projects/${project.id}/milestones/${milestone.id}", buildJsonObject { put("status", MilestoneStatus.SUBMITTED) }),
            audit(project.id, user, "submission_created", id, label),
        ))
    }

    /** [approvedLink] is the evidence the client adds when the submission has no usable link (server rejects approval without one). */
    suspend fun reviewSubmission(projectId: String, submissionId: String, approve: Boolean, note: String, approvedLink: String = "") {
        backend.call("reviewSubmission", buildJsonObject {
            put("projectId", projectId); put("submissionId", submissionId); put("approve", approve); put("note", note)
            if (approvedLink.isNotBlank()) put("approvedLink", approvedLink)
        })
    }

    fun observePayments(pid: String): Flow<List<PaymentRecord>> =
        db.observeQuery(QuerySpec("projects/$pid/payments")).map { d -> d.map { it.data.decodeAs<PaymentRecord>().copy(id = it.id) }.sortedByDescending { it.createdAt } }

    /** A manual record. Always labelled "Paid outside Relay" — never shown as verified. */
    suspend fun recordExternalPayment(user: AuthUser, pid: String, milestone: Milestone, amountMinor: Long, note: String) {
        val id = db.newId(); val now = platform.now()
        val rec = PaymentRecord(id, milestone.id, amountMinor, "INR", "external_record", PaymentState.RECORDED_EXTERNALLY, payee = milestone.assigneeName ?: "", note = note.trim(), recordedBy = user.uid, createdAt = now)
        db.write(listOf(
            WriteOp.Set("projects/$pid/payments/$id", rec.toJsonObject()),
            WriteOp.Update("projects/$pid/milestones/${milestone.id}", buildJsonObject {
                put("paymentStatus", PaymentState.RECORDED_EXTERNALLY); put("paidExternallyMinor", amountMinor)
            }),
            audit(pid, user, "payment_recorded_externally", milestone.id, formatInr(amountMinor)),
        ))
    }

    // ── Interruption & handoff (F5) ─────────────────────────────────────────
    suspend fun markInterrupted(user: AuthUser, project: Project, freelancerUid: String, reason: String) {
        val now = platform.now()
        val member = project.members[freelancerUid] ?: throw RelayError("That person isn't on this project.")
        val updatedMembers = project.members + (freelancerUid to member.copy(status = "interrupted"))
        db.write(listOf(
            WriteOp.Update("projects/${project.id}", buildJsonObject {
                put("status", ProjectStatus.INTERRUPTED); put("members", RJson.encodeToJsonElement(updatedMembers)); put("updatedAt", now)
            }),
            WriteOp.Set("projects/${project.id}/private/interruption", buildJsonObject {
                put("freelancerUid", freelancerUid); put("reason", reason); put("at", now); put("by", user.uid)
            }),
            audit(project.id, user, "assignment_interrupted", freelancerUid, "History and payments kept"),
        ))
    }

    fun observeHandoffs(pid: String, ownerId: String): Flow<List<Handoff>> =
        db.observeQuery(QuerySpec("handoffs", listOf(Where("projectId", "==", JsonPrimitive(pid)), Where("ownerId", "==", JsonPrimitive(ownerId)))))
            .map { d -> d.map { it.data.decodeAs<Handoff>().copy(id = it.id) }.sortedByDescending { it.createdAt } }

    fun observeHandoff(token: String): Flow<Handoff?> = db.observe("handoffs/$token").map { it?.decodeAs<Handoff>()?.copy(id = token) }

    suspend fun publishHandoff(projectId: String, milestoneIds: List<String>, feeMinor: Long, includeSections: List<String>, excludedAssetIds: List<String>, excludedDecisionIds: List<String>, sendTo: String): String {
        val res = backend.call("publishHandoff", buildJsonObject {
            put("projectId", projectId); put("feeMinor", feeMinor); put("sendTo", sendTo)
            put("milestoneIds", JsonArray(milestoneIds.map { JsonPrimitive(it) }))
            put("includeSections", JsonArray(includeSections.map { JsonPrimitive(it) }))
            put("excludedAssetIds", JsonArray(excludedAssetIds.map { JsonPrimitive(it) }))
            put("excludedDecisionIds", JsonArray(excludedDecisionIds.map { JsonPrimitive(it) }))
        }) as JsonObject
        return res["token"].toString().trim('"')
    }

    suspend fun handoffAction(token: String, action: String, amountMinor: Long? = null, reason: String? = null) {
        backend.call("handoffAction", buildJsonObject {
            put("token", token); put("action", action)
            if (amountMinor != null) put("amountMinor", amountMinor)
            if (reason != null) put("reason", reason)
        })
    }

    suspend fun submitHandoffWork(token: String, links: List<SubmissionLink>, note: String) {
        backend.call("submitHandoffWork", buildJsonObject {
            put("token", token); put("note", note)
            put("links", JsonArray(links.map { buildJsonObject { put("label", it.label); put("url", it.url) } }))
        })
    }

    fun observeHandoffSubmissions(token: String): Flow<List<Submission>> =
        db.observeQuery(QuerySpec("handoffs/$token/submissions")).map { d -> d.map { it.data.decodeAs<Submission>().copy(id = it.id) }.sortedByDescending { it.submittedAt } }

    // ── Payments (F7) — server-created orders, server-verified results ─────
    suspend fun createPaymentOrder(projectId: String, milestoneId: String): JsonObject =
        backend.call("createPaymentOrder", buildJsonObject { put("projectId", projectId); put("milestoneId", milestoneId) }) as JsonObject

    suspend fun verifyPayment(projectId: String, milestoneId: String, orderId: String, paymentId: String, signature: String): JsonObject =
        backend.call("verifyPayment", buildJsonObject {
            put("projectId", projectId); put("milestoneId", milestoneId); put("orderId", orderId); put("paymentId", paymentId); put("signature", signature)
        }) as JsonObject

    suspend fun refreshPaymentStatus(projectId: String, milestoneId: String): JsonObject =
        backend.call("refreshPaymentStatus", buildJsonObject { put("projectId", projectId); put("milestoneId", milestoneId) }) as JsonObject

    // ── Audit ───────────────────────────────────────────────────────────────
    fun observeEvents(pid: String): Flow<List<AuditEvent>> =
        db.observeQuery(QuerySpec("projects/$pid/events", orderBy = "createdAt", descending = true, limit = 30)).map { d -> d.map { it.data.decodeAs<AuditEvent>().copy(id = it.id) } }

    private fun audit(pid: String, user: AuthUser, type: String, entityId: String, detail: String): WriteOp {
        val id = db.newId()
        return WriteOp.Set("projects/$pid/events/$id", AuditEvent(id, user.uid, user.displayName, type, entityId, detail, platform.now()).toJsonObject())
    }

    companion object {
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
    }
}

// ── Money ────────────────────────────────────────────────────────────────────

/** 700000 paise → "7,000" with Indian digit grouping (e.g. 1,50,000). */
fun formatInr(paise: Long): String {
    val rupees = paise / 100
    val rem = (paise % 100).toInt()
    val s = rupees.toString()
    val grouped = if (s.length <= 3) s else {
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$rest,$last3"
    }
    return if (rem == 0) grouped else grouped + "." + rem.toString().padStart(2, '0')
}

fun rupees(paise: Long): String = "₹" + formatInr(paise)

/** "7,000" / "7000" / "₹ 7,000.50" → paise, or null when invalid. */
fun parseRupeesToPaise(input: String): Long? {
    val cleaned = input.replace("₹", "").replace(",", "").trim()
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split('.')
    if (parts.size > 2 || parts.any { p -> p.any { !it.isDigit() } }) return null
    val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val frac = parts.getOrNull(1)?.let { if (it.length > 2) return null else it.padEnd(2, '0').toLong() } ?: 0
    return whole * 100 + frac
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
