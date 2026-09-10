# Raksha Postman APIs

Import both JSON files into Postman, select **Raksha Local**, and set its **password** locally to the DEMO_PASSWORD in the project's .env. No password is included in these exports.

## Quick start
1. Ensure the Docker app is available at http://localhost:8088.
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
Use the Postman desktop app or a desktop agent that can reach localhost. Leave the cookie jar enabled and keep the same hostname in baseUrl. Authentication uses a JSESSIONID cookie, not a Bearer token. The collection-level pre-request script uses pm.sendRequest to fetch CSRF data before every POST/PUT. It then sets the returned CSRF header on the outgoing request, including Login and Logout. Session rotation after login is handled by fetching a new token for each write.

Built-in demo emails: admin@fraudshield.demo, analyst@fraudshield.demo, viewer@fraudshield.demo. They use the seeded demo password. Changing .env after initial database seeding does not reset existing user passwords.

## Rules and recovery
Only admins can read/change rules, read audit events, or retry dead jobs. Read current risk policy captures all current values before an update. Change the desired body fields or collection variables explicitly. Updates increment the policy version and affect future assessments only.

Retry requires an actual dead job. If List dead jobs returns [], there is nothing to retry. Do not run the entire collection blindly: it includes optional policy changes, case resolution, and a retry request requiring an existing failure. Use individual requests or select the desired workflow folders.

## Coverage
Includes every implemented /api route: CSRF, login/logout/current user, transaction creation/list/detail, dashboard, all four simulations, alerts, claim/resolve actions, notes, rules, audit, dead jobs and retry. No nonexistent payment or ML API is advertised. /actuator/health is internal to the Java service and is not routed by the Docker web proxy, so it is not included as a public request.

Postman format: Collection v2.1.
Official scripting reference: https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-send-request/
