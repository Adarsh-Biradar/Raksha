# Raksha

A runnable hackathon MVP for simulated transaction monitoring, explainable fraud-risk scoring and analyst investigations.

**Stack:** Java 21 / Spring Boot 3.5, React 19 / TypeScript / Vite, PostgreSQL 16, Docker Compose.

## Run

Install Docker Desktop with Linux containers enabled and Node.js 22 for the setup/test scripts.

```sh
node scripts/setup.mjs
docker compose up --build -d
```

Open **http://localhost:8088**. The first image build downloads Java, Node and PostgreSQL dependencies and can take several minutes.

The local `.env` contains a randomly generated `DEMO_PASSWORD`. Use it with:

| Email | Permissions |
| --- | --- |
| admin@fraudshield.demo | All screens, policy changes, recovery and audit |
| analyst@fraudshield.demo | Monitoring, simulation, case notes and resolution |
| viewer@fraudshield.demo | Read-only monitoring and investigations |

The project already has a generated local `.env`; setup preserves it. Do not commit or publish that file. Seed passwords are inserted only when users do not already exist: changing DEMO_PASSWORD after initial startup does not change existing database users.

```sh
docker compose logs --tail 100 api
docker compose ps
docker compose stop
```

The database uses a persistent Docker volume. Stopping containers preserves data. Avoid commands that delete volumes unless you intend to erase the demo.

## Demo journey

1. Log in as admin or analyst.
2. Open Simulator and run Everyday spending. Eight historical events and one current payment are inserted.
3. Run Account takeover. The current payment triggers amount, statistical deviation, new device, new country and failed-attempt signals.
4. Open Investigations, investigate the high-risk event, assign it to yourself and add a note.
5. Resolve it as confirmed fraud or a false positive, with an explanation.
6. Sign in as admin and inspect Audit trail.
7. Run Rapid-fire payments or Delayed delivery to exercise other behaviors.

Overview, transaction lists and alerts refresh every four seconds. Close and reopen a transaction detail to refresh its assessment.

## Verification

With the Docker stack running:

```sh
node scripts/smoke.mjs
```

This creates synthetic test records and checks login, CSRF, role restrictions, concurrent duplicate ingestion, conflicting payload rejection, amount/currency validation, background scoring, explanations, alert uniqueness, ownership, optimistic concurrency, notes, resolution and audit history.

## Source layout

- `backend/src/main/java/com/fraudshield`: security, REST endpoints, ingestion and background worker.
- `backend/src/main/resources/db/migration`: Flyway schema migrations.
- `frontend/src/App.tsx`: React application and workflows.
- `frontend/src/style.css`: responsive UI styling.
- `compose.yaml`: web, Java API/worker and PostgreSQL services.
- `docs/ARCHITECTURE.md`: system flow, risk method, limitations and recovery.

## What is implemented

- Session authentication, BCrypt passwords, CSRF and backend roles.
- INR simulated transactions, validation and idempotency.
- PostgreSQL durable job queue with bounded retry and dead-letter recovery.
- Deterministic rules plus historical statistical anomaly scoring.
- Explanations, feature snapshots and policy-version capture.
- Alerts, analyst ownership, notes, resolution and concurrency checks.
- Versioned configuration, audit history, Docker persistence and structured event logging.

## Honest scope

This is a functional competition MVP, **not a certified production financial system**. It has one demo organization, no real payment rail, no trained ML model, no RabbitMQ, and no public deployment. The worker uses PostgreSQL row locks and atomic commits as its durable queue. Detection scores prioritize review; they are not fraud probabilities.

Advanced multi-tenant isolation, immutable external audit storage, production identity/MFA, trained-model evaluation, backup automation, HTTPS infrastructure and distributed rate limiting remain future work. See the architecture document before presenting production-readiness claims.

## Package already compiled artifacts

If Java and React have already been built locally, skip downloading build-tool images:

```sh
mvn -f backend/pom.xml package
npm --prefix frontend ci
npm --prefix frontend run build
docker compose -f compose.yaml -f compose.prebuilt.yaml up --build -d
```

The standard Dockerfiles still support building everything from source. The optional prebuilt Dockerfiles require backend/target/fraudshield-0.1.0.jar and frontend/dist. Both paths use the same Java 21 and unprivileged Nginx runtime images.

For host-based development, compose.dev.yaml exposes PostgreSQL only at localhost:54329. Use it with the main Compose file, set DATABASE_URL=jdbc:postgresql://localhost:54329/fraudshield for the Java process, and run the React development server with its /api proxy to localhost:8080. These development overrides are not used by the normal Docker deployment.
## Verification performed

