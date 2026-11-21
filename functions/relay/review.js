// Client review of a submission (PRD AC-006 / AC-011). Only the project owner can approve, and
// approval is blocked without evidence ("Add the approved file").
import { onCall } from "firebase-functions/v2/https";
import {
  CALL_OPTS, HttpsError, argId, assertOwner, audit, db, isHttpUrl, loadProject, now, requireUser, text,
} from "./shared.js";

export const reviewSubmission = onCall(CALL_OPTS, async (req) => {
  const user = requireUser(req);
  const pid = argId(req.data?.projectId, "project");
  const sid = argId(req.data?.submissionId, "submission");
  const approve = req.data?.approve === true;
  const note = text(req.data?.note, "a note", { max: 2000, optional: approve });
  const approvedLink = typeof req.data?.approvedLink === "string" ? req.data.approvedLink.trim() : "";

  return db().runTransaction(async (tx) => {
    const { ref, p } = await loadProject(pid, tx);
    assertOwner(p, user.uid);
    const subRef = ref.collection("submissions").doc(sid);
    const sub = await tx.get(subRef);
    if (!sub.exists) throw new HttpsError("not-found", "That submission no longer exists.");
    const s = sub.data();
    if (s.status !== "submitted") {
      if (approve && s.status === "approved") return { status: "approved", already: true };
      throw new HttpsError("failed-precondition", "This submission was already reviewed.");
    }
    const msRef = ref.collection("milestones").doc(s.milestoneId);
    const ms = await tx.get(msRef);
    if (!ms.exists) throw new HttpsError("not-found", "That milestone no longer exists.");
    const m = ms.data();
    const handoffRef = s.handoffId ? db().doc(`handoffs/${s.handoffId}`) : null;
    const handoff = handoffRef ? await tx.get(handoffRef) : null;

    let links = (Array.isArray(s.links) ? s.links : []).filter((l) => isHttpUrl(l?.url));
    if (approve && approvedLink) {
      if (!isHttpUrl(approvedLink)) throw new HttpsError("invalid-argument", "Enter a valid link to the approved file.");
      links = [...links, { label: "Approved file", url: approvedLink }];
    }
    const t = now();
    if (approve) {
      if (links.length === 0) throw new HttpsError("failed-precondition", "Add the approved file before approving.");
      tx.update(subRef, { status: "approved", reviewedAt: t, reviewNote: note || null, links });
      tx.update(msRef, { status: "approved", approvedSubmissionId: sid, approvedLabel: s.label, approvedAt: t });
      const assetRef = ref.collection("assets").doc();
      tx.set(assetRef, {
        id: assetRef.id, label: s.label, url: links[0].url, status: "approved", milestoneId: s.milestoneId,
        addedBy: user.uid, addedByName: user.name, note: `Approved by ${user.name}`, createdAt: t,
      });
      if (handoff?.exists) {
        tx.update(handoffRef, { status: "approved", approvedAt: t });
        tx.set(handoffRef.collection("submissions").doc(sid), { status: "approved", reviewedAt: t, reviewNote: note || null, links }, { merge: true });
      }
      audit(tx, pid, user, "milestone_approved", s.milestoneId, `${s.label} approved.`);
      return { status: "approved" };
    }
    tx.update(subRef, { status: "revision_requested", reviewedAt: t, reviewNote: note });
    tx.update(msRef, { status: "revision_requested", revisionsUsed: (m.revisionsUsed ?? 0) + 1 });
    if (handoff?.exists) {
      tx.update(handoffRef, { status: "changes_requested" });
      tx.set(handoffRef.collection("submissions").doc(sid), { status: "revision_requested", reviewedAt: t, reviewNote: note }, { merge: true });
    }
    audit(tx, pid, user, "changes_requested", s.milestoneId, note);
    return { status: "revision_requested" };
  });
});
