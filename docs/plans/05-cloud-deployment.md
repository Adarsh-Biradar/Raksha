# Plan: Alternative cloud deployment

## Goal
Deploy the existing Docker/Kubernetes setup to a managed cloud platform (AWS/Azure/GCP), since the
problem statement credits this only as an *additional* deployment beyond an initial
Railway/Render/self-hosted target. This plan should run **last** — it packages the outcomes of
the other 6 plans (messaging, observability, scalability, multi-tenant, payment gateway, AI).

## Current state
- `k8s/` already has manifests: `namespace.yaml`, `postgres.yaml`, `api.yaml`, `web.yaml`,
  `configmap.yaml`, `ingress.yaml`, `kustomization.yaml`, plus a `setup-secrets.ps1` /
  `setup-smtp.ps1` helper and `k8s/README.md`.
- `docs/README.md`: "Validation: Compose configuration, Kubernetes manifest rendering, and the
  secret helper's PowerShell syntax passed... No images were pushed and no cluster deployment was
  performed; this machine had no configured Kubernetes context."
- Images already build to `adarshbiradar/raksha-api`/`raksha-web` on Docker Hub via the Jenkinsfile.
- Postgres currently runs as an in-cluster pod (`k8s/postgres.yaml`) with no managed-DB story.

## Steps

### 1. Pick a target
Cheapest/fastest path for a hackathon judge demo, in order of setup effort:
1. A managed Kubernetes service the team already has credits/access for (AKS/EKS/GKE) — reuses
   all existing `k8s/*.yaml` manifests almost as-is.
2. If full K8s setup is too slow given remaining time: a simpler container platform (Azure
   Container Apps, AWS App Runner/ECS Fargate) running the same Docker images directly, skipping
   the K8s manifests for this deployment target while still keeping them for the primary
   submission.
Pick based on what cloud credits/CLI access the team already has — don't spend hackathon time on
account setup.

### 2. Managed database
- Replace `k8s/postgres.yaml`'s in-cluster pod with a managed Postgres instance (RDS / Azure
  Database for PostgreSQL / Cloud SQL).
- Run the existing Flyway migrations (`backend/src/main/resources/db/migration`) against it once
  on first deploy — no migration content changes needed, just a different connection target.
- Update `k8s/configmap.yaml` / secrets with the new `DATABASE_URL`.

### 3. Secrets
- Move off the local `.env` + `k8s/setup-secrets.ps1` pattern for this target: use the cloud
  provider's secret manager (AWS Secrets Manager / Azure Key Vault / GCP Secret Manager) and
  inject via CSI driver or native K8s secret sync, rather than committing generated secrets.
- Carry over `DEMO_PASSWORD`, `DATABASE_PASSWORD`, `SMTP_*`, and any new secrets from the other
  plans (Stripe keys, LLM API key, RabbitMQ credentials) into the same secret store.

### 4. Networking / HTTPS
- Add a managed ingress or provider load balancer in front of `k8s/ingress.yaml`'s existing rules,
  with a cert-manager-issued or provider-managed TLS certificate.
- Set `COOKIE_SECURE=true` (already a documented requirement in `docs/ARCHITECTURE.md`'s
  Deployment section: "For remote use, provision a VM, restrict ingress, use a trusted HTTPS
  reverse proxy and set COOKIE_SECURE=true").
- Confirm `BIND_ADDRESS`/ingress rules don't accidentally expose Postgres or the admin actuator
  endpoints externally — the architecture doc is explicit that these must stay internal.

### 5. CI/CD extension
- Extend the existing `Jenkinsfile` (Kaniko build/push stages) with a deploy stage targeting the
  new cluster's kubeconfig (stored as a Jenkins credential), running `kubectl apply -k k8s/` or
  the provider CLI equivalent, gated behind a manual approval step for a hackathon demo.

### 6. Documentation
- Update `k8s/README.md` with the cloud-specific steps actually run (not hypothetical) and the
  live URL, per the existing documentation style in this repo (the README and ARCHITECTURE docs
  consistently distinguish "done" from "documented as future work" — keep that honesty here too).

## Testing
- Run the full `scripts/smoke.mjs` suite against the cloud-deployed URL, not just locally.
- Verify HTTPS redirect, secure cookie flag, and that Postgres/actuator ports are unreachable from
  outside the cluster (e.g. `curl` from outside, confirm connection refused/timeout).
- Load-test (reuse `scripts/loadtest.js` from the scalability plan) against the cloud deployment
  to get real numbers, not just local-machine numbers, for the scalability writeup.

## Risk / rollback
- Do this last and keep the original Compose/local-K8s deployment as the primary, always-working
  fallback for the demo — cloud account issues, quota limits, or DNS propagation delays are common
  and shouldn't jeopardize the core submission.
- Tear down cloud resources after judging to avoid ongoing cost (managed DB and load balancers are
  the main cost drivers) — document the teardown commands alongside the deploy commands.
