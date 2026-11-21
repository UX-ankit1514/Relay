# Relay

Relay keeps a freelance project going when the freelancer drops out. Agreements, decisions, approvals and payments stay on record. The client can hand the remaining work to a replacement through a reviewed brief, then pay through Razorpay (test mode).

Built for the Razorpay Buildathon. The stack:

- **Kotlin Multiplatform + Compose Multiplatform**: a web (Kotlin/JS) app, with UI built from the Steep design system in Figma.
- **Firebase**: Authentication (Google sign-in only), Cloud Firestore (with security rules) and Cloud Functions (Node 22).
- **Razorpay Checkout in test mode**: orders are created on the server, and signatures and amounts are verified on the server.
- **Claude (Anthropic API)**: called from Cloud Functions to suggest decisions from the conversation. AI only suggests; the client confirms.

## Run the demo locally

Requires JDK 17+, Node 22, and the Firebase CLI.

```bash
# 1. Install function dependencies (first time only)
cd functions && npm install && cd ..

# 2. Start the Firebase emulators (Auth, Firestore, Functions; UI at :4000)
firebase emulators:start --only auth,firestore,functions --project relay-4ef17

# 3. In a second terminal, run the web app (http://localhost:4173)
./gradlew :composeApp:jsBrowserDevelopmentRun --continuous
```

### Local configuration (never committed)

- **`local.properties`**:
  - Firebase web config (`firebase.apiKey`, `firebase.authDomain`, `firebase.projectId`, …)
  - `razorpay.keyId=rzp_test_…`
  - `relay.useEmulators=true`
- **`functions/.env`**: `RAZORPAY_KEY_ID`. See `functions/.env.example`.
- **`functions/.secret.local`**: `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and optionally `ANTHROPIC_API_KEY`. Without the Anthropic key, AI suggestions show as unavailable and decisions can be added manually.

In production these secrets live in Google Secret Manager. Set them with `firebase functions:secrets:set NAME`. They are never sent to the browser.

### Demo mode

With `relay.useEmulators=true`, the sign-in screens show **Priya / Rohan / Maya** buttons. These are fictional Google accounts that exist only in the local Auth emulator, so the whole journey runs on one machine without real Google accounts. In production, only "Continue with Google" is shown.

## The 3-minute demo journey

1. **Priya (client)**
   1. Landing → Get started → Priya.
   2. Create the project "ClearInvoice landing page" with 3 milestones (₹7,000 / ₹6,000 / ₹2,000).
   3. Create the invite → Copy invite link.
2. **Rohan (freelancer)**
   1. Sign out, then open the invite link → Rohan → **Accept terms**. Agreement v1 is saved with a timestamp.
   2. Post in Conversation.
   3. Open milestone → paste a Figma link → **Submit for review**.
3. **Priya**
   1. Review → **Approve**.
   2. Record the Desktop payment as *paid outside Relay*. It shows as "recorded", never "verified".
   3. Decisions → add and confirm "Keep Book a demo near the top".
4. **Rohan drops out**
   1. Priya goes to Handoff → **Mark as interrupted**.
   2. Remaining work = Mobile design ₹6,000.
   3. **Publish handoff** → copy the private link. Private items stay excluded.
5. **Maya (replacement)**
   1. Opens the link and sees only the reviewed brief.
   2. Terms → **Accept ₹6,000**. She can propose a different fee instead.
   3. My work → 360px + 390px Figma links + note → **Submit**.
6. **Priya**
   1. Review Mobile design v1 → **Approve** → **Pay ₹6,000**.
   2. Razorpay Test Checkout opens. Use contact `9000090000`, then Netbanking → any bank → **Success**.
   3. Relay marks the milestone **Paid (verified)** only after the server verifies the signature and amount.

## Project layout

| Path | What's there |
|---|---|
| `composeApp/src/commonMain` | Shared Kotlin: models, repository, navigation, design system, screens |
| `composeApp/src/jsMain` | Firebase JS bindings, browser router, Razorpay Checkout bridge |
| `functions/relay` | Agreements, handoffs, review and decision extraction (Claude) |
| `functions/index.js`, `functions/razorpay.js` | Razorpay order creation, verification and webhook |
| `firestore.rules` | Members-only access. Server-only writes for acceptance, approval and verified payments |

## Deploying

Live (Firebase Hosting): **https://relay-4ef17.web.app**

```bash
# Production build: RELAY_USE_EMULATORS=false overrides relay.useEmulators=true in local.properties
RELAY_USE_EMULATORS=false ./gradlew :composeApp:jsBrowserDistribution
firebase deploy --only hosting,firestore            # works on the free Spark plan
firebase deploy --only functions                    # needs the Blaze plan + secrets in Secret Manager
```

Deploying needs three things:

- The Firebase project must be on the **Blaze** plan, because Cloud Functions require it.
- The deploying account needs access to `relay-4ef17`.
- The secrets must be set in Secret Manager.
