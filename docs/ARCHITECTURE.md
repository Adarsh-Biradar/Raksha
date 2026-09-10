# Architecture and operating guide

## System flow

React runs as static files behind Nginx. Same-origin /api requests reach Spring Boot. Spring Security authenticates server-side sessions backed by the application's memory. PostgreSQL stores users, events, jobs, assessments, alerts, cases and audit records. Flyway applies versioned migrations on startup.

The same Java process contains a scheduled background worker. A separate broker is intentionally deferred: a transaction and its scoring job are committed in the same PostgreSQL transaction, eliminating a database-to-broker dual-write gap for this MVP.

## Transaction integrity and processing

POST /api/transactions requires Idempotency-Key equal to eventId. The database enforces unique event IDs. A canonical request hash distinguishes identical retries from conflicting reuse. Identical duplicates return the existing ID; different content returns 409.

The worker selects a ready job with FOR UPDATE SKIP LOCKED, obtains an account advisory lock, computes a risk assessment, writes any alert and audit event, and marks the job done in one transaction. A crash before commit rolls all these changes back. Completed transaction IDs cannot create duplicate alerts due to a unique database constraint.

After a scoring exception, the worker records a bounded retry with increasing delay. After three failures the job is DEAD and the transaction FAILED. An admin can retry dead jobs through Rules & settings. Database unavailability leaves durable pending work for later polling.

The default deployment runs one API/worker instance. Concurrent ingesters and case updates are supported. Multiple instances require shared sessions, distributed rate limiting and careful contention/failure testing before being advertised as supported.

## Risk methodology

Points are capped at 100:

| Signal | Points |
| --- | --- |
| Amount at or above configured threshold, initially INR 50,000 | 25 |
| Amount at least 3x historical average and normalized deviation at least 3 | 30 |
| Unseen device with at least 5 historical events | 15 |
| Unseen country with at least 5 historical events | 15 |
| Five or more events in the preceding 5 minutes, including current event | 25 |
| At least three reported preceding failures | 25 |

Normalized deviation is (amount - mean) / max(population standard deviation, mean * 0.25). The floor prevents tiny baseline variance from dominating. Five earlier events are required for behavioral scoring. Cold-start assessments explicitly disclose insufficient history.

Classification starts at 30 for SUSPICIOUS and 70 for HIGH_RISK. Both create an analyst alert. These heuristic thresholds require evaluation; they are not learned or calibrated fraud probabilities. No LLM or trained classifier is used. This demonstrates statistical anomaly detection, not a claim of advanced machine learning.

Features include only events with strictly earlier occurred_at and received_at no later than the current event's receipt. All supported events use INR. Earlier events are activity observations, not automatically verified legitimate transactions, so baseline poisoning remains a known risk.

Event time and receipt time are preserved. Events over ten minutes late are marked; past assessments are never silently rewritten. Earlier late events arriving after an assessment do not cause automatic reassessment. Simultaneous events sharing exactly the same event timestamp are excluded from one another's historical baseline, a known velocity limitation.

All detections retain explanations, historical feature values and policy version. Policy changes affect future processing only. Accounts with unusually large legitimate purchases can still cause false positives; analysts can record that outcome. Outcomes do not automatically retrain a model.

## Security

- BCrypt passwords; random initial demo password is read from an uncommitted environment file.
- HttpOnly, SameSite=Strict session cookie; CSRF required on mutations, including login/logout.
- Backend authorization for admin configuration/audit and analyst mutations.
- Viewer access is read-only. Case ownership is enforced for resolution.
- Case and policy versions reject stale updates.
- Prepared SQL statements, bounded lists, validated amounts and bounded text.
- Security headers and a self-only Content Security Policy on Nginx.
- Database ports are private within the Compose network; web binds to localhost by default.
- Logs use internal IDs rather than financial payloads or passwords.
- The in-memory rate limiter allows 12 login requests and 600 other requests per minute per direct peer IP. Behind Nginx all clients share the proxy bucket. This is coarse local abuse protection, not production per-user enforcement.
- The application database user owns the schema. Audit is recorded transactionally and has no edit API, but is not tamper-proof against a database administrator or compromised application credentials.
- Existing seeded users are not re-keyed by editing .env; password lifecycle and MFA are deferred.
- Session state is lost on API restart; users must log in again.
- Single organization only; no multi-tenant privacy claim.
- The simulator accepts synthetic device/country/failed-attempt values. A real ingestion integration must authenticate the source and validate provenance rather than trust arbitrary client risk signals.

## API outline

Authentication: GET /api/csrf, POST /api/auth/login (form encoded username/password), POST /api/auth/logout, GET /api/auth/me.

Transactions: POST /api/transactions, GET /api/transactions?search=&classification=ALL&page=0, GET /api/transactions/{id}.

Monitoring: GET /api/dashboard, GET /api/alerts.

Cases: POST /api/alerts/{id}/action with version, action CLAIM or RESOLVE, optional outcome CONFIRMED_FRAUD or FALSE_POSITIVE and required resolution reason; GET/POST /api/alerts/{id}/notes.

Administration: GET/PUT /api/rules, GET /api/audit-events, GET /api/jobs, POST /api/jobs/{id}/retry.

Simulation: POST /api/simulations with scenario NORMAL, TAKEOVER, VELOCITY or LATE.

Accepted transactions return 202. Validation returns 400, unauthenticated reads 401, unauthorized/CSRF rejection 403, missing records 404, conflicting writes 409 and rate limit rejection 429. Missing CSRF can result in 403 before authentication is evaluated.

## Recovery and observability

Pending, failed and scored statuses are distinct; pending never means normal. The dashboard reports pending jobs and dead jobs. API logs include scored transaction IDs and classification; worker failures log job IDs and exception type. Actuator exposes a minimal database health endpoint inside the network. Container restart policies restart exited processes, not merely unhealthy ones.

Backup: use pg_dump to a file outside the database volume; protect the backup and restore it into a separate PostgreSQL instance before relying on it. Backup automation and a tested disaster-recovery objective are not provided. Do not treat Docker volumes as backups.

To demonstrate process recovery, set WORKER_ENABLED=false in .env and recreate the API with docker compose up -d. Submit transactions and observe PENDING. Restore WORKER_ENABLED=true, recreate the API, sign in again, and verify the same records become SCORED without duplicate alerts. This demonstrates durable queued-work recovery; it does not prove crash-at-every-instruction correctness.

## Deployment and scalability

The included Compose stack is a local single-host deployment. For remote use, provision a VM, restrict ingress, use a trusted HTTPS reverse proxy and set COOKIE_SECURE=true. BIND_ADDRESS must be configured deliberately if direct external access is intended. Do not expose PostgreSQL or administrative infrastructure. Production secrets should come from a secrets manager, not committed Compose values.

For scale: move sessions to a shared store, separate API/worker processes, add indexed/materialized account windows, benchmark query costs, partition historical events and move work to a broker via transactional outbox if justified. Keep idempotency and alert uniqueness when using at-least-once messaging. A managed database with replication and a tested backup/restore process is a higher priority than Kubernetes for this product.

## Evaluation and future work

The current smoke test checks behavior and financial integrity on generated examples, not model quality. To measure precision/recall, create a separate labeled evaluation dataset containing both attacks and difficult legitimate cases, split by account/time, and compare rules alone against a learned model. Avoid training and testing against identical generator rules. Record false-positive rate, recall, throughput and p95 end-to-end latency. No measured accuracy claim is made by this repository.
