// Handoffs (PRD §14 Journey C/D/E/F, FR-011…FR-018, IA F5/F6).
// The brief is assembled HERE from the client's selections, so excluded content is never copied into
// the handoff document — the only thing a replacement can read (see firestore.rules → /handoffs).
import { onCall } from "firebase-functions/v2/https";
import {
  CALL_OPTS, HttpsError, ROLES, SEVEN_DAYS, argId, assertOwner, audit, db, isHttpUrl, loadProject, now,
  paise, requireUser, rupees, text, token,
} from "./shared.js";

const LIVE = ["published", "counteroffer"];
const OPEN_FOR_WORK = ["accepted", "changes_requested"];

function requiredDeliverables(milestones) {
  const out = [];
  for (const m of milestones) {
    const sizes = [...String(m.deliverable ?? "").matchAll(/(\d{3,4})\s?px/gi)].map((x) => `${x[1]}px frame`);
    if (sizes.length) out.push(...sizes); else out.push(m.deliverable || m.title);
  }
  out.push("Completion note");
  return [...new Set(out)];
}

const briefMilestone = (id, m) => ({ milestoneId: id, title: m.title, deliverable: m.deliverable, amountMinor: m.amountMinor, revisionLimit: m.revisionLimit ?? 2 });

/** Client publishes a reviewed brief for the remaining scope. Returns the share token. */
export const publishHandoff = onCall(CALL_OPTS, async (req) => {
  const user = requireUser(req);
  const pid = argId(req.data?.projectId, "project");
  const milestoneIds = Array.isArray(req.data?.milestoneIds) ? req.data.milestoneIds.filter((x) => typeof x === "string") : [];
  if (milestoneIds.length === 0) throw new HttpsError("failed-precondition", "Define remaining work — select at least one milestone.");
  const feeMinor = paise(req.data?.feeMinor, "fee for the replacement");
  const include = new Set(Array.isArray(req.data?.includeSections) ? req.data.includeSections : ["goal", "done", "decisions", "scope", "files"]);
  const excludedAssets = new Set(Array.isArray(req.data?.excludedAssetIds) ? req.data.excludedAssetIds : []);
  const excludedDecisions = new Set(Array.isArray(req.data?.excludedDecisionIds) ? req.data.excludedDecisionIds : []);
  const sendTo = text(req.data?.sendTo, "email", { max: 200, optional: true });

  const { ref, p } = await loadProject(pid);
  assertOwner(p, user.uid);
  if (p.activeHandoffId) {
    const prev = await db().doc(`handoffs/${p.activeHandoffId}`).get();
    if (prev.exists && !["revoked", "declined", "completed"].includes(prev.data().status)) {
      throw new HttpsError("failed-precondition", "A handoff for this project is already open. Revoke it before creating another.");
    }
  }
  const [msSnap, decSnap, assetSnap, subSnap] = await Promise.all([
    ref.collection("milestones").get(), ref.collection("decisions").get(),
    ref.collection("assets").get(), ref.collection("submissions").get(),
  ]);
  const ms = new Map(msSnap.docs.map((d) => [d.id, d.data()]));
  for (const id of milestoneIds) {
    const m = ms.get(id);
    if (!m) throw new HttpsError("not-found", "One of the selected milestones no longer exists.");
    if (m.status === "approved") throw new HttpsError("failed-precondition", `${m.title} is already approved — it can’t be handed off.`);
  }
  const subs = new Map(subSnap.docs.map((d) => [d.id, d.data()]));
  const assets = assetSnap.docs.map((d) => ({ id: d.id, ...d.data() }));

  const includedFiles = include.has("files") ? assets.filter((a) => !excludedAssets.has(a.id)) : [];
  const blocked = includedFiles.find((a) => a.status === "no_access");
  if (blocked) throw new HttpsError("failed-precondition", `Request access to this file: ${blocked.label}. Or exclude it from the brief.`);

  const approved = [...ms.entries()].filter(([, m]) => m.status === "approved");
  const brief = {
    projectName: p.name,
    goal: include.has("goal") ? p.goal : "",
    clientName: p.ownerName,
    approvedWork: include.has("done") ? approved.map(([, m]) => {
      const sub = m.approvedSubmissionId ? subs.get(m.approvedSubmissionId) : null;
      const url = sub?.links?.find((l) => isHttpUrl(l.url))?.url ?? "";
      return { label: `${m.approvedLabel ?? m.title}`, url, status: "approved", note: `${m.title} · approved by ${p.ownerName}` };
    }) : [],
    work: milestoneIds.map((id) => briefMilestone(id, ms.get(id))),
    requiredDeliverables: requiredDeliverables(milestoneIds.map((id) => ms.get(id))),
    decisions: include.has("decisions") ? decSnap.docs.map((d) => ({ id: d.id, ...d.data() }))
      .filter((x) => x.status === "confirmed" && !excludedDecisions.has(x.id))
      .map((x) => ({ title: x.title, summary: x.summary ?? "", sourceQuote: x.sourceQuote ?? "", sourceAuthor: x.sourceAuthor ?? "", sourceAt: x.sourceAt ?? 0 })) : [],
    notInScope: include.has("scope") ? [...ms.entries()].filter(([id, m]) => !milestoneIds.includes(id) && m.status !== "approved").map(([id, m]) => briefMilestone(id, m)) : [],
    files: includedFiles.filter((a) => a.status !== "approved" || !approved.some(([, m]) => m.approvedLabel === a.label))
      .map((a) => ({ label: a.label, url: a.url, status: a.status, note: a.note ?? "" })),
    earlierPayments: approved.map(([, m]) =>
      m.paymentStatus === "verified_test" ? `${m.title} · Paid (verified) · ${rupees(m.paidVerifiedMinor ?? m.amountMinor)}`
        : m.paymentStatus === "recorded_externally" ? `${m.title} · Paid outside Relay · ${rupees(m.paidExternallyMinor ?? m.amountMinor)}`
          : `${m.title} · approved, not yet paid`),
  };
  const excluded = [
    ...assets.filter((a) => excludedAssets.has(a.id)).map((a) => `File: ${a.label}`),
    ...decSnap.docs.filter((d) => excludedDecisions.has(d.id)).map((d) => `Decision: ${d.data().title}`),
    "Full conversation history",
  ];

  const t = now();
  const id = token();
  const handoffRef = db().doc(`handoffs/${id}`);
  const batch = db().batch();
  batch.set(handoffRef, {
    id, projectId: pid, ownerId: user.uid, ownerName: p.ownerName ?? user.name, status: "published",
    milestoneIds, brief, excluded, feeMinor,
    proposals: [{ version: 1, amountMinor: feeMinor, by: user.uid, byName: user.name, byRole: ROLES.OWNER, reason: "", at: t, status: "offered" }],
    sentTo: sendTo || null, createdAt: t, publishedAt: t, expiresAt: t + SEVEN_DAYS,
  });
  batch.update(ref, { status: "handoff", activeHandoffId: id, updatedAt: t });
  for (const mid of milestoneIds) {
    batch.update(ref.collection("milestones").doc(mid), { status: "reassigned", handoffId: id, assigneeId: null, assigneeName: null, assigneeRole: null });
  }
  audit(batch, pid, user, "handoff_published", id, `${brief.work.map((w) => w.title).join(", ")} · ${rupees(feeMinor)} · ${excluded.length} items excluded`);
  await batch.commit();
  return { token: id };
});

