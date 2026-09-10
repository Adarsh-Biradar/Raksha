# Email alerts

Administrators can open **Email alerts** to add/remove management recipients (one address per line, maximum 20), enable/pause automatic mail, select high-risk only or suspicious + high-risk, send a test and inspect the latest 100 delivery records. Save settings before testing. Tests send to the saved addresses even when automatic alerts are paused, at most one batch per minute.

## Server configuration

The API reads SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD and SMTP_FROM from server environment variables. For Gmail use smtp.gmail.com and port 587 with the account's app password. Authenticated STARTTLS is required, with certificate hostname checking and finite connection/read/write timeouts. The UI displays configuration presence and sender details, never the app password. SMTP credentials are not baked into images.

Update .env privately, then recreate the API container to apply changes. Existing notification recipient/settings records are stored in PostgreSQL and survive image updates. Database and login passwords are independent of SMTP credentials.

In Kubernetes, both manifest formats reference the optional SMTP keys in raksha-secrets; the ConfigMap supplies host/port. The secret helper imports the SMTP values from local .env into the explicitly selected cluster. The single-file bundle contains empty SMTP fields to fill privately. After changing Secrets, restart the API deployment to reload its environment. Enable notifications in the UI after the deployed SMTP credentials and recipients are configured.

## How delivery works

1. Scoring creates the alert and one email outbox row per recipient in the same database transaction. A unique (alert_id, recipient) constraint prevents duplicate enqueueing.
2. A separate scheduled task sends minimal plain-text notifications: alert/transaction IDs, class, score and notification ID. Account, merchant, amount, IP, phone and app passwords are not included.
3. Successful SMTP acceptance is recorded as SENT. This is not proof of inbox delivery; check spam folders and provider delivery policies.
4. Failures use bounded retries (30 seconds, then 60 seconds) and become FAILED after three attempts. Errors shown in the UI are sanitized. Admin can retry failed messages.
5. Pending messages are cancelled if the recipient is removed, email is paused, or severity no longer qualifies. Test messages are allowed while paused, but still require the recipient to remain configured.
6. Configuration changes affect new alerts and pending messages. Historical alerts are not backfilled. Already accepted messages cannot be recalled.

SMTP outages do not roll back risk assessments. The scheduler has two threads so the mail task does not occupy the scoring scheduler thread. Delivery holds a row lock during bounded SMTP I/O; this favors simple coordination for the single-instance MVP over high throughput. A crash after SMTP acceptance but before database commit can cause a duplicate. Notification IDs identify repeats; exactly-once email delivery is not claimed. At greater scale, use a separate notification service, leases, provider idempotency/webhooks, queue monitoring and organization-specific recipient policies.

Audit events cover settings changes, queued tests, sent/failed attempts, cancellations and manual retries. SMTP health is excluded from the application's readiness check so an email provider outage does not take transaction ingestion offline.

## APIs (administrator only)

- GET /api/notifications/settings: saved settings plus non-secret SMTP status.
- PUT /api/notifications/settings: {version, enabled, minimumClassification, recipients}. recipients is a newline-delimited string; minimumClassification is HIGH_RISK or SUSPICIOUS.
- GET /api/notifications/deliveries: latest 100 delivery records.
- POST /api/notifications/test: queue one test per saved recipient.
- POST /api/notifications/deliveries/{id}/retry: requeue a failed notification.

Authentication and CSRF apply to mutations. Settings updates reject stale versions. Automatic emails default to paused on a fresh database until SMTP/recipients are configured.

## Verification

Java/React builds and the existing fraud/rules smoke tests passed. Two authorized real messages (one TEST, one HIGH_RISK) were accepted by Gmail. Integration checks covered administrator-only access, missing-secret protection in responses, invalid recipients, stale writes, test cooldown, atomic enqueueing, duplicate suppression and paused-mail scoring. An isolated PostgreSQL schema and fake sender verified three-attempt failure, safe errors, recipient removal, pause cancellation and retry recovery without making SMTP network calls.

scripts/email-smoke.mjs deliberately requires SEND_TEST_MAIL=true because it sends two real messages to SMTP_USERNAME. Run it only against the local sandbox with authorization for that recipient. Existing fraud smoke scripts require automatic notifications to be paused to avoid email from their synthetic transactions.

References: [Google's SMTP settings](https://support.google.com/mail/answer/7104828?hl=en-uk), [Spring Boot mail configuration and timeouts](https://docs.spring.io/spring-boot/reference/io/email.html).
