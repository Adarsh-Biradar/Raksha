# Plan: Observability (metrics, error tracking, dashboards)

## Goal
Give judges/operators visibility into transaction throughput, scoring latency, alert volume,
queue health, and errors — beyond the existing structured logs and `/actuator/health`.

## Current state
- `spring-boot-starter-actuator` is already a dependency (`backend/pom.xml`).
- No metrics registry, no Prometheus/Grafana, no dashboards.
- Structured event logging exists per `docs/ARCHITECTURE.md` ("API logs include scored
  transaction IDs and classification; worker failures log job IDs and exception type").
- `compose.yaml` has healthchecks for `postgres` and `web` only.

## Steps

### 1. Add Micrometer + Prometheus registry
- `backend/pom.xml`: add
  ```xml
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>
  ```
- `backend/src/main/resources/application.properties` (or equivalent config): expose the endpoint
  ```
  management.endpoints.web.exposure.include=health,info,prometheus
  management.endpoint.health.probes.enabled=true
  ```
- Verify `GET /actuator/prometheus` returns metrics after `mvn -f backend/pom.xml package` + run.

### 2. Instrument custom metrics
In `FraudService.java` and `RiskWorker.java`, inject `MeterRegistry` and record:
- `raksha.transactions.ingested` (Counter) — incremented in the ingestion path (`ApiController.java`).
- `raksha.scoring.latency` (Timer) — wrap the scoring call in `RiskWorker.java`.
- `raksha.alerts.created` (Counter, tagged by `classification`: SUSPICIOUS/HIGH_RISK).
- `raksha.jobs.pending` / `raksha.jobs.dead` (Gauge) — read from the job table's counts, same
  query the dashboard endpoint (`GET /api/dashboard`) already uses.
- `raksha.jobs.retry` (Counter) — incremented in the existing bounded-retry path.

Keep names namespaced under `raksha.*` to avoid clashing with default JVM/HTTP metrics.

### 3. Error tracking
- Add a Counter `raksex.exceptions` tagged by exception class name, incremented in the worker's
  catch block (where it currently logs "job IDs and exception type" — reuse that same catch site).
- Optional (if time allows): wire Sentry free tier via `sentry-spring-boot-starter` for stack-trace
  capture; gate behind an env var (`SENTRY_DSN`) so it's a no-op without one.

### 4. Prometheus + Grafana in Compose (local demo)
Add to `compose.yaml`:
```yaml
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./docs/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "127.0.0.1:9090:9090"
  grafana:
    image: grafana/grafana:latest
    ports:
      - "127.0.0.1:3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-admin}
    volumes:
      - grafana_data:/var/lib/grafana
```
Add `grafana_data:` to the `volumes:` block. Create `docs/observability/prometheus.yml` scraping
`api:8080` (Spring Boot's actuator port) at `/actuator/prometheus` on a 10s interval.

### 5. Grafana dashboard
Create `docs/observability/raksha-dashboard.json` (Grafana dashboard JSON) with panels for the
metrics above. Import via Grafana UI or provisioning volume mount for a one-command demo.

### 6. Kubernetes wiring
- Confirm `k8s/api.yaml` liveness/readiness probes point at `/actuator/health/liveness` and
  `/actuator/health/readiness` (Spring Boot's Kubernetes-aware health groups — enable via
  `management.endpoint.health.probes.enabled=true` from step 1).
- Optionally add a `ServiceMonitor` manifest if the cluster runs the Prometheus Operator.

## Testing
- `mvn -f backend/pom.xml package` — confirm build succeeds with new dependency.
- Start stack, hit `/actuator/prometheus`, confirm `raksha_*` metrics appear after running the
  simulator (`node scripts/smoke.mjs` or the UI's Simulator scenarios).
- Confirm Grafana dashboard renders non-zero values after a few simulated transactions.
- Kill the worker mid-job (`WORKER_ENABLED=false` trick from `docs/ARCHITECTURE.md`) and confirm
  `raksha.jobs.pending` gauge rises, then falls after re-enabling.

## Risk / rollback
- Additive only — no schema or API contract changes. Safe to merge independently.
- Prometheus/Grafana containers are optional local-demo additions; remove the two services from
  `compose.yaml` to fully revert with no backend impact.
