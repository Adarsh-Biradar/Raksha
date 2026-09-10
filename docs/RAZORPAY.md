# Razorpay Test Mode payments

Raksha now supports a provider-backed sandbox payment after risk assessment. Live API keys are deliberately rejected. Without test credentials, the UI displays setup required and checkout stays disabled. No paid provider account or real-money transaction is required to exercise the local fake-gateway test suite.

## Setup

1. Obtain Test Mode API keys in Razorpay Dashboard.
2. Configure automatic payment capture in Test Mode. An authorized payment is not a captured payment; Raksha displays these separately.
3. Create a Test Mode webhook pointing to `https://api-raksha.rayududev.live/api/payments/webhook`. Subscribe to `payment.authorized`, `payment.captured`, and `payment.failed`. Choose a strong webhook secret and use exactly the same value on the server.
4. In local `.env`, set `RAZORPAY_KEY_ID=rzp_test_...`, `RAZORPAY_KEY_SECRET=...`, and `RAZORPAY_WEBHOOK_SECRET=...`. Recreate the API container after configuration. Do not put secrets in React, source control or screenshots.
5. For Ubuntu Kubernetes, deploy the updated API and web manifests/images, then run `bash k8s/setup-payments.sh default` from the project folder. Replace default with your actual context when different. This creates/updates a dedicated `raksha-payments` Secret and restarts the API. Existing database, login and SMTP secrets are unaffected. Both API manifests reference these optional keys; never store gateway credentials in the general ConfigMap.

## Demonstration

Create a transaction with a new customer account, INR 100, country IN, a device ID, no blocked signals and no failed attempts. Wait for scoring, then choose Pay in test mode. Complete Checkout using Razorpay's documented test instruments. The backend verifies the checkout signature and fetches payment details; captured payments appear in Payments history. Never use real card details in the demo.

For a fraud scenario, first configure a rule that produces a 100-point score. The triggering transaction cannot open checkout. Its customer account is held, so subsequent attempts are blocked. Resolve its investigation before creating a fresh payment transaction.

Closing Checkout is not proof of failure. Use Reconcile to fetch the provider's payment attempts if a callback or webhook is delayed. Reconcile and webhook processing use the same transition logic. The Payments page lets operators reopen an existing order instead of creating another transaction to retry.

## State and consistency

Risk status remains in transactions; payment state is stored separately in gateway_orders and gateway_attempts. Order states: CREATING, UNKNOWN, CREATED, AUTHORIZED, CAPTURED, FAILED. An order may contain several payment attempts. A failed attempt can authorize later; authorization never regresses to failed, and captured never regresses. Refunds and disputes are outside this integration's supported states.

The backend checks stored amount/currency and risk before order creation, and checks holds again before returning checkout details. It commits a unique creation intent per transaction BEFORE making the provider request. Concurrent/repeated checkout requests reuse that intent. An uncertain response is marked UNKNOWN (a crash can leave CREATING); the app never blindly issues another create-order request. Reconcile searches by the exact unique receipt and links only one matching provider order. If no unique match exists, operator investigation in Razorpay Dashboard is required. Recovery does not automatically create a replacement order.

Webhook verification uses HMAC-SHA256 on the original raw request bytes and constant-time signature comparison. The event ID plus payload hash prevents duplicate or conflicting deliveries. Only the exact webhook POST route bypasses session/CSRF checks; other payment mutations require admin/analyst session and CSRF. Amounts, currency, IDs and order association are checked before state changes. The service acknowledges only after durable event storage and processes the queue every two seconds. Unlinked orders retry for approximately one hour, then become IGNORED; mismatches become REJECTED. Reconcile remains available to recover authoritative payment state. Database errors leave work pending for a later poll. Event storage retains only payment identifiers, state and amount/currency, not full webhook/card/contact payloads.

Payment callbacks and webhooks update the same transaction's payment record; they do not create new risk transactions, investigations or duplicate fraud emails. Transitions are audited. Payment history exposes the latest 100 orders; risk transaction history and investigations retain their existing workflows.

A hold placed AFTER Checkout was already issued cannot revoke a Razorpay order or undo a captured payment. Raksha records later provider events accurately even when the account is held. Stronger real-money prevention would require controlled authorization/capture and a broader settlement/refund workflow. This demo does not claim to provide that. Merchant names are labels: all checkout orders belong to the one configured test merchant account; this is not marketplace onboarding or split settlement.

## Tests and deployment checks

Run a clean Java package build (to avoid stale copied migrations) and build React, then run `scripts/test-payments.ps1` on the development Windows machine. It extracts dependencies from the packaged jar, compiles GatewayPaymentCheck.java, creates a randomly named isolated schema in local PostgreSQL on port 54329, runs fake-provider integration tests and drops only that test schema afterward. It never sends real requests to Razorpay or emails.

Covered: migrations, missing/live keys, pending/100-point/held transactions, one order under concurrent requests, checkout HMAC, webhook HMAC, duplicate event IDs, conflicting payloads, amount mismatch, captured-state regression, recovery by receipt after an uncertain create response, authorization versus capture and reconciliation.

Deployment preflight: migration history must match the source. The existing local database currently records V4 as rule_lab, whereas the current repository has V4 outbox and V6 rule_lab. This pre-existing numbering conflict must be reconciled against the actual deployed migration history before upgrading that database. Do not run Flyway repair or disable validation to conceal it. V7 is the new payment migration. Fresh databases pass all seven migrations. The existing demo database was not rewritten.

## API

- GET /api/payments/config: test mode and configuration-present boolean (no secrets).
- GET /api/payments: latest 100 orders.
- GET /api/payments/{transactionId}: risk, order, hold flag and payment attempts.
- POST /api/payments/{transactionId}/checkout: risk-gated order creation/reuse, returns public test key and checkout details.
- POST /api/payments/{transactionId}/confirm: `{paymentId,signature}`; verifies using the SERVER-stored order ID, then fetches the payment.
- POST /api/payments/{transactionId}/reconcile: recover uncertain creation or fetch payment attempts.
- POST /api/payments/webhook: signed raw provider event with X-Razorpay-Signature and x-razorpay-event-id headers.

References: https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/ ; https://razorpay.com/docs/webhooks/validate-test/ ; https://razorpay.com/docs/api/orders/fetch-all/

The isolated preview is available at http://localhost:8090/ using a separate database. The original localhost:8088 stack remains unchanged. Stop the preview with: docker compose --project-name raksha-payment-check --env-file .env -f tmp/payments-check/compose.yaml down


Mobile numbers are now required on the transaction entry API and form, with country code (for example +919876543210). Checkout receives the normalized saved number and marks its contact field read-only. This prefill is not phone-ownership verification. Historical or simulator records without a phone remain readable but cannot start checkout; create a new transaction with a mobile number.
