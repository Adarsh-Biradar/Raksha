# Security audit — 2026-09-11

Fresh, code-verified pass (not the earlier chat-only audit, which was never saved). Scope:
broken authorization, tenant isolation, secret management, replay/payment manipulation, AI
prompt injection. Each finding below was confirmed by reading the actual query/handler, not
inferred.

## Findings

### 1. [FIXED] Core scoring pipeline ignored per-org configuration (Critical)
`ScoringEngine.score()` read `risk_policy WHERE id=1` and `RuleLab.evaluate()`/`configuration()`
read `rule_lab WHERE id=1` — hardcoded leftovers from before the V10 multi-tenant migration.
Every transaction, regardless of which organization it belongs to, was scored against the
*default* organization's thresholds/velocity limit/Rule Lab settings, not its own. Masked today
because only one organization exists; becomes a real cross-tenant correctness bug the moment a
second org is created (org B's admin changes their thresholds — org B's transactions keep
scoring against org A's policy).
**Fix**: `ScoringEngine.score()` now reads `t.get("org_id")` (already present on the fetched
transaction row) and scopes both the `risk_policy` and `rule_lab` lookups to it.

### 2. [FIXED] Email notification pipeline ignored per-org settings (High)
`EmailAlerts.enqueue()` and `.deliver()` read `notification_settings WHERE id=1`, bypassing the
org-scoped `recipients`/`enabled`/`minimum_classification` an org actually configured via
`PUT /api/notifications/settings`. Same class of bug as #1: with a second org, org B's alerts
would be filtered/delivered per org A's settings.
**Fix**: threaded `orgId` from `ScoringEngine` → `EmailAlerts.enqueue(transactionId,classification,score,orgId)`,
and `deliver()` now joins `email_deliveries.org_id` (new column, stamped at insert) instead of a
single global settings row.

### 3. [FIXED] Cross-tenant data exposure — audit trail, dead jobs, notification history (High)
`GET /api/audit-events`, `GET /api/jobs`, `POST /api/jobs/{id}/retry`, `GET /api/notifications/deliveries`,
`GET /api/notifications/sms-deliveries`, `GET /api/rules/lab` (config), `GET /api/rules/lab/observations`,
and `GET /api/ai/evaluations` had no `org_id` filter at all — any authenticated admin could see
(and for jobs, retry) every organization's operational data. All are ADMIN-only routes today, but
"any admin can see every org" is still a tenant-isolation break, not a documented multi-org admin
capability.
**Fix**: added `org_id` to `email_deliveries`, `sms_deliveries`, `audit_events`, `scoring_jobs`,
`ai_eval_runs` (via new migration `V12__tenant_scoping.sql`, backfilled to the default org), and
scoped every listed endpoint's query/lookup by the caller's org. `rule_lab` GET now matches the
already-correct `PUT` (org-scoped) instead of the stale global read.

### 4. [FIXED] Rule Lab preview leaked cross-tenant transactions (Medium)
`POST /api/rules/lab/preview` selected from `transactions` filtered only by status/date/account —
no `org_id` filter — so an admin could preview Rule Lab impact against another organization's
transaction history.
**Fix**: added `AND org_id=?` to the preview's transaction subquery.

### 5. [Verified, no change needed] AI prompt-injection surface
`AiExplainer.prompt()` only includes deterministic, server-computed fields (score, classification,
currency, amount, `reasons`/`features` produced entirely by `ScoringEngine`) — no free-text field a
transaction submitter controls (merchant, event ID) ever reaches the LLM prompt. This was already
correctly designed; confirmed no injection vector from transaction input reaches the model.

### 6. [Verified, no change needed] Payment replay/signature handling
Razorpay webhook path enforces payload-size cap, event-ID format, HMAC signature verification,
payload-hash-on-reuse detection (rejects same `event_id` with different content), and idempotent
`ON CONFLICT DO NOTHING` insertion. Checkout confirmation cross-checks `order_id`/`payment_id`
against the stored order before accepting. No changes needed.

### 7. [FIXED] Rate limiter never ran for unauthenticated brute-force login attempts (High)
`SecurityConfig`'s `RequestLimit` filter was registered with `addFilterBefore(requestLimit,
UsernamePasswordAuthenticationFilter.class)`. Spring Security's fixed internal filter order runs
`CsrfFilter` *before* `UsernamePasswordAuthenticationFilter`, so any `POST /api/auth/login` with a
missing or invalid CSRF token was rejected by `CsrfFilter` before the request ever reached
`RequestLimit` — the 12/min login cap silently never applied to the common case of a scripted
brute-force attempt (which has no reason to fetch/replay a real CSRF token first). Discovered
while verifying the new Redis-backed limiter: login attempts weren't incrementing their counter at
all. This bug predates the Redis migration; it was present in the original in-memory limiter too.
**Fix**: moved the filter to `addFilterBefore(requestLimit, CsrfFilter.class)` so every request is
counted before CSRF (or anything else) can short-circuit it.

### 8. [Already fixed earlier this project] Hardcoded httpSMS credentials
`SmsAlerts.java` previously hardcoded `HTTPSMS_API_KEY`/`HTTPSMS_FROM_NUMBER`. Externalized via
`@Value` with a `configured()` gate; wired only through the `raksha-config` ConfigMap (no
Kubernetes Secret, per explicit project preference). No further action.

## Not re-verified this pass (unchanged from general architecture, lower priority)
- Rate limiting is in-memory/per-instance (documented limitation; addressed by the scalability
  work using Redis, see `docs/plans/04-scalability.md`).
- No dependency/container vulnerability scanning in CI before this pass (see CI/CD changes).
