// Relay · Cloud Functions for F7 "Approve & pay" (Razorpay test mode).
//
// Rules this file enforces (IA §1, PRD §27):
//  • Orders are created here, never in the browser. The amount comes from the milestone, not the client.
//  • A milestone becomes "Paid (verified)" only after this server checks the checkout signature AND
//    fetches the payment from Razorpay (order, amount, currency, captured). The browser callback alone is never enough.
//  • Verification is idempotent (Firestore transaction), so duplicate clicks or webhooks never double-record.
//  • Only test keys (rzp_test_…) are accepted. No real money moves in the Buildathon MVP.
//
// Secrets: RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET live in Secret Manager
// (functions/.secret.local for the emulator). RAZORPAY_KEY_ID is public and comes from functions/.env.

import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { setGlobalOptions } from "firebase-functions/v2";
import { onCall, onRequest, HttpsError } from "firebase-functions/v2/https";
import { defineSecret, defineString } from "firebase-functions/params";
import * as logger from "firebase-functions/logger";
import {
  DOC_ID, ORDER_ID, PAYMENT_ID, RazorpayApiError, isTestKey, isValidCheckoutSignature,
  isValidWebhookSignature, razorpayClient, receiptFor,
} from "./razorpay.js";

initializeApp();
const db = getFirestore();

// Must match getFunctions(app, "asia-south1") in composeApp FirebaseServices.kt.
setGlobalOptions({ region: "asia-south1", maxInstances: 10 });

const RAZORPAY_KEY_ID = defineString("RAZORPAY_KEY_ID");
const RAZORPAY_KEY_SECRET = defineSecret("RAZORPAY_KEY_SECRET");
const RAZORPAY_WEBHOOK_SECRET = defineSecret("RAZORPAY_WEBHOOK_SECRET");

// Mirrors app.relay.model.PaymentState.
const S = {
  UNPAID: "unpaid", EXTERNAL: "recorded_externally", CREATED: "created", PENDING: "pending",
  VERIFIED: "verified_test", FAILED: "failed", NOT_VERIFIED: "not_verified",
};
const OPEN = new Set([S.CREATED, S.PENDING, S.FAILED, S.NOT_VERIFIED]);
const SYSTEM = { uid: "relay-server", name: "Relay server" };

const inr = (paise) => "₹" + (paise / 100).toLocaleString("en-IN", { maximumFractionDigits: 2 });
/** Replacement milestones are paid at the fee agreed with the replacement; otherwise the agreed amount. */
const payableMinor = (m) => (Number.isInteger(m.replacementFeeMinor) && m.replacementFeeMinor > 0 ? m.replacementFeeMinor : m.amountMinor);
const payRef = (projectId, orderId) => db.doc(`projects/${projectId}/payments/${orderId}`);
const msRef = (projectId, milestoneId) => db.doc(`projects/${projectId}/milestones/${milestoneId}`);

function rzp() {
  const keyId = RAZORPAY_KEY_ID.value();
  if (!isTestKey(keyId)) {
    throw new HttpsError("failed-precondition", "Razorpay test mode isn’t set up on the server, so payments are off. Nothing was charged.");
  }
  return razorpayClient({ keyId, keySecret: RAZORPAY_KEY_SECRET.value() });
}

function arg(value, name, pattern = DOC_ID) {
  if (typeof value !== "string" || !pattern.test(value)) throw new HttpsError("invalid-argument", `Missing or invalid ${name}.`);
  return value;
}

function audit(writer, projectId, actor, type, entityId, detail) {
  const ref = db.collection(`projects/${projectId}/events`).doc();
  writer.set(ref, { id: ref.id, actorId: actor.uid, actorName: actor.name, type, entityId, detail, createdAt: Date.now() });
}

