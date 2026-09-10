# Plan: Scalability

## Goal
Demonstrate — with real changes and a load test, not just documentation — how Raksha evolves from
a single-instance MVP to a horizontally scalable system.

## Current state (from `docs/ARCHITECTURE.md`)
- "The default deployment runs one API/worker instance... Multiple instances require shared
  sessions, distributed rate limiting and careful contention/failure testing before being
  advertised as supported."
- Session state lives in application memory (`SecurityConfig.java`) — lost on restart, and not
  shareable across replicas.
- Rate limiter is in-memory per-instance ("coarse local abuse protection, not production
  per-user enforcement").
- No documented indexing strategy for the historical-window statistics query in `FraudService.java`.

## Steps

### 1. Externalize sessions (unblocks horizontal API scaling)
- Add `spring-session-data-redis` + `redis` service to `compose.yaml`.
- `SecurityConfig.java`: replace in-memory session repository with Spring Session's Redis-backed
  one (`@EnableRedisHttpSession`).
- This directly removes the "session state lost on API restart" and "multiple instances require
  shared sessions" limitations called out in the architecture doc.

### 2. Distributed rate limiting
- Replace the in-memory rate limiter (`SecurityConfig.java` / wherever the 12/min login and
  600/min general limits are enforced) with a Redis-backed token-bucket (e.g. Bucket4j with the
  Redis backend, reusing the Redis instance from step 1).
- Keeps identical limits (12 login/min, 600 other/min) but now correct across replicas.

### 3. Database indexing and query cost
- Add a Flyway migration `V4__perf_indexes.sql` adding a composite index on
  `events(account_id, occurred_at)` (the column pair the statistical-baseline query in
  `FraudService.java` filters/sorts by, per the "historical events" logic in
  `docs/ARCHITECTURE.md`).
- Run `EXPLAIN ANALYZE` on the historical-window query before/after; document the plan change.
- Tune HikariCP pool size (`spring.datasource.hikari.maximum-pool-size`) based on load-test
  results from step 5, rather than guessing.

### 4. Separate API and worker scaling
- Split the single Spring Boot process's responsibilities: run N replicas of the API (stateless
  after step 1) behind the existing Nginx/`web` service, and scale the worker independently
  (especially once on RabbitMQ per the messaging plan — worker replicas become just more
  consumers on the same queue, safe due to existing idempotency).
- `k8s/api.yaml`: split into `api-deployment` (HTTP-serving replicas) and `worker-deployment`
  (consumer replicas), each independently scalable; or keep combined but add
  `WORKER_ENABLED=false` on HTTP-only replicas if splitting the JAR isn't feasible in the time
  budget.

### 5. Horizontal Pod Autoscaler
- Add an `HorizontalPodAutoscaler` manifest under `k8s/` targeting the API deployment on CPU
  (simple) or, if RabbitMQ is in place, queue depth via KEDA (stronger story for judges, more
  setup time — do CPU-based first, KEDA only if time remains).

### 6. Load test
- Add `scripts/loadtest.js` (k6) exercising `POST /api/transactions` and `GET /api/dashboard` at
  increasing concurrency.
- Record baseline (1 replica, before indexes) vs. after (N replicas, Redis sessions, indexes) in
  `docs/ARCHITECTURE.md`'s scalability section: p95 latency, max sustained TPS, error rate.

## Testing
- Functional: full smoke suite (`scripts/smoke.mjs`) against a 2-replica API deployment behind
  the existing Nginx `web` service — confirm login/session persists across requests hitting
  different replicas (proves Redis session sharing works).
- Load: run `scripts/loadtest.js` before and after indexing/pooling changes; the plan is only
  "done" when there's a documented before/after number, not just code changes.
- Failure: kill one API replica mid-session, confirm the user isn't logged out (session survives
  in Redis).

## Risk / rollback
- Redis session change is the highest-risk step (touches auth). Test login/logout/CSRF flows
  thoroughly before removing the in-memory fallback; keep it behind a config flag during rollout.
- Index migration is additive/reversible (`DROP INDEX` in a follow-up migration if it regresses
  write performance — unlikely for a read-pattern index, but verify write latency too).