function currentOffer(h) {
  const list = h.proposals ?? [];
  return [...list].reverse().find((x) => x.status === "offered" || x.status === "accepted") ?? null;
}
const pendingProposal = (h) => (h.proposals ?? []).find((x) => x.status === "proposed") ?? null;
function setLatest(h, predicate, status) {
  const list = (h.proposals ?? []).map((x) => ({ ...x }));
  for (let i = list.length - 1; i >= 0; i--) if (predicate(list[i])) { list[i].status = status; break; }
  return list;
}

/** Replacement and client actions on a handoff. */
export const handoffAction = onCall(CALL_OPTS, async (req) => {
  const id = argId(req.data?.token, "handoff link");
  const action = String(req.data?.action ?? "");
  const ownerActions = ["acceptProposal", "rejectProposal", "revoke", "reissue"];
  const replacementActions = ["accept", "propose", "withdraw", "decline"];
  if (action !== "open" && !ownerActions.includes(action) && !replacementActions.includes(action)) {
    throw new HttpsError("invalid-argument", "Unknown action.");
  }
  const ref = db().doc(`handoffs/${id}`);

  if (action === "open") {
    const snap = await ref.get();
    if (!snap.exists) throw new HttpsError("not-found", "This handoff link isn’t valid.");
    const h = snap.data();
    if (LIVE.includes(h.status) && h.expiresAt > now() && !h.openedAt) await ref.update({ openedAt: now() });
    return { status: h.status };
  }

  const user = requireUser(req);
  return db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("not-found", "This handoff link isn’t valid.");
    const h = snap.data();
    const projRef = db().doc(`projects/${h.projectId}`);
    const t = now();

    if (ownerActions.includes(action)) {
      if (h.ownerId !== user.uid) throw new HttpsError("permission-denied", "Only the client can do that.");
      const proj = await tx.get(projRef);
      const msRefs = (h.milestoneIds ?? []).map((mid) => projRef.collection("milestones").doc(mid));
      if (action === "acceptProposal" || action === "rejectProposal") {
        const pend = pendingProposal(h);
        if (h.status !== "counteroffer" || !pend) throw new HttpsError("failed-precondition", "There’s no proposal waiting for you.");
        if (action === "acceptProposal") {
          tx.update(ref, { status: "published", feeMinor: pend.amountMinor, proposals: setLatest(h, (x) => x.status === "proposed", "accepted") });
          audit(tx, h.projectId, user, "fee_proposal_accepted", id, `${rupees(pend.amountMinor)} accepted (v${pend.version}); ${rupees(currentOffer(h)?.amountMinor ?? h.feeMinor)} offer kept in history`);
        } else {
          tx.update(ref, { status: "published", proposals: setLatest(h, (x) => x.status === "proposed", "rejected") });
          audit(tx, h.projectId, user, "fee_proposal_rejected", id, `${rupees(pend.amountMinor)} rejected; ${rupees(currentOffer(h)?.amountMinor ?? h.feeMinor)} still offered`);
        }
        return { status: "published" };
      }
      if (action === "revoke") {
        if (!LIVE.includes(h.status)) throw new HttpsError("failed-precondition", "Only a handoff that hasn’t been accepted can be revoked.");
        tx.update(ref, { status: "revoked", revokedAt: t });
        if (proj.exists && proj.data().activeHandoffId === id) tx.update(projRef, { status: "interrupted", activeHandoffId: null, updatedAt: t });
        msRefs.forEach((r) => tx.update(r, { status: "interrupted", handoffId: null }));
        audit(tx, h.projectId, user, "handoff_revoked", id, "Link revoked — it no longer opens");
        return { status: "revoked" };
      }
      // reissue: a fresh unguessable link with the same reviewed brief; the old link stays dead.
      if (!["revoked", "declined"].includes(h.status) && h.expiresAt > t) throw new HttpsError("failed-precondition", "This link is still live — no need to reissue it.");
      if (proj.exists && proj.data().activeHandoffId && proj.data().activeHandoffId !== id) {
        const other = await tx.get(db().doc(`handoffs/${proj.data().activeHandoffId}`));
        if (other.exists && !["revoked", "declined", "completed"].includes(other.data().status)) throw new HttpsError("failed-precondition", "Another handoff is already open.");
      }
      const newId = token();
      const fee = currentOffer(h)?.amountMinor ?? h.feeMinor;
      tx.set(db().doc(`handoffs/${newId}`), {
        id: newId, projectId: h.projectId, ownerId: h.ownerId, ownerName: h.ownerName, status: "published",
        milestoneIds: h.milestoneIds, brief: h.brief, excluded: h.excluded ?? [], feeMinor: fee,
        proposals: [{ version: 1, amountMinor: fee, by: user.uid, byName: user.name, byRole: ROLES.OWNER, reason: "", at: t, status: "offered" }],
        sentTo: null, createdAt: t, publishedAt: t, expiresAt: t + SEVEN_DAYS, reissuedFrom: id,
      });
      if (h.status !== "revoked" && h.status !== "declined") tx.update(ref, { status: "revoked", revokedAt: t });
      tx.update(projRef, { status: "handoff", activeHandoffId: newId, updatedAt: t });
      msRefs.forEach((r) => tx.update(r, { status: "reassigned", handoffId: newId }));
      audit(tx, h.projectId, user, "handoff_reissued", newId, "New private link issued");
      return { token: newId };
    }

    // Replacement actions
    if (h.ownerId === user.uid) throw new HttpsError("failed-precondition", "This is your own handoff — share the link with the replacement.");
    if (h.replacementUid && h.replacementUid !== user.uid) throw new HttpsError("permission-denied", "This handoff was already taken by someone else.");
    if (action === "accept" && h.status === "accepted" && h.replacementUid === user.uid) return { status: "accepted", already: true };
    if (!LIVE.includes(h.status)) {
      const msg = h.status === "revoked" ? "This handoff link has been revoked." : h.status === "declined" ? "This handoff was declined." : "This handoff is no longer open.";
      throw new HttpsError("failed-precondition", msg);
    }
    if (h.expiresAt < t) throw new HttpsError("failed-precondition", "This handoff link has expired. Ask the client to reissue it.");
    const who = { replacementUid: user.uid, replacementName: user.name, replacementEmail: user.email };

    if (action === "accept") {
      if (pendingProposal(h)) throw new HttpsError("failed-precondition", "Review latest proposal — a fee proposal is still waiting for the client.");
      const offer = currentOffer(h);
      if (!offer) throw new HttpsError("failed-precondition", "There’s no fee on the table yet.");
      const proj = await tx.get(projRef);
      const msRefs = (h.milestoneIds ?? []).map((mid) => projRef.collection("milestones").doc(mid));
      tx.update(ref, {
        ...who, status: "accepted", acceptedAt: t, agreedFeeMinor: offer.amountMinor,
        proposals: setLatest(h, (x) => x.version === offer.version, "accepted"),
      });
      msRefs.forEach((r) => tx.update(r, {
        assigneeId: user.uid, assigneeName: user.name, assigneeRole: ROLES.REPLACEMENT,
        status: "in_progress", handoffId: id, replacementFeeMinor: offer.amountMinor, revisionsUsed: 0,
      }));
      if (proj.exists) tx.update(projRef, { status: "active", updatedAt: t });
      audit(tx, h.projectId, user, "handoff_accepted", id, `${user.name} accepted ${rupees(offer.amountMinor)} (terms v${offer.version})`);
      return { status: "accepted", agreedFeeMinor: offer.amountMinor };
    }
    if (action === "propose") {
      const amount = paise(req.data?.amountMinor, "fee");
      if (pendingProposal(h)) throw new HttpsError("failed-precondition", "Review latest proposal — only one proposal can be open at a time.");
      const reason = text(req.data?.reason, "a reason", { max: 500, optional: true });
      const version = (h.proposals ?? []).reduce((mx, x) => Math.max(mx, x.version ?? 0), 0) + 1;
      tx.update(ref, {
        ...who, status: "counteroffer",
        proposals: [...(h.proposals ?? []), { version, amountMinor: amount, by: user.uid, byName: user.name, byRole: ROLES.REPLACEMENT, reason, at: t, status: "proposed" }],
      });
      audit(tx, h.projectId, user, "fee_proposed", id, `${user.name} proposed ${rupees(amount)} (v${version})`);
      return { status: "counteroffer" };
    }
    if (action === "withdraw") {
      if (h.status !== "counteroffer" || h.replacementUid !== user.uid) throw new HttpsError("failed-precondition", "There’s no proposal of yours to withdraw.");
      tx.update(ref, { status: "published", proposals: setLatest(h, (x) => x.status === "proposed", "withdrawn") });
      audit(tx, h.projectId, user, "fee_proposal_withdrawn", id, `${user.name} withdrew the proposal`);
      return { status: "published" };
    }
    // decline
    const reason = text(req.data?.reason, "a reason", { max: 500, optional: true });
    const proj = await tx.get(projRef);
    const msRefs = (h.milestoneIds ?? []).map((mid) => projRef.collection("milestones").doc(mid));
    tx.update(ref, { ...who, status: "declined", declinedAt: t, declineReason: reason || null });
    if (proj.exists && proj.data().activeHandoffId === id) tx.update(projRef, { status: "interrupted", activeHandoffId: null, updatedAt: t });
    msRefs.forEach((r) => tx.update(r, { status: "interrupted" }));
    audit(tx, h.projectId, user, "handoff_declined", id, `${user.name} declined${reason ? `: ${reason}` : ""}`);
    return { status: "declined" };
  });
});