async function loadMilestone(auth, projectId, milestoneId, { ownerOnly }) {
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Your session expired. Sign in again.");
  const pSnap = await db.doc(`projects/${projectId}`).get();
  const project = pSnap.data();
  const isOwner = project?.ownerId === auth.uid;
  if (!pSnap.exists || !(isOwner || (project.memberIds ?? []).includes(auth.uid))) {
    throw new HttpsError("permission-denied", "You don’t have access to this project.");
  }
  if (ownerOnly && !isOwner) throw new HttpsError("permission-denied", "Only the client who owns this project can pay.");
  const mSnap = await msRef(projectId, milestoneId).get();
  if (!mSnap.exists) throw new HttpsError("not-found", "That milestone no longer exists.");
  return { project, milestone: { ...mSnap.data(), id: mSnap.id }, actor: { uid: auth.uid, name: auth.token?.name ?? auth.token?.email ?? "Client" } };
}

function verifiedResult(rec) {
  return { status: "verified", orderId: rec.orderId, paymentId: rec.paymentId, verifiedAt: rec.verifiedAt, amountMinor: rec.amountMinor, currency: rec.currency };
}

/** Records a non-final outcome. Never downgrades a verified payment or a milestone paid outside Relay. */
async function markOpen({ projectId, milestoneId, orderId }, status, reason, paymentId) {
  return db.runTransaction(async (tx) => {
    const [pay, ms] = await Promise.all([tx.get(payRef(projectId, orderId)), tx.get(msRef(projectId, milestoneId))]);
    if (!pay.exists) throw new HttpsError("failed-precondition", "This payment doesn’t match a Relay milestone.");
    const rec = pay.data();
    if (rec.status === S.VERIFIED) return verifiedResult(rec);
    tx.update(pay.ref, {
      status, failureReason: reason ?? null, updatedAt: Date.now(), ...(paymentId ? { paymentId } : {}),
    });
    const msStatus = ms.data()?.paymentStatus;
    if (ms.exists && msStatus !== S.VERIFIED && msStatus !== S.EXTERNAL) {
      tx.update(ms.ref, { paymentStatus: status === S.PENDING ? S.PENDING : S.UNPAID });
    }
    if (status === S.NOT_VERIFIED || status === S.FAILED) {
      audit(tx, projectId, SYSTEM, status === S.FAILED ? "payment_failed" : "payment_not_verified", milestoneId, `${orderId} · ${reason ?? status}`);
    }
    return { status: status === S.NOT_VERIFIED ? "not_verified" : status, orderId, paymentId: paymentId ?? rec.paymentId ?? null, reason: reason ?? null };
  });
}

/** The only place a milestone becomes paid. Runs once per order thanks to the transaction. */
async function markVerified({ projectId, milestoneId, orderId }, payment, via) {
  return db.runTransaction(async (tx) => {
    const [pay, ms] = await Promise.all([tx.get(payRef(projectId, orderId)), tx.get(msRef(projectId, milestoneId))]);
    if (!pay.exists || !ms.exists) throw new HttpsError("failed-precondition", "This payment doesn’t match a Relay milestone.");
    const rec = pay.data();
    if (rec.status === S.VERIFIED) return { ...verifiedResult(rec), alreadyVerified: true };
    const handoffId = ms.data().handoffId;
    const handoff = typeof handoffId === "string" && DOC_ID.test(handoffId) ? await tx.get(db.doc(`handoffs/${handoffId}`)) : null;
    const now = Date.now();
    tx.update(pay.ref, {
      status: S.VERIFIED, paymentId: payment.id, verifiedAt: now, method: payment.method ?? null,
      verifiedVia: via, failureReason: null, updatedAt: now,
    });
    tx.update(ms.ref, { paymentStatus: S.VERIFIED, paidVerifiedMinor: rec.amountMinor });
    if (handoff?.exists) tx.update(handoff.ref, { status: "completed", completedAt: now });
    audit(tx, projectId, SYSTEM, "payment_verified", milestoneId, `${inr(rec.amountMinor)} · ${payment.id} · test mode`);
    return { status: "verified", orderId, paymentId: payment.id, verifiedAt: now, amountMinor: rec.amountMinor, currency: rec.currency };
  });
}

