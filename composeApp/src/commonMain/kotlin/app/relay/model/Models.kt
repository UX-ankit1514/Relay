package app.relay.model

import kotlinx.serialization.Serializable

/*
 * Domain model — PRD §22. Stored in Cloud Firestore.
 * Money is always integer minor units (paise) + currency INR.
 * Times are epoch milliseconds.
 */

object Roles {
    const val OWNER = "owner"
    const val ORIGINAL = "original_freelancer"
    const val REPLACEMENT = "replacement_freelancer"
}

@Serializable
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    /** "hiring" | "freelancing" — answered once during onboarding. */
    val intent: String? = null,
    val introSeen: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
data class Member(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    /** invited | active | interrupted | revoked */
    val status: String = "active",
    val since: Long = 0,
)

object ProjectStatus {
    const val DRAFT = "draft"
    const val WAITING = "waiting_agreement"
    const val ACTIVE = "active"
    const val INTERRUPTED = "interrupted"
    const val HANDOFF = "handoff"
    const val COMPLETED = "completed"
}

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val goal: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val status: String = ProjectStatus.DRAFT,
    /** Owner + original freelancer. Replacements are NOT members — they only see their handoff. */
    val memberIds: List<String> = emptyList(),
    val members: Map<String, Member> = emptyMap(),
    val agreementVersion: Int = 0,
    /** draft | proposed | accepted */
    val agreementStatus: String = "draft",
    val inviteToken: String? = null,
    val inviteEmail: String? = null,
    val activeHandoffId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

object MilestoneStatus {
    const val DRAFT = "draft"
    const val NOT_STARTED = "not_started"
    const val IN_PROGRESS = "in_progress"
    const val SUBMITTED = "submitted"
    const val CHANGES = "revision_requested"
    const val APPROVED = "approved"
    const val INTERRUPTED = "interrupted"
    const val REASSIGNED = "reassigned"
}

object PaymentState {
    const val UNPAID = "unpaid"
    const val RECORDED_EXTERNALLY = "recorded_externally"
    const val CREATED = "created"
    const val PENDING = "pending"
    const val VERIFIED_TEST = "verified_test"
    const val FAILED = "failed"
    const val NOT_VERIFIED = "not_verified"
}

@Serializable
data class Milestone(
    val id: String = "",
    val index: Int = 0,
    val title: String = "",
    val deliverable: String = "",
    val amountMinor: Long = 0,
    val currency: String = "INR",
    val revisionLimit: Int = 2,
    val revisionsUsed: Int = 0,
    val status: String = MilestoneStatus.DRAFT,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val assigneeRole: String? = null,
    val paymentStatus: String = PaymentState.UNPAID,
    val paidExternallyMinor: Long = 0,
    val paidVerifiedMinor: Long = 0,
    val approvedSubmissionId: String? = null,
    val approvedLabel: String? = null,
    val approvedAt: Long? = null,
    /** Set when the milestone was handed to a replacement. */
    val handoffId: String? = null,
    /** Fee agreed with the replacement. Shown as a separate adjustment; the original agreement is never rewritten. */
    val replacementFeeMinor: Long? = null,
)

@Serializable
data class MilestoneTerms(
    val milestoneId: String = "",
    val title: String = "",
    val deliverable: String = "",
    val amountMinor: Long = 0,
    val revisionLimit: Int = 2,
)

@Serializable
data class AgreementVersion(
    val id: String = "",
    val version: Int = 0,
    /** proposed | accepted | rejected | superseded */
    val status: String = "proposed",
    val terms: List<MilestoneTerms> = emptyList(),
    val totalMinor: Long = 0,
    val proposedBy: String = "",
    val proposedByName: String = "",
    val proposedAt: Long = 0,
    val acceptedBy: String? = null,
    val acceptedByName: String? = null,
    val acceptedAt: Long? = null,
    val changeNote: String? = null,
)

@Serializable
data class Invite(
    val token: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val projectGoal: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val email: String = "",
    val role: String = Roles.ORIGINAL,
    /** Snapshot of the proposed terms so the invitee can review them before joining. */
    val terms: List<MilestoneTerms> = emptyList(),
    val totalMinor: Long = 0,
    val agreementVersion: Int = 1,
    /** pending | accepted | revoked */
    val status: String = "pending",
    val acceptedBy: String? = null,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
)

@Serializable
data class Message(
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val body: String = "",
    val createdAt: Long = 0,
    val clientRequestId: String = "",
)

object DecisionStatus {
    const val SUGGESTED = "suggested"
    const val CONFIRMED = "confirmed"
    const val REJECTED = "rejected"
    const val SUPERSEDED = "superseded"
}

