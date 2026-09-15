# Raksha Postman APIs

Import both JSON files into Postman, select **Raksha Live**, and set its **password** to the real demo password for the live deployment (never commit it — this export intentionally ships with an empty password value). `baseUrl` already points at the live API (`https://api-raksha.rayududev.live`); `webUrl` is the live frontend for reference. To point at a local Docker Compose stack instead, just edit `baseUrl` to `http://localhost:8088` in the environment.

## Quick start
1. `baseUrl` defaults to the live deployment; no server setup needed to start reading data.
2. Send **01 Authentication / Login**.
3. Send **Current user** to confirm your role.
4. Open **02 Transactions / Create transaction — edit amount and merchant**.
5. Edit amountMinor, merchant, accountId, deviceId, country or failedAttempts in the JSON body, then Send.
6. Open **Transaction details and assessment**. The transaction ID is captured automatically; resend after a few seconds if still PENDING.

Amounts use integer paise: 125000 = INR 1,250; 5000000 = INR 50,000. Only INR is supported. Event/account/device IDs allow letters, digits, underscores and hyphens. Events must be within the previous year and no more than five minutes in the future.

A new event ID and current UTC timestamp are generated each time the Create request is sent. Use **Replay last transaction** immediately after Create to verify duplicate handling; it preserves the exact timestamp and payload. The conflicting replay deliberately returns 409. After running simulations transactionId changes, so create again before the replay demonstration.

## Investigation demo
Send **Simulate TAKEOVER**, wait a few seconds for background scoring, then:
- List alerts — select current transaction
- Claim selected case
- Add investigation note
- List investigation notes
- Resolve selected case

The alert selector only matches the currently saved transactionId. It never silently picks a different case. Alert versions are captured after reads and successful updates. A 409 can indicate a stale version, another analyst's ownership, or an already-resolved case.

## Authentication
Leave Postman's cookie jar enabled and keep the same hostname in baseUrl throughout a session. Authentication uses a JSESSIONID cookie, not a Bearer token. The collection-level pre-request script uses pm.sendRequest to fetch CSRF data before every POST/PUT. It then sets the returned CSRF header on the outgoing request, including Login and Logout. Session rotation after login is handled by fetching a new token for each write.

Built-in demo emails: admin@fraudshield.demo, analyst@fraudshield.demo, viewer@fraudshield.demo. They use the seeded demo password. Changing .env after initial database seeding does not reset existing user passwords.

## Rules and recovery
Only admins can read/change rules, read audit events, or retry dead jobs. Read current risk policy captures all current values before an update. Change the desired body fields or collection variables explicitly. Updates increment the policy version and affect future assessments only.

Retry requires an actual dead job. If List dead jobs returns [], there is nothing to retry. Do not run the entire collection blindly: it includes optional policy changes, case resolution, and a retry request requiring an existing failure. Use individual requests or select the desired workflow folders.

## Coverage
Includes every implemented /api route: CSRF, login/logout/current user, transaction creation/list/detail, AI narrative per transaction, dashboard, all four simulations, alerts, claim/resolve actions, notes, risk policy, detection rule catalog, Rule Lab, audit, dead jobs and retry, Razorpay sandbox payments (config/list/status/checkout/reconcile/confirm), email/SMS notification settings and delivery history, organization info/rename, usage-based billing (usage/plan), and AI evaluation (run/history). /actuator/health is internal to the cluster network and is not included as a public request.

## Payments
Folder **09 Payments**: `Payment status`/`Checkout`/`Reconcile` need a transaction that is already `SCORED`, not blocked/held, and has a phone number — run **02 Transactions / Create transaction** with `phoneNumber` added to the body, or a simulation, first. `Confirm checkout callback` needs a real `paymentId`/`signature` pair from Razorpay Checkout's browser callback (see `frontend/src/PaymentScreen.tsx`); it cannot be fabricated from Postman alone.

## Notifications
Folder **10 Notifications**: email requires SMTP configured server-side; SMS requires `HTTPSMS_API_KEY`/`HTTPSMS_FROM_NUMBER` (see the `raksha-config` ConfigMap) — both are optional and degrade to a clear `last_error` rather than blocking transaction processing when unset.

## Organization & Billing
Every transaction/alert/rule/notification-setting request in this collection is implicitly scoped to the logged-in user's organization (folder **13 Organization**). Every accepted `Create transaction` — including simulator runs — increments that organization's monthly usage counter (folder **14 Billing**); exceeding the plan cap (FREE 500/mo, PRO 10,000/mo, ENTERPRISE unlimited) returns HTTP 402 from `Create transaction` itself.

## AI narrative & evaluation
Folder **02 Transactions / AI narrative for transaction**: populated asynchronously once a transaction is scored SUSPICIOUS/HIGH_RISK. It is advisory only — rephrasing the deterministic rule output, never changing the score. Folder **15 AI Evaluation** grades that narrative against the deterministic ground truth (not a second AI judging the first) across eight dimensions: accuracy, hallucination, financial consistency, reliability, explainability, safety, latency and cost. Both features degrade gracefully (state `FAILED` with `last_error: "AI not configured"`) if `AZURE_OPENAI_*` isn't set server-side.

Postman format: Collection v2.1.
Official scripting reference: https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-send-request/