/** Replacement submits work against the brief's required deliverables (PRD AC-010, Journey E). */
export const submitHandoffWork = onCall(CALL_OPTS, async (req) => {
  const user = requireUser(req);
  const id = argId(req.data?.token, "handoff link");
  const note = text(req.data?.note, "a completion note", { max: 2000, optional: true });
  const links = (Array.isArray(req.data?.links) ? req.data.links : [])
    .map((l) => ({ label: String(l?.label ?? "").trim().slice(0, 80), url: String(l?.url ?? "").trim() }))
    .filter((l) => l.url);
  const bad = links.find((l) => !isHttpUrl(l.url));
  if (bad) throw new HttpsError("invalid-argument", `“${bad.label || bad.url}” isn’t a valid link.`);

  return db().runTransaction(async (tx) => {
    const ref = db().doc(`handoffs/${id}`);
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("not-found", "This handoff link isn’t valid.");
    const h = snap.data();
    if (h.replacementUid !== user.uid) throw new HttpsError("permission-denied", "Only the person who accepted this handoff can submit work.");
    if (!OPEN_FOR_WORK.includes(h.status)) {
      throw new HttpsError("failed-precondition", h.status === "submitted" ? "Your submission is already in review." : "This handoff isn’t open for submissions.");
    }
    const required = h.brief?.requiredDeliverables ?? [];
    for (const r of required) {
      if (r === "Completion note") { if (!note) throw new HttpsError("failed-precondition", "Add a completion note."); continue; }
      const size = r.match(/(\d{3,4})px/)?.[1];
      const ok = size ? links.some((l) => l.label.includes(size)) : links.length > 0;
      if (!ok) throw new HttpsError("failed-precondition", `Add ${r}.`);
    }
    const mid = (h.milestoneIds ?? [])[0];
    const projRef = db().doc(`projects/${h.projectId}`);
    const prior = await tx.get(projRef.collection("submissions").where("handoffId", "==", id));
    const t = now();
    const subRef = projRef.collection("submissions").doc();
    const title = (h.brief?.work ?? []).map((w) => w.title).join(", ") || "Handoff work";
    const sub = {
      id: subRef.id, milestoneId: mid, label: `${title} · v${prior.size + 1}`, submittedBy: user.uid, submittedByName: user.name,
      submittedByRole: ROLES.REPLACEMENT, links, note, status: "submitted", submittedAt: t, handoffId: id,
    };
    tx.set(subRef, sub);
    tx.set(ref.collection("submissions").doc(subRef.id), sub);
    (h.milestoneIds ?? []).forEach((m) => tx.update(projRef.collection("milestones").doc(m), { status: "submitted" }));
    tx.update(ref, { status: "submitted", lastSubmittedAt: t });
    audit(tx, h.projectId, user, "submission_created", subRef.id, `${sub.label} submitted by ${user.name}`);
    return { submissionId: subRef.id };
  });
});