@Serializable
data class Decision(
    val id: String = "",
    /** decision | requirement | revision | approval_candidate | open_question */
    val type: String = "decision",
    val title: String = "",
    val summary: String = "",
    val sourceMessageIds: List<String> = emptyList(),
    val sourceQuote: String = "",
    val sourceAuthor: String = "",
    val sourceAt: Long = 0,
    val status: String = DecisionStatus.SUGGESTED,
    /** ai | manual */
    val origin: String = "ai",
    val conflict: Boolean = false,
    val options: List<String> = emptyList(),
    val confirmedBy: String? = null,
    val confirmedByName: String? = null,
    val confirmedAt: Long? = null,
    val reviewNote: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class Asset(
    val id: String = "",
    val label: String = "",
    val url: String = "",
    /** approved | reference | no_access */
    val status: String = "reference",
    val milestoneId: String? = null,
    val addedBy: String = "",
    val addedByName: String = "",
    val note: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class SubmissionLink(val label: String = "", val url: String = "")

object SubmissionStatus {
    const val SUBMITTED = "submitted"
    const val CHANGES = "revision_requested"
    const val APPROVED = "approved"
}

@Serializable
data class Submission(
    val id: String = "",
    val milestoneId: String = "",
    val label: String = "",
    val submittedBy: String = "",
    val submittedByName: String = "",
    val submittedByRole: String = "",
    val links: List<SubmissionLink> = emptyList(),
    val note: String = "",
    val status: String = SubmissionStatus.SUBMITTED,
    val submittedAt: Long = 0,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null,
    /** Set when the submission came from a replacement via a handoff. */
    val handoffId: String? = null,
)

@Serializable
data class PaymentRecord(
    val id: String = "",
    val milestoneId: String = "",
    val amountMinor: Long = 0,
    val currency: String = "INR",
    /** external_record | test */
    val mode: String = "external_record",
    val status: String = PaymentState.RECORDED_EXTERNALLY,
    val orderId: String? = null,
    val paymentId: String? = null,
    val payee: String = "",
    val note: String = "",
    val recordedBy: String = "",
    val createdAt: Long = 0,
    val verifiedAt: Long? = null,
    val failureReason: String? = null,
    val updatedAt: Long = 0,
)

// ─── Handoff (top-level collection; doc id = unguessable share token) ────────

@Serializable
data class BriefDecision(val title: String = "", val summary: String = "", val sourceQuote: String = "", val sourceAuthor: String = "", val sourceAt: Long = 0)

@Serializable
data class BriefFile(val label: String = "", val url: String = "", val status: String = "reference", val note: String = "")

@Serializable
data class BriefMilestone(val milestoneId: String = "", val title: String = "", val deliverable: String = "", val amountMinor: Long = 0, val revisionLimit: Int = 2)

/** The client-reviewed subset a replacement can see. Excluded content is never copied here. */
@Serializable
data class Brief(
    val projectName: String = "",
    val goal: String = "",
    val clientName: String = "",
    val approvedWork: List<BriefFile> = emptyList(),
    val work: List<BriefMilestone> = emptyList(),
    val requiredDeliverables: List<String> = emptyList(),
    val decisions: List<BriefDecision> = emptyList(),
    val notInScope: List<BriefMilestone> = emptyList(),
    val files: List<BriefFile> = emptyList(),
    val earlierPayments: List<String> = emptyList(),
)

@Serializable
data class FeeProposal(
    val version: Int = 1,
    val amountMinor: Long = 0,
    val by: String = "",
    val byName: String = "",
    val byRole: String = "",
    val reason: String = "",
    val at: Long = 0,
    /** offered | proposed | accepted | rejected | superseded | withdrawn */
    val status: String = "offered",
)

object HandoffStatus {
    const val DRAFT = "draft"
    const val PUBLISHED = "published"
    const val COUNTEROFFER = "counteroffer"
    const val ACCEPTED = "accepted"
    const val SUBMITTED = "submitted"
    const val CHANGES = "changes_requested"
    const val APPROVED = "approved"
    const val DECLINED = "declined"
    const val REVOKED = "revoked"
    const val COMPLETED = "completed"
}

@Serializable
data class Handoff(
    val id: String = "",
    val projectId: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val status: String = HandoffStatus.DRAFT,
    val milestoneIds: List<String> = emptyList(),
    val brief: Brief = Brief(),
    val excluded: List<String> = emptyList(),
    val feeMinor: Long = 0,
    val proposals: List<FeeProposal> = emptyList(),
    val agreedFeeMinor: Long? = null,
    val replacementUid: String? = null,
    val replacementName: String? = null,
    val replacementEmail: String? = null,
    val sentTo: String? = null,
    val openedAt: Long? = null,
    val acceptedAt: Long? = null,
    val createdAt: Long = 0,
    val publishedAt: Long? = null,
    val expiresAt: Long = 0,
)

@Serializable
data class AuditEvent(
    val id: String = "",
    val actorId: String = "",
    val actorName: String = "",
    val type: String = "",
    val entityId: String = "",
    val detail: String = "",
    val createdAt: Long = 0,
)
