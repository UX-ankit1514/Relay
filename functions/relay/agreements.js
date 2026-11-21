// Agreement acceptance (PRD AC-002, FR-003/FR-004). Recorded server-side so the acknowledgment
// timestamp and the version that was accepted can't be forged by a client.
import { onCall } from "firebase-functions/v2/https";
import {
  CALL_OPTS, FieldValue, HttpsError, ROLES, argId, assertMember, audit, db, loadProject, now, requireUser,
} from "./shared.js";

/** Invitee accepts the proposed terms: joins the project and Agreement vN becomes binding. Idempotent. */
export const acceptInvite = onCall(CALL_OPTS, async (req) => {
  const user = requireUser(req);
  const tokenId = argId(req.data?.token, "invite link");
  return db().runTransaction(async (tx) => {
    const invRef = db().doc(`invites/${tokenId}`);
    const inv = await tx.get(invRef);
    if (!inv.exists) throw new HttpsError("not-found", "This invite link isn’t valid. Ask for a new link.");
    const i = inv.data();
    if (i.status === "accepted") {
      if (i.acceptedBy === user.uid) return { projectId: i.projectId, already: true };
      throw new HttpsError("failed-precondition", "This invite was already accepted by someone else.");
    }
    if (i.status !== "pending") throw new HttpsError("failed-precondition", "This invite was revoked. Ask for a new link.");
    if (i.expiresAt < now()) throw new HttpsError("failed-precondition", "This invite has expired. Ask for a new link.");
    if (i.ownerId === user.uid) throw new HttpsError("failed-precondition", "This is your own invite — send the link to your freelancer.");

    const { ref: projRef, p } = await loadProject(i.projectId, tx);
    const version = p.agreementVersion;
    const agRef = projRef.collection("agreements").doc(`v${version}`);
    const ag = await tx.get(agRef);
    if (!ag.exists || ag.data().status !== "proposed") {
      throw new HttpsError("failed-precondition", "These terms changed since the invite was sent. Ask for a new link.");
    }
    const ms = await tx.get(projRef.collection("milestones"));
    const t = now();
    tx.update(invRef, { status: "accepted", acceptedBy: user.uid, acceptedAt: t });
    tx.update(agRef, { status: "accepted", acceptedBy: user.uid, acceptedByName: user.name, acceptedAt: t });
    tx.update(projRef, {
      memberIds: FieldValue.arrayUnion(user.uid),
      [`members.${user.uid}`]: { uid: user.uid, name: user.name, email: user.email, role: ROLES.ORIGINAL, status: "active", since: t },
      status: "active",
      agreementStatus: "accepted",
      updatedAt: t,
    });
    ms.docs
      .sort((a, b) => (a.data().index ?? 0) - (b.data().index ?? 0))
      .forEach((d, idx) => tx.update(d.ref, {
        assigneeId: user.uid, assigneeName: user.name, assigneeRole: ROLES.ORIGINAL,
        status: idx === 0 ? "in_progress" : "not_started",
      }));
    const emailNote = i.email && user.email && i.email.toLowerCase() !== user.email.toLowerCase()
      ? ` (invite was addressed to ${i.email})` : "";
    audit(tx, i.projectId, user, "agreement_accepted", agRef.id, `Version ${version} accepted${emailNote}`);
    return { projectId: i.projectId };
  });
});

async function freelancerContext(req, tx) {
  const user = requireUser(req);
  const pid = argId(req.data?.projectId, "project");
  const version = Number(req.data?.version);
  if (!Number.isInteger(version) || version < 1) throw new HttpsError("invalid-argument", "Missing agreement version.");
  const { ref, p } = await loadProject(pid, tx);
  assertMember(p, user.uid);
  if (p.members?.[user.uid]?.role !== ROLES.ORIGINAL) {
    throw new HttpsError("permission-denied", "Only the freelancer on this agreement can respond to a change.");
  }
  const agRef = ref.collection("agreements").doc(`v${version}`);
  const ag = await tx.get(agRef);
  if (!ag.exists) throw new HttpsError("not-found", "That agreement version doesn’t exist.");
  return { user, pid, version, ref, agRef, a: ag.data() };
}

/** Freelancer accepts a changed version (v2+). The previous version is superseded, never overwritten. */
export const acceptAgreementVersion = onCall(CALL_OPTS, async (req) =>
  db().runTransaction(async (tx) => {
    const { user, pid, version, ref, agRef, a } = await freelancerContext(req, tx);
    if (a.status === "accepted" && a.acceptedBy === user.uid) return { already: true };
    if (a.status !== "proposed") throw new HttpsError("failed-precondition", "This version is no longer open.");
    const all = await tx.get(ref.collection("agreements"));
    const ms = await tx.get(ref.collection("milestones"));
    const t = now();
    all.docs.forEach((d) => { if (d.id !== agRef.id && d.data().status === "accepted") tx.update(d.ref, { status: "superseded" }); });
    tx.update(agRef, { status: "accepted", acceptedBy: user.uid, acceptedByName: user.name, acceptedAt: t });
    const terms = new Map((a.terms ?? []).map((x) => [x.milestoneId, x]));
    ms.docs.forEach((d) => {
      const term = terms.get(d.id);
      if (term) tx.update(d.ref, { title: term.title, deliverable: term.deliverable, amountMinor: term.amountMinor, revisionLimit: term.revisionLimit });
    });
    tx.update(ref, { agreementStatus: "accepted", updatedAt: t });
    audit(tx, pid, user, "agreement_accepted", agRef.id, `Version ${version} accepted`);
    return { accepted: version };
  }));

/** Freelancer rejects a changed version — the version in force stays binding. */
export const rejectAgreementVersion = onCall(CALL_OPTS, async (req) =>
  db().runTransaction(async (tx) => {
    const { user, pid, version, ref, agRef, a } = await freelancerContext(req, tx);
    if (a.status !== "proposed") throw new HttpsError("failed-precondition", "This version is no longer open.");
    const t = now();
    tx.update(agRef, { status: "rejected", acceptedBy: null, rejectedBy: user.uid, rejectedAt: t });
    tx.update(ref, { agreementStatus: "accepted", updatedAt: t });
    audit(tx, pid, user, "agreement_change_rejected", agRef.id, `Version ${version} rejected — previous version stays in force`);
    return { rejected: version };
  }));
