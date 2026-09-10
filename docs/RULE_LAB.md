# Rule Lab

Open **Rule Lab** as an administrator. It provides three connected features:

1. **Repeated-amount detection:** flag a configured number of payments with the same amount and currency from one customer account within a time window.
2. **Historical impact preview:** compare a proposed rule with recorded assessments before saving it.
3. **Live shadow mode:** record matches and proposed scores without adding this rule's points to real assessments.

## Five-payment demo

Set Number of equal payments to **5**, Time window to **5** minutes, Risk points to **100**, and mode to **Shadow**, then save.

In Create transaction, submit five distinct transactions within five minutes using the same customer account and amount (for example, account `judge-repeat-demo` and INR 100). Use Start a new transaction between submissions. Merchants may differ. The fifth equal payment appears in Shadow observations with the actual score and proposed score. Other active rules can still raise the actual score or generate alerts independently.

Enter that account in Preview historical impact and select Preview impact. Review the matches and score changes. Switch the rule to Active and save, then create another equal payment within the window to add the configured points. Scores cap at 100; this is an investigation priority score, not a probability of fraud. Switch to Off to stop evaluating the rule for future assessments.

This rule counts equal payments within a window, including the current payment. Payments of different amounts do not count and do not reset the count. It does not require consecutive equal payments. Accounts and currencies are isolated. Late-arriving events cannot contribute to the earlier assessment of an event received before them.

## Preview boundaries

Preview evaluates up to 500 latest scored transactions received in the last 30 days, optionally filtered by account. Earlier available history can contribute to each payment's match count. The UI indicates truncation and shows up to 50 matched or changed examples.

Other recorded rule contributions are frozen. Preview replaces the repeated-amount contribution with the proposed contribution and uses the current policy's classification thresholds. It does not rerun every historical rule or rewrite recorded assessments. Legacy transactions without a stored base contribution use their recorded score. Investigation labels are sparse, reviewer-provided outcomes; the preview is not an accuracy benchmark.

Preview writes no transactions, cases, emails, or audit events. Saved configuration changes use optimistic versions, increment the policy version and are audited. Shadow matches retain the rule configuration/version, actual score and proposed score; the UI shows the latest 100 observations across versions. Existing assessments remain unchanged when settings change. The rule defaults to Off after migration.

## API

All routes require administrator access. Use the existing session and CSRF conventions.

- `GET /api/rules/lab`: current configuration and version.
- `PUT /api/rules/lab`: `{ "version": 1, "mode": "SHADOW", "repeatCount": 5, "windowMinutes": 5, "points": 100 }` (use the actual current version).
- `POST /api/rules/lab/preview`: `{ "repeatCount": 5, "windowMinutes": 5, "points": 100, "accountId": "judge-repeat-demo" }`. Use an empty accountId for all accounts.
- `GET /api/rules/lab/observations`: recent shadow matches.

Allowed modes: OFF, SHADOW, ACTIVE. Count: 2-20; window: 1-60 minutes; points: 1-100.

## Validation and deployment

Java packaging, React production build and the Docker integration test passed. Run `node scripts/rule-lab-smoke.mjs` against the local application with its existing environment configuration. The test temporarily pauses email and two interfering rules, restores settings in finally, and leaves synthetic transactions and audit history. Use a demo environment.

The test covers mixed amounts, account isolation, window boundaries, equal event timestamps, late arrivals, shadow isolation, active scoring, preview replacement, unchanged historical scores, access control and stale updates.

Deploy both updated API and web images. Flyway V4 creates the Rule Lab configuration/observation tables and adds base-risk points to transactions. No additional external service is required.
