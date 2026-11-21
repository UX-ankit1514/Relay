package app.relay.demo

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ready-made demo story. Three projects, one per perspective, each paused at the moment
 * worth presenting — and every next step still works:
 *
 *  1. Client      · ClearInvoice landing page — Rohan submitted Mobile design; Priya reviews, approves, pays.
 *  2. Freelancer  · Bloom Café website — Priya invited Rohan; he accepts the terms and delivers.
 *  3. Replacement · FitTrack onboarding — Rohan dropped out; Maya opens the handoff, accepts, delivers, gets paid.
 *
 * IDs are fixed, so a copied demo link opens the same page in any browser.
 */
object DemoIds {
    const val CLEARINVOICE = "demo-clearinvoice"
    const val BLOOM = "demo-bloom-cafe"
    const val FITTRACK = "demo-fittrack"
    const val BLOOM_INVITE = "demo-invite-bloom-cafe"
    const val CLEARINVOICE_INVITE = "demo-invite-clearinvoice"
    const val FITTRACK_INVITE = "demo-invite-fittrack"
    const val FITTRACK_HANDOFF = "demo-handoff-fittrack"
    const val CI_MOBILE_SUB = "demo-ci-sub-mobile-v1"
}

object DemoSeed {
    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

    private val priya = Member("demo-priya", "Priya", "priya@example.com", Roles.OWNER, "active")
    private val rohan = Member("demo-rohan", "Rohan", "rohan@example.com", Roles.ORIGINAL, "active")

