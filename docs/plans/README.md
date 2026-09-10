# Implementation plans — Advanced Engineering gaps

These plans cover the 7 "not done" items from the FraudShield/Raksha problem statement's
Advanced Engineering Challenges list. Each plan is self-contained: goal, current state,
step-by-step changes with concrete files, testing, and rollback/risk notes.

Suggested build order (each hardens the base before the next adds new surface area):

1. [Observability](03-observability.md)
2. [Messaging (RabbitMQ)](02-messaging-rabbitmq.md)
3. [Scalability](04-scalability.md)
4. [Business expansion (multi-tenant + billing)](07-business-expansion.md)
5. [Payment gateway](06-payment-gateway.md)
6. [Advanced AI](01-advanced-ai.md)
7. [Alternative cloud deployment](05-cloud-deployment.md)

Each plan assumes the current stack: Java 21 / Spring Boot 3.5, React 19 / TypeScript / Vite,
PostgreSQL 16, Docker Compose, Kubernetes manifests under `k8s/`, Jenkins/Kaniko CI/CD.
See `docs/ARCHITECTURE.md` for the existing system flow and known limitations before starting.
