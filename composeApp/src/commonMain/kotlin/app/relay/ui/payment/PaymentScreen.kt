package app.relay.ui.payment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.relay.data.CheckoutRequest
import app.relay.data.CheckoutResult
import app.relay.data.RelayError
import app.relay.data.formatInr
import app.relay.data.rupees
import app.relay.data.str
import app.relay.design.ActionSpec
import app.relay.design.Amount
import app.relay.design.ContextPanel
import app.relay.design.ListCard
import app.relay.design.ListRowSpec
import app.relay.design.NextStepPanel
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.Section
import app.relay.design.StatusKind
import app.relay.design.StatusLine
import app.relay.design.StatusPill
import app.relay.design.TitleBlock
import app.relay.design.WorkspaceLayout
import app.relay.model.Milestone
import app.relay.model.MilestoneStatus
import app.relay.model.PaymentRecord
import app.relay.model.PaymentState
import app.relay.model.Project
import app.relay.model.Roles
import app.relay.nav.Route
import app.relay.ui.ErrorText
import app.relay.ui.LocalServices
import app.relay.ui.LocalUser
import app.relay.ui.rememberAction
import app.relay.ui.rememberLive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * F7 · Approve & pay — Figma "Hi-Fi Design" › F7-03 … F7-08 (IA §F7, S-12 → S-13).
 *
 * Pay → server creates the Razorpay order → Razorpay Standard Checkout (test mode) →
 * server verifies signature + amount → milestone shows "Paid (verified)".
 * The browser callback is never trusted: "paid" is only ever read from what the server wrote.
 */

/** What this person is doing right now. A server-verified payment always overrides it. */
private sealed interface PayStep {
    data object Idle : PayStep
    data object Creating : PayStep
    data class InCheckout(val orderId: String) : PayStep
    data class Verifying(val orderId: String?, val returnedAt: Long) : PayStep
    data class NotConfirmed(val orderId: String?, val cancelled: Boolean, val reason: String?, val at: Long) : PayStep
    data class NotVerified(val orderId: String?, val reason: String?) : PayStep
    data class Verified(val orderId: String?, val paymentId: String?, val verifiedAt: Long?) : PayStep
}

/**
 * Content only — ProjectScreen renders this inside its Page, under the project TopBar + TabBar,
 * for Route.Project(pid, ProjectTab.Payment, sub = milestoneId) → /p/{pid}/pay/{mid}.
 */
@Composable
fun PaymentScreen(projectId: String, milestoneId: String) {
    val s = LocalServices.current
    val project = rememberLive(projectId, null as Project?) { s.repo.observeProject(projectId) }
    val milestones = rememberLive(projectId, emptyList<Milestone>()) { s.repo.observeMilestones(projectId) }
    val payments = rememberLive(projectId, emptyList<PaymentRecord>()) { s.repo.observePayments(projectId) }

    val p = project.value
    val milestone = milestones.value.firstOrNull { it.id == milestoneId }
    when {
        !project.loaded || !milestones.loaded -> Notice("Loading payment…", null)
        p == null -> Notice("You can’t open this payment", project.error ?: "You don’t have access to this project, or it no longer exists.")
        milestone == null -> Notice("This milestone doesn’t exist", milestones.error ?: "It may have been removed from the agreement.") {
            s.router.navigate(Route.Project(projectId))
        }
        else -> PaymentContent(p, milestone, milestones.value, payments.value)
    }
}

@Composable
private fun Notice(title: String, body: String?, back: (() -> Unit)? = null) {
    WorkspaceLayout(
        main = {
            StatusLine(title, StatusKind.NotStarted, body)
            if (back != null) PillButton("Back to project", back, appearance = PillAppearance.Ghost)
        },
        rail = {},
    )
}

