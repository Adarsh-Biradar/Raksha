# Plan: Business expansion (multi-tenant organizations + usage-based billing)

## Goal
Add commercial-product scaffolding: multiple customer organizations, per-org data isolation, and
usage metering — turning the single-demo-org MVP into something that maps to the "B2B
fraud-detection SaaS/API with usage-based pricing... enterprise licensing" business potential
named in the problem statement.

## Current state
- "Single organization only; no multi-tenant privacy claim." (`docs/ARCHITECTURE.md`)
- Three fixed demo users (admin/analyst/viewer) with no org concept.
- No billing, metering, or plan/tier logic anywhere in `backend/` or `frontend/`.

## Steps

### 1. Data model
New Flyway migration `V5__organizations.sql`:
- `organizations(id, name, plan_tier, created_at)`.
- Add nullable `org_id` FK to `users`, `events`, `alerts`, `rules_catalog`, `audit_events`, then
  backfill existing demo rows into a single "Demo Org" row, then make the column `NOT NULL`.
- Add `usage_counters(org_id, period_start, transactions_scored, alerts_created)` for metering.

### 2. Scope every query and mutation by org
- `ApiController.java`, `RuleController.java`: every read/write must filter/insert by the
  authenticated user's `org_id` — audit each existing endpoint listed in `docs/ARCHITECTURE.md`'s
  "API outline" section one by one.
- `SecurityConfig.java`: put `org_id` on the authenticated principal so authorization checks are
  a straightforward `WHERE org_id = :callerOrgId`, not string-matched or forgotten per-endpoint.
- This directly resolves the "no multi-tenant privacy claim" limitation, not just adds billing.

### 3. Usage metering
- Increment `usage_counters` in the same transaction where `RiskWorker.java` commits a scored
  assessment (piggyback on the existing "one transaction" commit boundary — no new consistency
  risk).
- Expose `GET /api/billing/usage` (admin-only) returning current-period counts.

### 4. Plan tiers and gating
- `organizations.plan_tier`: `FREE`, `PRO`, `ENTERPRISE`.
- Gate one real limit per tier to make it meaningful, not cosmetic: e.g. `FREE` capped at 500
  scored transactions/month (soft-block new ingestion past the cap with a clear 402-style API
  error) and a max of 3 custom rules in the rule catalog (`RuleController.java` already manages a
  catalog of 8 checks — cap how many a FREE org can enable/customize).

### 5. Billing UI
- New admin screen `frontend/src/BillingSettings.tsx`: show plan tier, usage-vs-cap, and an
  upgrade action (stub Stripe Checkout — reuse the Stripe integration from the payment-gateway
  plan if implemented, otherwise a "Contact sales" stub is acceptable for the demo).

### 6. Optional: Stripe Billing subscriptions
- If the payment-gateway plan (`06-payment-gateway.md`) is already done, wire real Stripe
  subscription objects per org and update `plan_tier` via Stripe webhook events
  (`customer.subscription.updated`) — same webhook-verification pattern as that plan.

## Testing
- Create two organizations via a seed script; confirm org A's admin cannot see org B's
  transactions/alerts/rules through any existing endpoint (write this as an explicit negative
  test — the most important test in this plan, since it's a security property, not just a
  feature).
- Confirm usage counters increment correctly under concurrent ingestion (reuse the existing
  concurrent-duplicate-ingestion test pattern from `scripts/smoke.mjs`).
- Confirm a FREE-tier org is blocked once its cap is hit, with a clear error the frontend surfaces.

## Risk / rollback
- Cross-org data leakage is the main risk of this plan — treat every endpoint's org-scoping as a
  security review item, not just a feature add (pairs with the "broken authorization" item in the
  Advanced Security list).
- Migration is additive (nullable column → backfill → NOT NULL) so it's safely reversible before
  the final `NOT NULL` step; take a `pg_dump` backup before running the final migration.
