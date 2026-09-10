# Plan: Payment gateway / financial integration (Stripe sandbox)

## Goal
Prove the fraud-scoring pipeline works against real payment events (not only the simulator), and
demonstrate secure webhook handling — which also covers the "replay attacks" item in the Advanced
Security list.

## Current state
- All transactions are simulator-generated or manually entered via "Create transaction"
  (`docs/README.md`'s "Managed rules and merchant transactions" section) — no external gateway.
- Existing ingestion path already has: idempotency via `Idempotency-Key` header equal to
  `eventId`, unique event ID constraint, canonical request-hash duplicate detection
  (`docs/ARCHITECTURE.md`).

## Steps

### 1. Stripe test-mode setup
- Create a Stripe sandbox account; store `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` as env
  vars (`.env`, following the existing pattern for `SMTP_*`/`DATABASE_PASSWORD` in `compose.yaml`)
  — never commit them, matching the existing `.env.example` convention.

### 2. Create payment intents from the app
- New optional flow alongside "Create transaction": when a user creates a transaction with a
  `usePaymentGateway` flag, call Stripe's `PaymentIntents` API server-side (`FraudService.java` or
  a new `PaymentGatewayService.java`) to create a real (sandbox) payment intent, then proceed with
  the existing ingestion pipeline unchanged (still gets an `eventId`/idempotency key, still goes
  through `RiskWorker.java` scoring).

### 3. Webhook endpoint
- New `POST /api/webhooks/stripe` in `ApiController.java` (or a dedicated
  `WebhookController.java`), **excluded from CSRF** (webhooks can't carry a CSRF token — mirror
  how `docs/ARCHITECTURE.md` already documents CSRF being required "on mutations, including
  login/logout" so this is a deliberate, documented exception) but protected instead by Stripe
  signature verification.
- Verify `Stripe-Signature` header against `STRIPE_WEBHOOK_SECRET` using Stripe's SDK before
  processing anything — this is the replay-attack mitigation (Stripe signs with a timestamp;
  reject signatures older than a small tolerance window, e.g. 5 minutes).
- On `payment_intent.succeeded` / `payment_intent.payment_failed`: map to the existing transaction
  event shape and feed it into the same ingestion path used by the simulator/manual entry, so
  `payment_intent.id` becomes the `eventId`/idempotency key — duplicate webhook deliveries (Stripe
  retries on non-2xx) are naturally deduped by the existing unique-event-ID constraint.

### 4. Transaction state management
- Add a `source` column (`SIMULATED`, `MANUAL`, `GATEWAY`) to the events table via a small Flyway
  migration, so the UI and audit trail can distinguish real gateway events from demo data — judges
  should be able to tell these apart at a glance.
- Map Stripe intent states (`requires_payment_method`, `processing`, `succeeded`, `canceled`) onto
  the existing transaction status model; don't invent a parallel state machine.

### 5. Frontend
- Add a "Live payment (sandbox)" panel near the existing Simulator screen
  (`frontend/src/App.tsx`) using Stripe.js/Elements test card numbers, so the demo can show a real
  card-entry flow that ends up scored by the fraud engine.

## Testing
- Use Stripe's documented test card numbers (success, decline, fraud-flagged) to generate real
  webhook events in sandbox; confirm each produces a correctly scored transaction with the right
  `source=GATEWAY` tag.
- Signature tampering test: send a webhook payload with an invalid/stale signature, confirm 400
  rejection and that nothing is ingested.
- Replay test: resend a valid webhook payload twice, confirm only one transaction/alert exists
  (idempotency already covers this — this test proves it holds for the new source too).
- Stripe CLI (`stripe listen --forward-to localhost:8088/api/webhooks/stripe`) for local
  end-to-end testing before deploying.

## Risk / rollback
- Keep the CSRF exclusion scoped to exactly the webhook path (never broaden the exclusion) —
  document this explicitly in `docs/ARCHITECTURE.md`'s security section as a reviewed exception.
- Sandbox-only keys; never place live Stripe keys in this repo or demo environment.
- Feature is additive — simulator and manual entry paths are untouched, so this can be developed
  and demoed independently of the rest.
