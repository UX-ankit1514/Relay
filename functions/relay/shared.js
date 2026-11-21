// Shared helpers for the Relay app functions. Firestore is resolved lazily because these modules
// are evaluated (ESM import hoisting) before ../index.js calls initializeApp().
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { randomBytes } from "node:crypto";

export { FieldValue, HttpsError };

// Region is set per function: setGlobalOptions() in ../index.js runs after these modules load.
export const CALL_OPTS = { region: "asia-south1", maxInstances: 10 };
export const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000;
export const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;
export const ROLES = { OWNER: "owner", ORIGINAL: "original_freelancer", REPLACEMENT: "replacement_freelancer" };

export const db = () => getFirestore();
export const now = () => Date.now();

export function requireUser(req) {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in with Google to continue.");
  const t = req.auth.token ?? {};
  const email = typeof t.email === "string" ? t.email : "";
  const name = (typeof t.name === "string" && t.name.trim()) || (email ? email.split("@")[0] : "Someone");
  return { uid: req.auth.uid, name, email };
}

export function argId(value, name) {
  if (typeof value !== "string" || !DOC_ID.test(value)) throw new HttpsError("invalid-argument", `Missing or invalid ${name}.`);
  return value;
}

export function text(value, name, { max = 500, optional = false } = {}) {
  if (value === undefined || value === null || (typeof value === "string" && !value.trim())) {
    if (optional) return "";
    throw new HttpsError("invalid-argument", `Add ${name}.`);
  }
  if (typeof value !== "string") throw new HttpsError("invalid-argument", `Invalid ${name}.`);
  const v = value.trim();
  if (v.length > max) throw new HttpsError("invalid-argument", `${name} is too long (max ${max} characters).`);
  return v;
}

export function paise(value, name = "fee") {
  if (!Number.isInteger(value) || value <= 0 || value > 1_000_000_000) throw new HttpsError("invalid-argument", `Enter a ${name} above ₹0.`);
  return value;
}

export function isHttpUrl(u) {
  if (typeof u !== "string" || !u.trim() || u.length > 2048) return false;
  try {
    const x = new URL(/^https?:\/\//i.test(u) ? u : "https://" + u);
    return (x.protocol === "https:" || x.protocol === "http:") && x.hostname.includes(".");
  } catch { return false; }
}

export async function loadProject(pid, tx) {
  const ref = db().doc(`projects/${pid}`);
  const snap = tx ? await tx.get(ref) : await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "This project doesn’t exist.");
  return { ref, p: snap.data() };
}

export function assertOwner(p, uid) {
  if (p.ownerId !== uid) throw new HttpsError("permission-denied", "Only the client who owns this project can do that.");
}

export function assertMember(p, uid) {
  if (!Array.isArray(p.memberIds) || !p.memberIds.includes(uid)) throw new HttpsError("permission-denied", "Access not permitted.");
}

/** Appends an audit event (PRD FR-026). `writer` is a transaction or batch. */
export function audit(writer, pid, actor, type, entityId, detail) {
  const ref = db().collection(`projects/${pid}/events`).doc();
  writer.set(ref, {
    id: ref.id, actorId: actor.uid, actorName: actor.name, type, entityId: entityId ?? "",
    detail: String(detail ?? "").slice(0, 300), createdAt: now(),
  });
}

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
/** Unguessable share token (~170 bits for 30 chars). */
export function token(len = 30) {
  const bytes = randomBytes(len);
  let out = "";
  for (let i = 0; i < len; i++) out += ALPHABET[bytes[i] % ALPHABET.length];
  return out;
}

export const rupees = (p) => "₹" + (p / 100).toLocaleString("en-IN", { maximumFractionDigits: 2 });
