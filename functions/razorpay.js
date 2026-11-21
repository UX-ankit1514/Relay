// Razorpay helpers with no Firebase dependency, so they can be unit-tested on their own.
// Docs: https://razorpay.com/docs/payments/server-integration/nodejs/
import { createHmac, timingSafeEqual } from "node:crypto";

const API = "https://api.razorpay.com/v1";

export const ORDER_ID = /^order_[A-Za-z0-9]{6,40}$/;
export const PAYMENT_ID = /^pay_[A-Za-z0-9]{6,40}$/;
export const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;

export function hmacHex(secret, data) {
  return createHmac("sha256", secret).update(data).digest("hex");
}

/** Constant-time string compare. Returns false on any length mismatch instead of throwing. */
export function safeEqual(a, b) {
  const x = Buffer.from(String(a ?? ""), "utf8");
  const y = Buffer.from(String(b ?? ""), "utf8");
  return x.length === y.length && timingSafeEqual(x, y);
}

/** Checkout handler signature: HMAC_SHA256(order_id + "|" + payment_id, key_secret). */
export function isValidCheckoutSignature({ orderId, paymentId, signature, keySecret }) {
  if (!keySecret || !ORDER_ID.test(orderId ?? "") || !PAYMENT_ID.test(paymentId ?? "")) return false;
  return safeEqual(hmacHex(keySecret, `${orderId}|${paymentId}`), signature);
}

/** Webhook signature: HMAC_SHA256(raw request body, webhook_secret). */
export function isValidWebhookSignature({ rawBody, signature, webhookSecret }) {
  if (!webhookSecret || !rawBody) return false;
  return safeEqual(hmacHex(webhookSecret, rawBody), signature);
}

/** Razorpay receipts are capped at 40 characters. */
export function receiptFor(milestoneId, now = Date.now()) {
  return `rl_${String(milestoneId).slice(0, 24)}_${now.toString(36)}`.slice(0, 40);
}

/** Relay only takes test-mode payments in the Buildathon MVP. Live keys are refused. */
export function isTestKey(keyId) {
  return typeof keyId === "string" && keyId.startsWith("rzp_test_");
}

export class RazorpayApiError extends Error {
  constructor(status, description) {
    super(description || `Razorpay request failed (${status})`);
    this.status = status;
  }
}

export function razorpayClient({ keyId, keySecret, fetchImpl = fetch }) {
  const auth = "Basic " + Buffer.from(`${keyId}:${keySecret}`).toString("base64");
  async function call(method, path, body) {
    const res = await fetchImpl(API + path, {
      method,
      headers: { Authorization: auth, "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new RazorpayApiError(res.status, json?.error?.description);
    return json;
  }
  return {
    createOrder: ({ amount, currency, receipt, notes }) => call("POST", "/orders", { amount, currency, receipt, notes }),
    fetchOrder: (orderId) => call("GET", `/orders/${orderId}`),
    fetchOrderPayments: (orderId) => call("GET", `/orders/${orderId}/payments`),
    fetchPayment: (paymentId) => call("GET", `/payments/${paymentId}`),
    capturePayment: (paymentId, amount, currency) => call("POST", `/payments/${paymentId}/capture`, { amount, currency }),
  };
}