/** Turns a Razorpay payment entity into Relay state: checks order + amount, captures if needed, then records. */
async function settle(client, ctx, payment, via) {
  const rec = (await payRef(ctx.projectId, ctx.orderId).get()).data();
  if (!rec) throw new HttpsError("failed-precondition", "This payment doesn’t match a Relay milestone.");
  if (rec.status === S.VERIFIED) return verifiedResult(rec);
  if (payment.order_id !== ctx.orderId || payment.amount !== rec.amountMinor || payment.currency !== rec.currency) {
    logger.warn("payment_mismatch", { ...ctx, paymentId: payment.id, amount: payment.amount, expected: rec.amountMinor });
    return markOpen(ctx, S.NOT_VERIFIED, "amount_mismatch", payment.id);
  }
  let p = payment;
  if (p.status === "authorized") {
    try {
      p = await client.capturePayment(p.id, rec.amountMinor, rec.currency);
    } catch (e) {
      // Auto-capture may have won the race. Re-read the payment instead of failing.
      logger.info("capture_retry_fetch", { paymentId: p.id, message: e.message });
      p = await client.fetchPayment(p.id);
    }
  }
  switch (p.status) {
    case "captured": return markVerified(ctx, p, via);
    case "failed": return markOpen(ctx, S.FAILED, p.error_description ?? "payment_failed", p.id);
    case "refunded": return markOpen(ctx, S.FAILED, "refunded", p.id);
    default: return markOpen(ctx, S.PENDING, null, p.id);
  }
}

/** Asks Razorpay what happened to an order. Used by Refresh status and before reusing an order. */
async function reconcileOrder(client, ctx, via) {
  const { items = [] } = await client.fetchOrderPayments(ctx.orderId);
  const pick = items.find((p) => p.status === "captured")
    ?? items.find((p) => p.status === "authorized")
    ?? [...items].sort((a, b) => b.created_at - a.created_at)[0];
  if (!pick) return { status: "not_paid", orderId: ctx.orderId };
  return settle(client, ctx, pick, via);
}

async function testRecords(projectId, milestoneId) {
  const snap = await db.collection(`projects/${projectId}/payments`)
    .where("milestoneId", "==", milestoneId).where("mode", "==", "test").get();
  return snap.docs.map((d) => d.data()).sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
}

function checkoutPayload(orderId, milestone, project) {
  return {
    status: "created", orderId, amountMinor: payableMinor(milestone), currency: milestone.currency || "INR",
    keyId: RAZORPAY_KEY_ID.value(), name: "Relay",
    description: `${milestone.title || "Milestone"} · ${project.name || "Relay project"} · test mode`,
  };
}

/** onCall wrapper: Razorpay/API failures become plain-language errors; nothing is ever shown as paid. */
const callable = (handler) => onCall({ secrets: [RAZORPAY_KEY_SECRET] }, async (req) => {
  try {
    return await handler(req);
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    if (e instanceof RazorpayApiError) {
      logger.error("razorpay_api_error", { status: e.status, message: e.message });
      throw new HttpsError("unavailable", e.status === 401
        ? "Razorpay rejected Relay’s test keys. Nothing was charged."
        : "Razorpay didn’t respond as expected. Nothing was marked paid — try again in a moment.");
    }
    logger.error("payment_function_error", e);
    throw new HttpsError("internal", "Something went wrong on our side. Nothing was marked paid — try again.");
  }
});