@Composable
private fun PaymentContent(project: Project, milestone: Milestone, milestones: List<Milestone>, payments: List<PaymentRecord>) {
    val s = LocalServices.current
    val user = LocalUser.current ?: return
    val pay = rememberAction()
    val refresh = rememberAction()
    var step by remember(milestone.id) { mutableStateOf<PayStep>(PayStep.Idle) }

    val pid = project.id
    val mid = milestone.id
    val isOwner = project.ownerId == user.uid
    val approved = milestone.status == MilestoneStatus.APPROVED
    val external = milestone.paymentStatus == PaymentState.RECORDED_EXTERNALLY
    val usesReplacementFee = (milestone.replacementFeeMinor ?: 0) > 0
    val amount = if (usesReplacementFee) milestone.replacementFeeMinor!! else milestone.amountMinor
    val attempts = payments.filter { it.milestoneId == mid && it.mode == "test" } // newest first
    val verifiedRecord = attempts.firstOrNull { it.status == PaymentState.VERIFIED_TEST }
    val latest = attempts.firstOrNull()
    fun dateTime(ms: Long) = s.platform.formatDateTime(ms)

    val view: PayStep = when {
        milestone.paymentStatus == PaymentState.VERIFIED_TEST || verifiedRecord != null ->
            (step as? PayStep.Verified) ?: PayStep.Verified(verifiedRecord?.orderId, verifiedRecord?.paymentId, verifiedRecord?.verifiedAt)
        step != PayStep.Idle -> step
        latest?.status == PaymentState.PENDING -> PayStep.Verifying(latest.orderId, latest.updatedAt.takeIf { it > 0 } ?: latest.createdAt)
        latest?.status == PaymentState.NOT_VERIFIED -> PayStep.NotVerified(latest.orderId, latest.failureReason)
        latest?.status == PaymentState.FAILED -> PayStep.NotConfirmed(latest.orderId, false, latest.failureReason, latest.updatedAt.takeIf { it > 0 } ?: latest.createdAt)
        else -> PayStep.Idle
    }

    /** Maps a createPaymentOrder / verifyPayment / refreshPaymentStatus result to a step. Unknown → keep checking, never "paid". */
    fun stepFrom(res: JsonObject, fallbackOrder: String?): PayStep {
        val order = res["orderId"].str() ?: fallbackOrder
        val now = s.platform.now()
        return when (res["status"].str()) {
            "verified" -> PayStep.Verified(order, res["paymentId"].str(), res["verifiedAt"].long())
            "failed" -> PayStep.NotConfirmed(order, cancelled = false, reason = res["reason"].str(), at = now)
            "not_verified" -> PayStep.NotVerified(order, res["reason"].str())
            "not_paid" -> PayStep.NotConfirmed(order, cancelled = false, reason = "Razorpay has no payment for this order yet", at = now)
            "none" -> PayStep.Idle
            else -> PayStep.Verifying(order, now)
        }
    }

    val startPayment: () -> Unit = {
        pay.run {
            step = PayStep.Creating
            try {
                val res = s.repo.createPaymentOrder(pid, mid)
                if (res["status"].str() == "verified") { step = stepFrom(res, null); return@run }
                val orderId = res["orderId"].str() ?: throw RelayError("Razorpay didn’t return an order. Nothing was charged.")
                val keyId = res["keyId"].str().orEmpty().ifBlank { s.platform.razorpayKeyId }
                if (keyId.isBlank()) throw RelayError("Payment is off: Razorpay test mode isn’t configured. Nothing was charged.")
                step = PayStep.InCheckout(orderId)
                val result = s.platform.openCheckout(
                    CheckoutRequest(
                        keyId = keyId, orderId = orderId,
                        amountMinor = res["amountMinor"].long() ?: amount,
                        currency = res["currency"].str() ?: "INR",
                        name = res["name"].str() ?: "Relay",
                        description = res["description"].str() ?: "${milestone.title} · test mode",
                        prefillName = user.displayName, prefillEmail = user.email,
                    )
                )
                step = when (result) {
                    is CheckoutResult.Completed -> {
                        step = PayStep.Verifying(result.orderId, s.platform.now())
                        stepFrom(s.repo.verifyPayment(pid, mid, result.orderId, result.paymentId, result.signature), result.orderId)
                    }
                    CheckoutResult.Dismissed -> PayStep.NotConfirmed(orderId, cancelled = true, reason = null, at = s.platform.now())
                    is CheckoutResult.Failed -> PayStep.NotConfirmed(orderId, cancelled = false, reason = result.reason, at = s.platform.now())
                }
            } catch (t: Throwable) {
                // Before Razorpay returns nothing has happened. After it returns we stay on
                // "Checking payment status…" so the person refreshes instead of paying twice.
                if (step is PayStep.Creating || step is PayStep.InCheckout) step = PayStep.Idle
                throw t
            }
        }
    }
    val refreshStatus: () -> Unit = {
        refresh.run { step = stepFrom(s.repo.refreshPaymentStatus(pid, mid), view.orderId() ?: latest?.orderId) }
    }
    val backToProject: () -> Unit = { s.router.navigate(Route.Project(pid)) }

    val payeeName = milestone.assigneeName?.takeIf { it.isNotBlank() }
    val payeeFirst = payeeName?.substringBefore(' ') ?: "the freelancer"
    val payeeRole = when (milestone.assigneeRole) {
        Roles.REPLACEMENT -> "replacement freelancer"
        Roles.ORIGINAL -> "freelancer"
        else -> null
    }
        WorkspaceLayout(
            main = {
                // Banner — F7-03 approved · F7-05 checking · F7-06 not confirmed · F7-07 unable to verify · F7-08 verified
                when {
                    view is PayStep.Verified -> StatusLine(
                        "Test payment verified.", StatusKind.Done,
                        view.verifiedAt?.let { "Verified on Relay’s server · ${dateTime(it)}" } ?: "Verified on Relay’s server",
                    )
                    external -> StatusLine("Paid outside Relay", StatusKind.Neutral, "Recorded manually · not verified by Relay")
                    !approved -> StatusLine("Payment locked", StatusKind.NotStarted, "Approve ${milestone.title} first. Payment unlocks after approval.")
                    view is PayStep.Verifying -> StatusLine(
                        "Checking payment status…", StatusKind.Pending,
                        "Razorpay returned at ${dateTime(view.returnedAt)}. Relay is verifying the signature and amount on the server.",
                    )
                    view is PayStep.NotConfirmed -> StatusLine(
                        "Payment not confirmed", StatusKind.Attention,
                        if (view.cancelled) "Checkout was cancelled at ${dateTime(view.at)}. Nothing was charged."
                        else "${(view.reason ?: "Razorpay didn’t confirm the payment").trimEnd('.')}. Nothing was marked paid.",
                    )
                    view is PayStep.NotVerified -> StatusLine("Unable to verify payment", StatusKind.Attention, notVerifiedText(view.reason))
                    else -> StatusLine(
                        "${milestone.title} approved.", StatusKind.Done,
                        listOfNotNull(
                            if (isOwner) "Approved by you" else "Approved by ${project.ownerName.ifBlank { "the client" }}",
                            milestone.approvedAt?.let { dateTime(it) },
                            milestone.approvedLabel?.let { "$it recorded" },
                        ).joinToString(" · "),
                    )
                }

                if (view is PayStep.Verified) {
                    TitleBlock(
                        "Completion", "${milestone.title} is approved and paid",
                        "Verified in Razorpay test mode. No real money moved, and Relay doesn’t pay $payeeFirst out automatically.",
                    )
                    val rec = verifiedRecord
                    val paidAmount = rec?.amountMinor ?: amount
                    Section("Payment evidence", meta = "Razorpay test mode") {
                        ListCard(listOf(
                            ListRowSpec("Amount", "${formatInr(paidAmount * 100)} paise · INR", trailing = { Amount(rupees(paidAmount)) }),
                            ListRowSpec("Razorpay order", "${shortId(view.orderId ?: rec?.orderId) ?: "—"} · created on Relay’s server"),
                            ListRowSpec("Razorpay payment", shortId(view.paymentId ?: rec?.paymentId) ?: "—"),
                            ListRowSpec(
                                "Verification", "Signature, order and amount checked on Relay’s server",
                                trailing = { StatusPill("Verified", StatusKind.Done) },
                            ),
                        ))
                    }
                } else {
                    TitleBlock(
                        "Payment · Razorpay test mode", "Pay ${rupees(amount)} for ${milestone.title}",
                        "Razorpay Test Checkout. No real money moves, and Relay doesn’t pay $payeeFirst out automatically.",
                    )
                    Section("Payment details") {
                        ListCard(listOf(
                            ListRowSpec(
                                "${milestone.title} · Milestone ${milestone.index}",
                                if (approved) listOfNotNull(
                                    if (isOwner) "Approved by you" else "Approved",
                                    milestone.approvedAt?.let { "on ${dateTime(it)}" },
                                ).joinToString(" ") else "Not approved yet — payment unlocks after approval",
                                trailing = { StatusPill(if (approved) "Approved" else "Not approved", if (approved) StatusKind.Done else StatusKind.NotStarted) },
                            ),
                            ListRowSpec("Payee", listOfNotNull(payeeName ?: "Not assigned yet", payeeRole).joinToString(" · ")),
                            ListRowSpec(
                                "Amount",
                                "${formatInr(amount * 100)} paise · INR · " + if (usesReplacementFee) "the fee agreed with the replacement" else "matches the agreed fee",
                                trailing = { Amount(rupees(amount)) },
                            ),
                            ListRowSpec(
                                "Mode", "Order is created on Relay’s server, not in the browser",
                                trailing = { StatusPill("Test mode", StatusKind.Neutral) },
                            ),
                        ))
                    }
                }

                // Earlier payments stay visibly separate (IA: recorded ≠ verified).
                val milestoneTitle = milestones.associate { it.id to it.title }
                val earlier = payments.filter {
                    it.status == PaymentState.RECORDED_EXTERNALLY || (it.status == PaymentState.VERIFIED_TEST && it.milestoneId != mid)
                }
                if (earlier.isNotEmpty()) {
                    Section("Earlier payments", meta = "Shown separately") {
                        ListCard(earlier.map { r ->
                            val outside = r.status == PaymentState.RECORDED_EXTERNALLY
                            ListRowSpec(
                                listOfNotNull(milestoneTitle[r.milestoneId] ?: "Milestone", r.payee.ifBlank { null }).joinToString(" · "),
                                if (outside) "Recorded ${if (r.recordedBy == user.uid) "by you " else ""}on ${s.platform.formatDate(r.createdAt)} · not verified by Relay"
                                else "Verified on Relay’s server · ${s.platform.formatDate(r.verifiedAt ?: r.createdAt)} · test mode",
                                muted = true,
                                trailing = {
                                    StatusPill(if (outside) "Paid outside Relay" else "Paid (verified)", if (outside) StatusKind.Neutral else StatusKind.Done)
                                    Amount(rupees(r.amountMinor), muted = true)
                                },
                            )
                        })
                    }
                }
            },
            rail = {
                when {
                    view is PayStep.Verified -> {
                        val next = milestones.filter {
                            it.id != mid && it.paymentStatus != PaymentState.VERIFIED_TEST && it.paymentStatus != PaymentState.RECORDED_EXTERNALLY
                        }.minByOrNull { it.index }
                        NextStepPanel(
                            "Project continues",
                            next?.let { "Next up: ${it.title} (${rupees(it.amountMinor)})." } ?: "Every milestone on this project is now paid.",
                            "${milestone.title} · Paid (verified)",
                            primary = ActionSpec("Back to project", backToProject),
                        )
                    }
                    external -> NextStepPanel(
                        "Nothing to pay here", "${milestone.title} is recorded as paid outside Relay. Relay doesn’t verify manual records.",
                        null, primary = ActionSpec("Back to project", backToProject),
                    )
                    !approved -> NextStepPanel(
                        "Payment is locked", "Approve ${milestone.title} first. Payment unlocks after approval.",
                        "Locked until approval", primary = ActionSpec("Back to project", backToProject),
                    )
                    !isOwner -> NextStepPanel(
                        "Waiting for ${project.ownerName.ifBlank { "the client" }} to pay",
                        "Only the client pays. This shows “Paid (verified)” once Razorpay confirms it on Relay’s server.",
                        "Test mode · no real money",
                        secondary = listOf(ActionSpec("Refresh status", refreshStatus, loading = refresh.running)),
                    )
                    view is PayStep.Verifying -> NextStepPanel(
                        "Checking payment status…",
                        "Don’t pay again. Relay waits for server-side verification — the browser callback alone is never enough.",
                        "Usually takes a few seconds",
                        primary = ActionSpec("Refresh status", refreshStatus, loading = refresh.running),
                    )
                    view is PayStep.NotConfirmed -> NextStepPanel(
                        "Payment not confirmed. You can try again.",
                        "Your approval is kept. ${milestone.title} stays approved and unpaid until a payment is verified.",
                        "${if (view.cancelled) "Cancelled" else "Not paid"} · ${dateTime(view.at)}",
                        primary = ActionSpec("Try again", startPayment, loading = pay.running),
                        secondary = listOf(ActionSpec("Refresh status", refreshStatus, loading = refresh.running)),
                    )
                    view is PayStep.NotVerified -> NextStepPanel(
                        "Unable to verify payment",
                        "If you were charged, it will be reconciled from Razorpay’s records. Refresh the status to check again.",
                        "Milestone stays unpaid",
                        primary = ActionSpec("Refresh status", refreshStatus, loading = refresh.running),
                    )
                    else -> NextStepPanel(
                        "Pay ${rupees(amount)}",
                        "Opens Razorpay test checkout. Relay marks the milestone paid only after our server verifies the payment.",
                        "Test mode · no real money",
                        primary = ActionSpec(
                            when (view) {
                                PayStep.Creating -> "Creating order"
                                is PayStep.InCheckout -> "Waiting for Razorpay"
                                else -> "Pay ${rupees(amount)}"
                            },
                            startPayment, loading = pay.running,
                        ),
                    )
                }
                ErrorText(pay.error ?: refresh.error)

                ContextPanel(
                    "Payment", milestone.title, "Item" to "Status",
                    listOf(
                        "Approval" to (milestone.approvedAt?.let { "Approved ${s.platform.formatDate(it)}" } ?: if (approved) "Approved" else "Not approved"),
                        "Order" to (shortId(view.orderId() ?: latest?.orderId) ?: "Not created yet"),
                        "Payment" to when {
                            view is PayStep.Verified -> "Paid (verified)"
                            external -> "Paid outside Relay"
                            view is PayStep.Creating -> "Creating order"
                            view is PayStep.InCheckout -> "In checkout"
                            view is PayStep.Verifying -> "Received · not verified"
                            view is PayStep.NotConfirmed -> if (view.cancelled) "Not paid · cancelled" else "Not paid"
                            view is PayStep.NotVerified -> "Not verified"
                            else -> "Not paid"
                        },
                        "Verified" to when (view) {
                            is PayStep.Verified -> view.verifiedAt?.let { s.platform.formatDate(it) } ?: "Yes"
                            is PayStep.Verifying -> "Pending"
                            is PayStep.NotVerified -> if (view.reason == "amount_mismatch") "Amount mismatch" else "Signature mismatch"
                            else -> "—"
                        },
                    ),
                )
            },
        )
}

private fun PayStep.orderId(): String? = when (this) {
    is PayStep.InCheckout -> orderId
    is PayStep.Verifying -> orderId
    is PayStep.NotConfirmed -> orderId
    is PayStep.NotVerified -> orderId
    is PayStep.Verified -> orderId
    PayStep.Idle, PayStep.Creating -> null
}

private fun notVerifiedText(reason: String?): String = when (reason) {
    "amount_mismatch" -> "The amount Razorpay received didn’t match this milestone’s fee. Relay never marks a payment paid without verification."
    else -> "Razorpay’s signature didn’t match this order. Relay never marks a payment paid without verification."
}

/** order_Pk2XqAbcDef7Yh → order_Pk2Xq…7Yh (as in the Figma frames). */
private fun shortId(id: String?): String? = id?.let { if (it.length > 16) it.take(11) + "…" + it.takeLast(3) else it }

private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
