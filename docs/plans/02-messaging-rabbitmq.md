# Plan: Messaging & event-driven architecture (RabbitMQ)

## Goal
Introduce a production-appropriate messaging layer for transaction ingestion, risk analysis and
alert notification, while preserving the existing correctness guarantees (idempotency, no
duplicate alerts, bounded retry, dead-letter recovery) described in `docs/ARCHITECTURE.md`.

## Current state
- No broker. A transaction and its scoring job are committed in the **same PostgreSQL
  transaction** (an intentional dual-write-avoidance choice per `docs/ARCHITECTURE.md`).
- `RiskWorker.java` polls the job table with `FOR UPDATE SKIP LOCKED` + an account advisory lock.
- Bounded retry with increasing delay; 3 failures → job `DEAD`, transaction `FAILED`.
- Single API/worker instance; the docs explicitly flag horizontal scaling as unsupported without
  a broker.

## Design decision
Keep the **transactional outbox pattern** — don't replace the Postgres commit with a direct
broker publish (that reintroduces the dual-write gap the current design avoids). Instead:
`API commits transaction + outbox row → OutboxPublisher relays to RabbitMQ → RiskWorker consumes`.
This is additive on top of the existing job table, not a replacement of it (job table becomes the
consumer-side durable state; outbox table becomes the publish-side durable state).

## Steps

### 1. Add RabbitMQ to the stack
`compose.yaml`:
```yaml
  rabbitmq:
    image: rabbitmq:3-management-alpine
    ports:
      - "127.0.0.1:15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
```
`backend/pom.xml`: add `spring-boot-starter-amqp`.

### 2. Define topology
- Exchange: `raksha.events` (topic).
- Queues: `transactions.ingested`, `alerts.generated`, each with a matching
  `*.dlq` bound to a `raksha.events.dlx` dead-letter exchange.
- Configure `x-dead-letter-exchange` + `x-message-ttl`-based retry (or use Spring Retry with
  manual nack + republish-with-delay) to mirror the existing "increasing delay, 3 attempts, then
  DEAD" policy.

### 3. Outbox table + publisher
- New Flyway migration `V3__outbox.sql`: `outbox_events(id, event_id, payload, created_at,
  published_at NULL)`.
- `ApiController.java`'s transaction-creation path: insert into `outbox_events` in the same
  DB transaction as the existing event/job insert (no behavior change to current commit).
- New `OutboxPublisher.java`: `@Scheduled` poller (same `FOR UPDATE SKIP LOCKED` pattern already
  used in `RiskWorker.java`) that publishes unpublished rows to `transactions.ingested` and marks
  `published_at`. This is the standard outbox relay — keeps "commit + eventually publish" atomic
  with respect to crashes.

### 4. Convert worker to a consumer
- `RiskWorker.java`: replace (or run alongside, behind `WORKER_MODE=poll|queue` for a safe
  migration) the polling loop with a `@RabbitListener` on `transactions.ingested`.
- On message receipt: look up the job row by `event_id` (still authoritative), run existing
  scoring logic, commit assessment/alert/audit in one DB transaction, `ack` only after commit.
- On scoring exception: `nack` with requeue=false and let the DLX/TTL retry policy handle bounded
  retry; after 3 redeliveries (tracked via a header counter), route to `transactions.dlq` and mark
  the job `DEAD` exactly as today.
- Idempotency is already guaranteed by the unique `event_id` constraint — safe under RabbitMQ's
  at-least-once delivery (duplicate delivery → duplicate lookup → no-op if already SCORED).

### 5. Alert notifications via queue
- On alert creation, publish to `alerts.generated`.
- `EmailAlerts.java` (existing email outbox) becomes a consumer of this queue instead of (or in
  addition to) its current trigger path — keeps the existing DB-backed email outbox/retry described
  in `docs/EMAIL_ALERTS.md` as the durable record, with the queue only triggering faster delivery.

### 6. Kubernetes
- Add a RabbitMQ StatefulSet + Service manifest under `k8s/` (or use a managed AMQP service if
  deploying to cloud — see the cloud-deployment plan), with a PVC for the Mnesia data directory.

## Testing
- Extend `scripts/smoke.mjs` (or add `scripts/messaging-smoke.mjs`): submit a transaction, confirm
  it's consumed and scored; kill the consumer process mid-message (simulate crash) and confirm
  redelivery does not create a duplicate alert (unique constraint already enforces this — this
  test proves the queue path preserves it).
- Test delayed delivery: publish with an artificial delay header, confirm the "late event" marking
  behavior in `docs/ARCHITECTURE.md` ("Events over ten minutes late are marked") still applies.
- Test broker unavailability: stop RabbitMQ, submit transactions, confirm they queue in the outbox
  table (`published_at IS NULL`) and drain once the broker returns — no data loss.
- Load: submit a burst of transactions, confirm no duplicate alerts and that DLQ only receives
  jobs that exhaust retries.

## Risk / rollback
- Ship behind `WORKER_MODE=queue` feature flag defaulting to `poll` (current behavior) until the
  queue path is verified in the demo environment; flip the flag rather than deleting the old code
  path in the first pass.
- If RabbitMQ is unavailable at demo time, `WORKER_MODE=poll` still works end-to-end — no hard
  dependency introduced on the critical path until you're confident.