// ── createPaymentOrder ──────────────────────────────────────────────────────
// In:  { projectId, milestoneId }
// Out: { status: "created", orderId, amountMinor, currency, keyId, name, description }
//   or { status: "verified", alreadyPaid: true, … } when the milestone is already paid.
export const createPaymentOrder = callable(async (req) => {
  const projectId = arg(req.data?.projectId, "project");
  const milestoneId = arg(req.data?.milestoneId, "milestone");
  const { project, milestone, actor } = await loadMilestone(req.auth, projectId, milestoneId, { ownerOnly: true });

  if (milestone.paymentStatus === S.VERIFIED) {
    const rec = (await testRecords(projectId, milestoneId)).find((r) => r.status === S.VERIFIED);
    return { ...(rec ? verifiedResult(rec) : { status: "verified" }), alreadyPaid: true };
  }
  if (milestone.paymentStatus === S.EXTERNAL) {
    throw new HttpsError("failed-precondition", "This milestone is already recorded as paid outside Relay, so there’s nothing to pay here.");
  }
  if (milestone.status !== "approved") {
    throw new HttpsError("failed-precondition", "Approve this milestone before paying. Payment unlocks after approval.");
  }
  const amount = payableMinor(milestone);
  const currency = milestone.currency || "INR";
  if (currency !== "INR" || !Number.isInteger(amount) || amount < 100 || amount > 50_000_000) {
    throw new HttpsError("failed-precondition", "This milestone’s fee can’t be paid in test mode. Check the agreed amount.");
  }
  const client = rzp();

  // Reuse the open order for this milestone, so a retry never creates a second chargeable order.
  const open = (await testRecords(projectId, milestoneId))
    .find((r) => OPEN.has(r.status) && r.amountMinor === amount && r.currency === currency);
  if (open) {
    const order = await client.fetchOrder(open.orderId);
    if (order.status !== "paid") return checkoutPayload(open.orderId, milestone, project);
    const settled = await reconcileOrder(client, { projectId, milestoneId, orderId: open.orderId }, "order_reuse_check");
    if (settled.status === "verified") return { ...settled, alreadyPaid: true };
    throw new HttpsError("failed-precondition", "Razorpay has a payment for this milestone that Relay couldn’t verify. Refresh the status before paying again.");
  }

  const order = await client.createOrder({
    amount, currency, receipt: receiptFor(milestoneId),
    notes: { relayProjectId: projectId, relayMilestoneId: milestoneId, mode: "test" },
  });
  const now = Date.now();
  const batch = db.batch();
  batch.set(payRef(projectId, order.id), {
    id: order.id, milestoneId, amountMinor: amount, currency, mode: "test", status: S.CREATED,
    orderId: order.id, paymentId: null, payee: milestone.assigneeName ?? "", note: "",
    recordedBy: actor.uid, createdAt: now, verifiedAt: null, failureReason: null,
  });
  batch.set(db.doc(`paymentOrders/${order.id}`), { projectId, milestoneId, amountMinor: amount, currency, createdAt: now });
  audit(batch, projectId, actor, "payment_order_created", milestoneId, `${inr(amount)} · ${order.id} · test mode`);
  await batch.commit();
  return checkoutPayload(order.id, milestone, project);
});

// ── verifyPayment ───────────────────────────────────────────────────────────
// In:  { projectId, milestoneId, orderId, paymentId, signature } — exactly what Checkout's handler returned.
// Out: { status: "verified" | "pending" | "failed" | "not_verified", orderId, paymentId, reason?, verifiedAt? }
export const verifyPayment = callable(async (req) => {
  const projectId = arg(req.data?.projectId, "project");
  const milestoneId = arg(req.data?.milestoneId, "milestone");
  const orderId = arg(req.data?.orderId, "order", ORDER_ID);
  const paymentId = arg(req.data?.paymentId, "payment", PAYMENT_ID);
  const signature = typeof req.data?.signature === "string" ? req.data.signature : "";
  await loadMilestone(req.auth, projectId, milestoneId, { ownerOnly: true });

  const rec = (await payRef(projectId, orderId).get()).data();
  if (!rec || rec.milestoneId !== milestoneId || rec.mode !== "test") {
    throw new HttpsError("failed-precondition", "This payment doesn’t belong to this milestone.");
  }
  if (rec.status === S.VERIFIED) return verifiedResult(rec);

  const client = rzp();
  const ctx = { projectId, milestoneId, orderId };
  if (!isValidCheckoutSignature({ orderId, paymentId, signature, keySecret: RAZORPAY_KEY_SECRET.value() })) {
    logger.warn("checkout_signature_mismatch", ctx);
    return markOpen(ctx, S.NOT_VERIFIED, "signature_mismatch", paymentId);
  }
  return settle(client, ctx, await client.fetchPayment(paymentId), "checkout_signature");
});

