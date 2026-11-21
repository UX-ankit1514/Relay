import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { isValidCheckoutSignature, isValidWebhookSignature, isTestKey, receiptFor, safeEqual } from "../razorpay.js";

const secret = "test_secret_123";
const sign = (data, key = secret) => createHmac("sha256", key).update(data).digest("hex");

test("accepts a genuine checkout signature", () => {
  const orderId = "order_Pk2XqAbc7Yh", paymentId = "pay_Pk2YrXyz3Lm";
  assert.equal(isValidCheckoutSignature({ orderId, paymentId, signature: sign(`${orderId}|${paymentId}`), keySecret: secret }), true);
});

test("rejects tampered, swapped or empty checkout signatures", () => {
  const orderId = "order_Pk2XqAbc7Yh", paymentId = "pay_Pk2YrXyz3Lm";
  const good = sign(`${orderId}|${paymentId}`);
  assert.equal(isValidCheckoutSignature({ orderId, paymentId, signature: good.slice(0, -1) + (good.endsWith("0") ? "1" : "0"), keySecret: secret }), false);
  assert.equal(isValidCheckoutSignature({ orderId: "order_OtherOrder1", paymentId, signature: good, keySecret: secret }), false);
  assert.equal(isValidCheckoutSignature({ orderId, paymentId, signature: sign(`${orderId}|${paymentId}`, "wrong"), keySecret: secret }), false);
  assert.equal(isValidCheckoutSignature({ orderId, paymentId, signature: "", keySecret: secret }), false);
  assert.equal(isValidCheckoutSignature({ orderId, paymentId, signature: good, keySecret: "" }), false);
  assert.equal(isValidCheckoutSignature({ orderId: "../orders", paymentId, signature: good, keySecret: secret }), false);
});

test("webhook signature is checked over the raw body", () => {
  const raw = Buffer.from('{"event":"payment.captured"}');
  assert.equal(isValidWebhookSignature({ rawBody: raw, signature: sign(raw), webhookSecret: secret }), true);
  assert.equal(isValidWebhookSignature({ rawBody: Buffer.from('{"event":"payment.captured" }'), signature: sign(raw), webhookSecret: secret }), false);
  assert.equal(isValidWebhookSignature({ rawBody: raw, signature: undefined, webhookSecret: secret }), false);
});

test("only test keys are allowed", () => {
  assert.equal(isTestKey("rzp_test_DemoKeyId0001"), true);
  assert.equal(isTestKey("rzp_live_abc"), false);
  assert.equal(isTestKey(""), false);
});

test("receipt stays within Razorpay's 40-character limit", () => {
  assert.ok(receiptFor("x".repeat(100)).length <= 40);
});

test("safeEqual handles length mismatch without throwing", () => {
  assert.equal(safeEqual("abc", "abcd"), false);
  assert.equal(safeEqual(null, undefined), true);
});