    fun build(now: Long): Map<String, JsonObject> {
        val docs = linkedMapOf<String, JsonObject>()
        fun put(path: String, o: JsonObject) { docs[path] = o }
        var eventSeq = 0
        fun event(pid: String, at: Long, actor: Member?, type: String, entity: String, detail: String) {
            val id = "demo-ev-${++eventSeq}"
            put("projects/$pid/events/$id", AuditEvent(id, actor?.uid ?: "relay-server", actor?.name ?: "Relay server", type, entity, detail, at).toJsonObject())
        }
        fun msg(pid: String, id: String, who: Member, at: Long, body: String) =
            put("projects/$pid/messages/$id", Message(id, who.uid, who.name, who.role, body, at, id).toJsonObject())

        // People
        listOf(priya to "hiring", rohan to "freelancing", Member("demo-maya", "Maya", "maya@example.com") to "freelancing").forEach { (m, intent) ->
            put("users/${m.uid}", UserProfile(m.uid, m.name, m.email, null, intent, introSeen = true, createdAt = now - 30 * DAY).toJsonObject())
        }

        // ── 1 · ClearInvoice landing page (client reviews & pays) ───────────────
        run {
            val pid = DemoIds.CLEARINVOICE
            val start = now - 14 * DAY
            val desktop = Milestone("demo-ci-desktop", 1, "Desktop design", "1440px desktop landing page in Figma", 700000, revisionLimit = 2, revisionsUsed = 1,
                status = MilestoneStatus.APPROVED, assigneeId = rohan.uid, assigneeName = rohan.name, assigneeRole = Roles.ORIGINAL,
                paymentStatus = PaymentState.RECORDED_EXTERNALLY, paidExternallyMinor = 700000,
                approvedSubmissionId = "demo-ci-sub-desktop-v2", approvedLabel = "Desktop design v2", approvedAt = now - 6 * DAY)
            val mobile = Milestone("demo-ci-mobile", 2, "Mobile design", "360px and 390px mobile layouts in Figma", 600000, revisionLimit = 2,
                status = MilestoneStatus.SUBMITTED, assigneeId = rohan.uid, assigneeName = rohan.name, assigneeRole = Roles.ORIGINAL)
            val dev = Milestone("demo-ci-dev", 3, "Developer handoff", "Specs, assets and redlines for the developer", 200000, revisionLimit = 1,
                status = MilestoneStatus.NOT_STARTED, assigneeId = rohan.uid, assigneeName = rohan.name, assigneeRole = Roles.ORIGINAL)
            val ms = listOf(desktop, mobile, dev)
            put("projects/$pid", Project(
                id = pid, name = "ClearInvoice landing page",
                goal = "A one-page site that explains ClearInvoice to small businesses in one screen and gets them to book a demo. Audience: shop owners and accountants who send 20–200 invoices a month.",
                ownerId = priya.uid, ownerName = priya.name, status = ProjectStatus.ACTIVE,
                memberIds = listOf(priya.uid, rohan.uid),
                members = mapOf(priya.uid to priya.copy(since = start), rohan.uid to rohan.copy(since = start + DAY)),
                agreementVersion = 1, agreementStatus = "accepted", inviteToken = DemoIds.CLEARINVOICE_INVITE, inviteEmail = rohan.email,
                createdAt = start, updatedAt = now - 1 * HOUR,
            ).toJsonObject())
            ms.forEach { put("projects/$pid/milestones/${it.id}", it.toJsonObject()) }
            val terms = ms.map { MilestoneTerms(it.id, it.title, it.deliverable, it.amountMinor, it.revisionLimit) }
            put("projects/$pid/agreements/v1", AgreementVersion("v1", 1, "accepted", terms, 1500000, priya.uid, priya.name, start + 2 * HOUR, rohan.uid, rohan.name, start + DAY).toJsonObject())
            put("invites/${DemoIds.CLEARINVOICE_INVITE}", Invite(DemoIds.CLEARINVOICE_INVITE, pid, "ClearInvoice landing page", "", priya.uid, priya.name, rohan.email, Roles.ORIGINAL, terms, 1500000, 1, "accepted", rohan.uid, start + 2 * HOUR, start + 9 * DAY).toJsonObject())

            msg(pid, "demo-ci-m1", priya, now - 12 * DAY, "Hi Rohan! The page should explain ClearInvoice in one screen and push people to book a demo.")
            msg(pid, "demo-ci-m2", rohan, now - 12 * DAY + 2 * HOUR, "Got it. I’ll start with the desktop layout. Do you have brand colours, or should I propose some?")
            msg(pid, "demo-ci-m3", priya, now - 12 * DAY + 3 * HOUR, "Use our brand guide — navy and white. And keep the Book a demo button near the top, visible without scrolling.")
            msg(pid, "demo-ci-m4", rohan, now - 9 * DAY, "Desktop v1 is up for review. Split hero, product screenshot on the right.")
            msg(pid, "demo-ci-m5", priya, now - 8 * DAY, "Looks great. One change: replace the stock photo with a real invoice screenshot.")
            msg(pid, "demo-ci-m6", rohan, now - 7 * DAY, "Done in v2 — real screenshot, and I tightened the pricing section.")
            msg(pid, "demo-ci-m7", priya, now - 6 * DAY, "Approved! I’ve paid the ₹7,000 for desktop by bank transfer.")
            msg(pid, "demo-ci-m8", rohan, now - 3 * DAY, "Starting mobile. Should the pricing table stack as cards on 360px?")
            msg(pid, "demo-ci-m9", priya, now - 3 * DAY + HOUR, "Yes, stack them as cards. And let’s drop the testimonials carousel on mobile.")
            msg(pid, "demo-ci-m10", rohan, now - 2 * DAY, "I’d keep one testimonial on mobile — it really helps trust for a payments product.")
            msg(pid, "demo-ci-m11", rohan, now - 1 * DAY, "Mobile v1 is submitted: 360px and 390px frames are in Figma. Let me know what you think!")

            fun dec(id: String, title: String, summary: String, src: List<String>, quote: String, author: Member, at: Long, status: String, conflict: Boolean = false, options: List<String> = emptyList(), origin: String = "ai") =
                put("projects/$pid/decisions/$id", Decision(id, "decision", title, summary, src, quote, author.name, at, status, origin, conflict, options,
                    confirmedBy = if (status == DecisionStatus.CONFIRMED) priya.uid else null, confirmedByName = if (status == DecisionStatus.CONFIRMED) priya.name else null,
                    confirmedAt = if (status == DecisionStatus.CONFIRMED) at + 4 * HOUR else null, createdAt = at + HOUR).toJsonObject())
            dec("demo-ci-d1", "Keep “Book a demo” near the top", "The demo button must be visible without scrolling on every screen size.", listOf("demo-ci-m3"), "keep the Book a demo button near the top, visible without scrolling", priya, now - 12 * DAY + 3 * HOUR, DecisionStatus.CONFIRMED)
            dec("demo-ci-d2", "Use the navy and white brand palette", "Colours come from the ClearInvoice brand guide.", listOf("demo-ci-m3"), "Use our brand guide — navy and white", priya, now - 12 * DAY + 3 * HOUR, DecisionStatus.CONFIRMED)
            dec("demo-ci-d3", "Use a real invoice screenshot, not stock photos", "Product imagery shows the real ClearInvoice app.", listOf("demo-ci-m5"), "replace the stock photo with a real invoice screenshot", priya, now - 8 * DAY, DecisionStatus.CONFIRMED)
            dec("demo-ci-d4", "Stack pricing as cards on mobile", "On 360px the pricing table becomes a vertical list of cards.", listOf("demo-ci-m8", "demo-ci-m9"), "Yes, stack them as cards.", priya, now - 3 * DAY + HOUR, DecisionStatus.SUGGESTED)
            dec("demo-ci-d5", "Testimonials on mobile", "Priya and Rohan disagree about testimonials on mobile. Pick one.", listOf("demo-ci-m9", "demo-ci-m10"), "let’s drop the testimonials carousel on mobile", priya, now - 2 * DAY, DecisionStatus.SUGGESTED,
                conflict = true, options = listOf("Drop the testimonials carousel", "Keep one testimonial"))
            put("projects/$pid/private/extraction", buildJsonObject { put("lastRunAt", now - 2 * DAY + HOUR) })

            put("projects/$pid/assets/demo-ci-a1", Asset("demo-ci-a1", "Desktop design v2", "https://www.figma.com/design/demo-clearinvoice/Desktop-v2", "approved", desktop.id, priya.uid, priya.name, "Approved by Priya", now - 6 * DAY).toJsonObject())
            put("projects/$pid/assets/demo-ci-a2", Asset("demo-ci-a2", "ClearInvoice brand guide", "https://example.com/clearinvoice/brand-guide.pdf", "reference", null, priya.uid, priya.name, "Colours, logo and type", now - 12 * DAY).toJsonObject())
            put("projects/$pid/assets/demo-ci-a3", Asset("demo-ci-a3", "Invoice screenshots", "https://example.com/clearinvoice/screenshots", "reference", null, priya.uid, priya.name, "Real product screens for the hero", now - 8 * DAY).toJsonObject())

            put("projects/$pid/submissions/demo-ci-sub-desktop-v1", Submission("demo-ci-sub-desktop-v1", desktop.id, "Desktop design v1", rohan.uid, rohan.name, Roles.ORIGINAL,
                listOf(SubmissionLink("1440px desktop", "https://www.figma.com/design/demo-clearinvoice/Desktop-v1")), "Split hero with the product on the right.",
                SubmissionStatus.CHANGES, now - 9 * DAY, now - 8 * DAY, "Replace the stock photo with a real invoice screenshot.").toJsonObject())
            put("projects/$pid/submissions/demo-ci-sub-desktop-v2", Submission("demo-ci-sub-desktop-v2", desktop.id, "Desktop design v2", rohan.uid, rohan.name, Roles.ORIGINAL,
                listOf(SubmissionLink("1440px desktop", "https://www.figma.com/design/demo-clearinvoice/Desktop-v2")), "Real screenshot in the hero; tighter pricing section.",
                SubmissionStatus.APPROVED, now - 7 * DAY, now - 6 * DAY).toJsonObject())
            put("projects/$pid/submissions/${DemoIds.CI_MOBILE_SUB}", Submission(DemoIds.CI_MOBILE_SUB, mobile.id, "Mobile design v1", rohan.uid, rohan.name, Roles.ORIGINAL,
                listOf(SubmissionLink("360px and 390px mobile layouts in Figma", "https://www.figma.com/design/demo-clearinvoice/Mobile-v1")),
                "Both frames are in one Figma page. Pricing stacks as cards; I kept one testimonial — happy to remove it.",
                SubmissionStatus.SUBMITTED, now - 1 * DAY).toJsonObject())

            put("projects/$pid/payments/demo-ci-pay1", PaymentRecord("demo-ci-pay1", desktop.id, 700000, "INR", "external_record", PaymentState.RECORDED_EXTERNALLY,
                payee = rohan.name, note = "Bank transfer", recordedBy = priya.uid, createdAt = now - 6 * DAY + HOUR).toJsonObject())

            event(pid, start, priya, "project_created", pid, "ClearInvoice landing page")
            event(pid, start + 2 * HOUR, priya, "agreement_proposed", "v1", "₹15,000")
            event(pid, start + DAY, rohan, "agreement_accepted", "v1", "Version 1 accepted")
            event(pid, now - 9 * DAY, rohan, "submission_created", "demo-ci-sub-desktop-v1", "Desktop design v1")
            event(pid, now - 8 * DAY, priya, "changes_requested", desktop.id, "Replace the stock photo with a real invoice screenshot.")
            event(pid, now - 7 * DAY, rohan, "submission_created", "demo-ci-sub-desktop-v2", "Desktop design v2")
            event(pid, now - 6 * DAY, priya, "milestone_approved", desktop.id, "Desktop design v2 approved.")
            event(pid, now - 6 * DAY + HOUR, priya, "payment_recorded_externally", desktop.id, "7,000")
            event(pid, now - 1 * DAY, rohan, "submission_created", DemoIds.CI_MOBILE_SUB, "Mobile design v1")
        }

        // ── 2 · Bloom Café website (freelancer accepts & delivers) ──────────────
        run {
            val pid = DemoIds.BLOOM
            val start = now - 2 * DAY
            val ms = listOf(
                Milestone("demo-bc-home", 1, "Home & menu pages", "Desktop and mobile designs for the home and menu pages", 800000, revisionLimit = 2),
                Milestone("demo-bc-order", 2, "Order online flow", "Cart, checkout and order confirmation screens", 500000, revisionLimit = 2),
            )
            put("projects/$pid", Project(
                id = pid, name = "Bloom Café website",
                goal = "A warm, fast website for Bloom Café: today’s menu, opening hours, directions, and online orders for pickup.",
                ownerId = priya.uid, ownerName = priya.name, status = ProjectStatus.WAITING,
                memberIds = listOf(priya.uid), members = mapOf(priya.uid to priya.copy(since = start)),
                agreementVersion = 1, agreementStatus = "proposed", inviteToken = DemoIds.BLOOM_INVITE, inviteEmail = rohan.email,
                createdAt = start, updatedAt = now - 20 * HOUR,
            ).toJsonObject())
            ms.forEach { put("projects/$pid/milestones/${it.id}", it.toJsonObject()) }
            val terms = ms.map { MilestoneTerms(it.id, it.title, it.deliverable, it.amountMinor, it.revisionLimit) }
            put("projects/$pid/agreements/v1", AgreementVersion("v1", 1, "proposed", terms, 1300000, priya.uid, priya.name, now - 20 * HOUR).toJsonObject())
            put("invites/${DemoIds.BLOOM_INVITE}", Invite(DemoIds.BLOOM_INVITE, pid, "Bloom Café website",
                "A warm, fast website for Bloom Café: today’s menu, opening hours, directions, and online orders for pickup.",
                priya.uid, priya.name, rohan.email, Roles.ORIGINAL, terms, 1300000, 1, "pending", null, now - 20 * HOUR, now + 6 * DAY).toJsonObject())
            event(pid, start, priya, "project_created", pid, "Bloom Café website")
            event(pid, now - 20 * HOUR, priya, "agreement_proposed", "v1", "₹13,000")
        }

        // ── 3 · FitTrack onboarding (replacement takes over) ────────────────────
        run {
            val pid = DemoIds.FITTRACK
            val start = now - 21 * DAY
            val hid = DemoIds.FITTRACK_HANDOFF
            val flow = Milestone("demo-ft-flow", 1, "Onboarding flow", "User flow and wireframes for sign-up and first goal", 700000, revisionLimit = 2,
                status = MilestoneStatus.APPROVED, assigneeId = rohan.uid, assigneeName = rohan.name, assigneeRole = Roles.ORIGINAL,
                paymentStatus = PaymentState.VERIFIED_TEST, paidVerifiedMinor = 700000,
                approvedSubmissionId = "demo-ft-sub-flow-v1", approvedLabel = "Onboarding flow v1", approvedAt = now - 10 * DAY)
            val screens = Milestone("demo-ft-screens", 2, "Mobile screens", "360px and 390px screens for sign-up and goal setup", 600000, revisionLimit = 2,
                status = MilestoneStatus.REASSIGNED, handoffId = hid)
            val dev = Milestone("demo-ft-dev", 3, "Developer handoff", "Specs and exported assets for the app team", 200000, revisionLimit = 1,
                status = MilestoneStatus.NOT_STARTED, assigneeId = rohan.uid, assigneeName = rohan.name, assigneeRole = Roles.ORIGINAL)
            val ms = listOf(flow, screens, dev)
            val goal = "Design a friendly onboarding for the FitTrack habit app, so a new user sets their first goal in under a minute."
            put("projects/$pid", Project(
                id = pid, name = "FitTrack onboarding", goal = goal,
                ownerId = priya.uid, ownerName = priya.name, status = ProjectStatus.HANDOFF,
                memberIds = listOf(priya.uid, rohan.uid),
                members = mapOf(priya.uid to priya.copy(since = start), rohan.uid to rohan.copy(status = "interrupted", since = start + DAY)),
                agreementVersion = 1, agreementStatus = "accepted", inviteToken = DemoIds.FITTRACK_INVITE, inviteEmail = rohan.email,
                activeHandoffId = hid, createdAt = start, updatedAt = now - 3 * HOUR,
            ).toJsonObject())
            ms.forEach { put("projects/$pid/milestones/${it.id}", it.toJsonObject()) }
            val terms = ms.map { MilestoneTerms(it.id, it.title, it.deliverable, it.amountMinor, it.revisionLimit) }
            put("projects/$pid/agreements/v1", AgreementVersion("v1", 1, "accepted", terms, 1500000, priya.uid, priya.name, start + 2 * HOUR, rohan.uid, rohan.name, start + DAY).toJsonObject())
            put("invites/${DemoIds.FITTRACK_INVITE}", Invite(DemoIds.FITTRACK_INVITE, pid, "FitTrack onboarding", "", priya.uid, priya.name, rohan.email, Roles.ORIGINAL, terms, 1500000, 1, "accepted", rohan.uid, start + 2 * HOUR, start + 9 * DAY).toJsonObject())

            msg(pid, "demo-ft-m1", priya, start + 2 * DAY, "Let’s ask for the user’s goal before we ask them to create an account.")
            msg(pid, "demo-ft-m2", rohan, start + 2 * DAY + HOUR, "Agreed. I’ll use the coral accent only for primary buttons so the goal picker stays calm.")
            msg(pid, "demo-ft-m3", priya, start + 3 * DAY, "Skip social sign-in for v1 — email only.")
            msg(pid, "demo-ft-m4", rohan, now - 11 * DAY, "Onboarding flow v1 is ready: 6 steps, goal first, account last.")
            msg(pid, "demo-ft-m5", priya, now - 10 * DAY, "Approved and paid through Relay. Mobile screens next!")
            msg(pid, "demo-ft-m6", rohan, now - 4 * DAY, "Priya, I’m so sorry — a family emergency means I can’t continue this project.")

            fun conf(id: String, title: String, summary: String, src: String, quote: String, author: Member, at: Long) =
                put("projects/$pid/decisions/$id", Decision(id, "decision", title, summary, listOf(src), quote, author.name, at, DecisionStatus.CONFIRMED, "ai",
                    confirmedBy = priya.uid, confirmedByName = priya.name, confirmedAt = at + 3 * HOUR, createdAt = at + HOUR).toJsonObject())
            conf("demo-ft-d1", "Ask for the goal before account creation", "The goal picker is step 1; sign-up comes last.", "demo-ft-m1", "ask for the user’s goal before we ask them to create an account", priya, start + 2 * DAY)
            conf("demo-ft-d2", "Coral accent only on primary buttons", "Everything else stays neutral so the goal picker feels calm.", "demo-ft-m2", "use the coral accent only for primary buttons", rohan, start + 2 * DAY + HOUR)
            conf("demo-ft-d3", "No social sign-in in v1", "Email sign-up only for the first release.", "demo-ft-m3", "Skip social sign-in for v1 — email only.", priya, start + 3 * DAY)
            put("projects/$pid/private/extraction", buildJsonObject { put("lastRunAt", now - 4 * DAY + HOUR) })

            put("projects/$pid/assets/demo-ft-a1", Asset("demo-ft-a1", "Onboarding flow v1", "https://www.figma.com/design/demo-fittrack/Onboarding-flow-v1", "approved", flow.id, priya.uid, priya.name, "Approved by Priya", now - 10 * DAY).toJsonObject())
            put("projects/$pid/assets/demo-ft-a2", Asset("demo-ft-a2", "FitTrack style guide", "https://example.com/fittrack/style-guide.pdf", "reference", null, priya.uid, priya.name, "Colours, type and icon set", start + DAY).toJsonObject())
            put("projects/$pid/assets/demo-ft-a3", Asset("demo-ft-a3", "Rohan’s rate card", "https://example.com/fittrack/rate-card.pdf", "reference", null, rohan.uid, rohan.name, "Private — not for the replacement", start + DAY).toJsonObject())

            put("projects/$pid/submissions/demo-ft-sub-flow-v1", Submission("demo-ft-sub-flow-v1", flow.id, "Onboarding flow v1", rohan.uid, rohan.name, Roles.ORIGINAL,
                listOf(SubmissionLink("User flow and wireframes", "https://www.figma.com/design/demo-fittrack/Onboarding-flow-v1")), "6 steps: goal first, account last.",
                SubmissionStatus.APPROVED, now - 11 * DAY, now - 10 * DAY).toJsonObject())
            put("projects/$pid/payments/order_DemoFitTrack01", PaymentRecord("order_DemoFitTrack01", flow.id, 700000, "INR", "test", PaymentState.VERIFIED_TEST,
                orderId = "order_DemoFitTrack01", paymentId = "pay_DemoFitTrack01", payee = rohan.name, recordedBy = priya.uid,
                createdAt = now - 10 * DAY, verifiedAt = now - 10 * DAY + 60_000, updatedAt = now - 10 * DAY + 60_000).toJsonObject())
            put("projects/$pid/private/interruption", buildJsonObject { put("freelancerUid", rohan.uid); put("reason", "Family emergency"); put("at", now - 4 * DAY + 2 * HOUR); put("by", priya.uid) })

            put("handoffs/$hid", Handoff(
                id = hid, projectId = pid, ownerId = priya.uid, ownerName = priya.name, status = HandoffStatus.PUBLISHED,
                milestoneIds = listOf(screens.id),
                brief = Brief(
                    projectName = "FitTrack onboarding", goal = goal, clientName = priya.name,
                    approvedWork = listOf(BriefFile("Onboarding flow v1", "https://www.figma.com/design/demo-fittrack/Onboarding-flow-v1", "approved", "Onboarding flow · approved by Priya")),
                    work = listOf(BriefMilestone(screens.id, screens.title, screens.deliverable, screens.amountMinor, screens.revisionLimit)),
                    requiredDeliverables = listOf("360px frame", "390px frame", "Completion note"),
                    decisions = listOf(
                        BriefDecision("Ask for the goal before account creation", "The goal picker is step 1; sign-up comes last.", "ask for the user’s goal before we ask them to create an account", priya.name, start + 2 * DAY),
                        BriefDecision("Coral accent only on primary buttons", "Everything else stays neutral so the goal picker feels calm.", "use the coral accent only for primary buttons", rohan.name, start + 2 * DAY + HOUR),
                        BriefDecision("No social sign-in in v1", "Email sign-up only for the first release.", "Skip social sign-in for v1 — email only.", priya.name, start + 3 * DAY),
                    ),
                    notInScope = listOf(BriefMilestone(dev.id, dev.title, dev.deliverable, dev.amountMinor, dev.revisionLimit)),
                    files = listOf(BriefFile("FitTrack style guide", "https://example.com/fittrack/style-guide.pdf", "reference", "Colours, type and icon set")),
                    earlierPayments = listOf("Onboarding flow · Paid (verified) · ₹7,000"),
                ),
                excluded = listOf("File: Rohan’s rate card", "Full conversation history"),
                feeMinor = 600000,
                proposals = listOf(FeeProposal(1, 600000, priya.uid, priya.name, Roles.OWNER, "", now - 3 * HOUR, "offered")),
                sentTo = "maya@example.com", createdAt = now - 3 * HOUR, publishedAt = now - 3 * HOUR, expiresAt = now + 7 * DAY - 3 * HOUR,
            ).toJsonObject())

            event(pid, start, priya, "project_created", pid, "FitTrack onboarding")
            event(pid, start + DAY, rohan, "agreement_accepted", "v1", "Version 1 accepted")
            event(pid, now - 10 * DAY, priya, "milestone_approved", flow.id, "Onboarding flow v1 approved.")
            event(pid, now - 10 * DAY + 60_000, null, "payment_verified", flow.id, "₹7,000 · pay_DemoFitTrack01 · test mode")
            event(pid, now - 4 * DAY + 2 * HOUR, priya, "assignment_interrupted", rohan.uid, "History and payments kept")
            event(pid, now - 3 * HOUR, priya, "handoff_published", hid, "Mobile screens · ₹6,000 · 2 items excluded")
        }
        return docs
    }
}