// ── refreshPaymentStatus ────────────────────────────────────────────────────
// In:  { projectId, milestoneId }  ·  Any project member may ask.
// Out: { status: "none" | "not_paid" | "pending" | "failed" | "not_verified" | "verified", … }
export const refreshPaymentStatus = callable(async (req) => {
  const projectId = arg(req.data?.projectId, "project");
  const milestoneId = arg(req.data?.milestoneId, "milestone");
  await loadMilestone(req.auth, projectId, milestoneId, { ownerOnly: false });

  const records = await testRecords(projectId, milestoneId);
  const done = records.find((r) => r.status === S.VERIFIED);
  if (done) return verifiedResult(done);
  if (records.length === 0) return { status: "none" };

  const client = rzp();
  let latest = null;
  for (const rec of records.slice(0, 5)) {
    const r = await reconcileOrder(client, { projectId, milestoneId, orderId: rec.orderId }, "refresh");
    if (r.status === "verified") return r;
    latest ??= r;
  }
  return latest;
});

// ── razorpayWebhook ─────────────────────────────────────────────────────────
// Razorpay Dashboard → Webhooks → URL of this function, secret = RAZORPAY_WEBHOOK_SECRET,
// events: payment.captured, payment.failed, order.paid. Each event id is processed once.
export const razorpayWebhook = onRequest(
  { secrets: [RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET], invoker: "public" },
  async (req, res) => {
    if (req.method !== "POST") { res.status(405).send("Method not allowed"); return; }
    const valid = isValidWebhookSignature({
      rawBody: req.rawBody, signature: req.get("x-razorpay-signature"), webhookSecret: RAZORPAY_WEBHOOK_SECRET.value(),
    });
    if (!valid) { res.status(400).send("Invalid signature"); return; }

    const event = req.body ?? {};
    const eventId = req.get("x-razorpay-event-id");
    const evRef = eventId && DOC_ID.test(eventId) ? db.doc(`webhookEvents/${eventId}`) : null;
    if (evRef && (await evRef.get()).data()?.processed) { res.status(200).send("Already processed"); return; }

    let outcome = "ignored";
    const payment = event.payload?.payment?.entity;
    const relevant = ["payment.captured", "payment.authorized", "payment.failed", "order.paid"].includes(event.event);
    try {
      if (relevant && payment && ORDER_ID.test(payment.order_id ?? "")) {
        const map = (await db.doc(`paymentOrders/${payment.order_id}`).get()).data();
        if (map) {
          const r = await settle(rzp(), { projectId: map.projectId, milestoneId: map.milestoneId, orderId: payment.order_id }, payment, "webhook");
          outcome = r.status;
        }
      }
    } catch (e) {
      logger.error("webhook_processing_failed", { eventId, event: event.event, message: e.message });
      res.status(500).send("Retry later"); // Razorpay retries; settle() is idempotent.
      return;
    }
    if (evRef) await evRef.set({ event: event.event ?? null, outcome, processed: true, receivedAt: Date.now() });
    res.status(200).send("OK");
  },
);

// Relay app functions (invites, agreements, decisions, reviews, handoffs) — owned by the app session.
export * from "./relay/index.js";