On 10 September 2026:
- Java backend compiled successfully with Java 21; a Java 17-compatible artifact was also built to exercise a cached test runtime.
- React passed TypeScript checking and Vite production build.
- Frontend npm audit reported zero vulnerabilities after updating Vite to 6.4.3. This is not a Java or container vulnerability scan.
- Final runtime images were built with the prebuilt-artifact Dockerfiles and run using Java 21, Nginx, and PostgreSQL.
- The integration smoke test passed against the final Docker endpoint, including dashboard/list/admin read endpoints and the full fraud investigation workflow.
- The standard multi-stage source-build Dockerfiles are included; their full build was interrupted during slow base-image downloads. Local source builds and the prebuilt runtime deployment were verified instead.

The running local app is available at http://localhost:8088. To restart the existing built images, use docker compose up --no-build -d.

## Raksha appearance and mobile support

The product is now named Raksha. The moon/sun control on the login page and top bar switches between light and night modes. A choice is saved locally in your browser; on first use it follows your device preference. On phones and tablets use the menu button for labeled navigation. At phone widths, transaction, investigation and audit tables become cards, and details open full-screen. Forms, charts and scenario panels adapt to the available width.

Existing demo account emails and database identifiers remain compatible with your saved data. Postman exports are now named Raksha.postman_collection.json and Raksha.local.postman_environment.json.

## Docker Hub images and Kubernetes

The frontend and backend have separate Dockerfiles; there is no root Dockerfile. From the root, use `docker compose build api web` to build `adarshbiradar/raksha-api:latest` and `adarshbiradar/raksha-web:latest`. Docker repository names must be lowercase. Run `docker compose push api web` when ready to publish those images to your account.

For cluster deployment, see [the Kubernetes guide](k8s/README.md). It includes PostgreSQL persistent storage, Java and React/Nginx deployments, internal services, probes, resource limits, and a PowerShell helper that provisions secrets from your local .env. Enable/select your intended Kubernetes context before following the deployment commands.

Validation: Compose configuration, Kubernetes manifest rendering, and the secret helper's PowerShell syntax passed. Both newly named images were built locally using the existing compiled artifacts. No images were pushed and no cluster deployment was performed; this machine had no configured Kubernetes context.

## Managed rules and merchant transactions

Sign in as an administrator and open **Rules & settings** to view all eight checks: large payment, unusual amount, unfamiliar device, unfamiliar country, rapid payments, failed attempts, blocked IPv4, and blocked phone. Each card supports edited risk points and Pause/Resume. The Risk policy form below controls amount/velocity/classification thresholds. Rule updates reject stale versions and increment the global policy version; the worker takes a consistent policy/rule snapshot. Existing assessments are not recalculated. Reload rules to fetch changes from another administrator.

The two block lists start empty. Add one IPv4 address/CIDR range or internationally formatted phone number per line (maximum 200 entries). Phone numbers require a plus sign and country code; spaces, hyphens and parentheses normalize away. IPv6 is not currently supported. These are caller-supplied sandbox signals, not verified IP ownership or phone identity. Matching raises the configured score; it does not move or decline real funds.

Administrators and analysts can open **Create transaction**, enter a merchant name (recent merchants are suggested), customer account ID, INR amount, country, device, optional IP/phone, and preceding failed-attempt count. This is transaction entry by merchant name, not a merchant onboarding/account-management subsystem. The screen converts INR to integer paise, generates event time/idempotency ID, submits to the durable queue and polls the result with explanations. A retry reuses the submitted event. Input validation errors allow editing; ambiguous network failures keep the original payload for retry. Starting another transaction creates a new event, so inspect transaction history first if a prior submission's outcome is uncertain.

API additions (session/CSRF requirements unchanged):
- GET /api/rules/catalog: administrator-only rule configurations.
- PUT /api/rules/catalog/{code}: administrator-only body {version, enabled, points, matchValues}; use the latest version from GET. matchValues is a newline-delimited string, empty for non-list checks.
- POST /api/transactions: additionally accepts optional ipAddress and phoneNumber. All previous required fields and the Idempotency-Key header remain supported.

Flyway V2 adds the rule catalog and nullable transaction signal fields without clearing existing data. Deploy the updated API and web images together. Tests: Java package and React production build passed; scripts/smoke.mjs and scripts/rules-smoke.mjs passed against Docker. The rules test creates synthetic transactions, restores the original rule configurations, and leaves their audit/version history intact. Browser verification covered rule visibility, merchant submission through scoring, night mode and a 390px phone viewport. Container images were rebuilt locally; publishing and Kubernetes rollout are separate steps.


## Management email alerts

Administrators can now open **Email alerts** to manage recipients, select severity, pause/resume email, send a test and inspect retry/delivery status. SMTP uses server-side environment credentials and a durable database outbox. See [email setup and operations](docs/EMAIL_ALERTS.md). Pause automatic notifications before running synthetic fraud smoke tests. Local Gmail sending was verified; Kubernetes SMTP secrets still need to be provisioned in the target cluster.

## Rule Lab

Administrators can now detect repeated equal-amount payments, preview historical impact, and observe a rule in shadow mode before activation. Open **Rule Lab** in the navigation. The new rule starts Off. See the [demo steps, API and evaluation boundaries](docs/RULE_LAB.md).
