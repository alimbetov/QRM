# ADR-002 — Spring MVC + Spring Data JPA as the QRM backend baseline

**Status:** ACCEPTED — architecture baseline approved by product owner on 2026-11-12; documentation and runtime implementation pending.
**Date:** 2026-11-12
**Scope:** R0/R1 backend architecture; no runtime code exists in the reviewed repository.

## Context and decision rationale

QRM is a QR-first marketplace with transactional negotiations, order conversion, stock reservation, immutable pricing, cancellation, receipts and a PostgreSQL transactional outbox. ADR-001 proposes WebFlux + R2DBC, but its proof spike has not been run. Prioritize transactional correctness, operability, and familiar JPA tooling rather than assuming reactive throughput gains.

## Approved technology stack

- React + TypeScript: public QR storefront and Seller Web Cabinet; no server-rendered HTML.
- Flutter: buyer and seller mobile app on Android/iOS.
- Java 21 LTS; pin an actively supported, compatibility-verified Spring Boot release when bootstrapping.
- Spring MVC + Spring Data JPA/Hibernate + PostgreSQL JDBC + HikariCP.
- Enable virtual threads as a **separately measured option**, not a throughput guarantee. Blocking JDBC still consumes database connections.
- Use Spring Security servlet stack, Bean Validation, MVC REST/OpenAPI, Micrometer and Actuator.
- Liquibase is deferred for R1; local development uses Hibernate `ddl-auto=update`, tests use reproducible PostgreSQL schema initialization, and production uses `ddl-auto=validate`. **No automatic production schema changes.** Before public deployment, institute versioned reviewed SQL migrations (Liquibase or equivalent), backup/rollback procedures and a schema consistency gate. Database constraints, indexes and SQL remain authoritative.
- Use a modular monolith with domain/application/infrastructure boundaries. Domain rules are plain Java. Use DTOs at HTTP boundaries, no exposure of JPA entities.
- Use `@Transactional` service-level units for state transitions and Order + Outbox atomic persistence. No remote I/O, media uploads, or notifications within the DB transaction.
- Design repository methods with explicit fetch strategy, no Open Session in View, pagination, projections and optimized SQL for read-heavy storefront endpoints.
- PostgreSQL is the source of truth for stock: conditional atomic `UPDATE inventory SET available=available-:q, reserved=reserved+:q WHERE sku_id=:id AND available>=:q`, check affected rows, in same DB transaction as reservation/Order/Outbox. Idempotency keys + uniqueness guard duplicate order conversion.
- Use `BigDecimal` monetary values, explicit KZT policy and immutable pricing snapshot; legal/product decision OPEN-03 must be closed.
- Outbox worker is independently retryable and idempotent; use batched claiming with PostgreSQL locking and bounded concurrency; keep external delivery outside the order transaction.
- Redis is an **approved deferred caching component**, not an R1 infrastructure dependency. Introduce Spring Cache + Redis in a later measured milestone for public catalog/category/read-model caches, with explicit TTL, event-based invalidation and tenant/key isolation. PostgreSQL remains the only source of truth; order, stock reservation, negotiated price and other authoritative transactional decisions always use PostgreSQL. Cache outage must not compromise order correctness. Kafka, distributed sagas and partitions remain deferred pending measured need.
- Keep clean architecture boundaries so a future reactive adapter can be benchmarked without rewriting domain invariants.

- Application business timezone: `Asia/Qyzylorda` (owner-selected), applied explicitly in business rules. Persist timestamps as UTC instants and set Hibernate JDBC timezone UTC; never infer timezone merely from a custom property.
- Test strategy: H2 in-memory for quick Docker-free tests of basic JPA mappings/CRUD and service logic, plus **mandatory PostgreSQL Testcontainers** for native queries, constraints, transactional correctness, isolation and concurrency (especially stock reservation and Order + Outbox). H2 is never a substitute for PostgreSQL acceptance.
- Initially use Hikari maximum-pool-size=5, minimum-idle=1 for local development, to be measured and tuned before load qualification. Keep `spring.jpa.open-in-view=false`.
- Logback rolling files: 200MB, 7-day retention, requestId MDC where present; Logbook TRACE only in local/debug profile with PII masking. Avoid request/response body logging in production.

## Alternatives

1. MVC + JPA on platform threads: simplest control baseline.
2. MVC + Virtual Threads + JPA: preferred candidate if benchmarks and tracing show a gain.
3. WebFlux + R2DBC: retains high-concurrency nonblocking design but adds reactive persistence and testing complexity; not rejected forever, must prove a material benefit.

## Required objective gate

Build three **separate comparable prototypes**, same PostgreSQL version/schema/indexes, semantically identical SQL and transaction isolation, CPU/memory limits, connection budgets and load shape (k6 5,000 VUs, order creation + stock reservation).
Collect RPS, p95/p99, error rate, CPU, memory, connection pool waits, lock waits, DB saturation, order correctness and double-reservation incidents. Include warmup, steady-state, ramp and overload. No claim that any approach is faster before measurement.

## Proof tests before implementation approval

- PostgreSQL Testcontainers: concurrent same-SKU reserve; no negative balance, no overselling.
- Identical idempotency key retried/concurrently: exactly one order, stable response or documented conflict.
- Order and Outbox committed atomically; failed Outbox insert rolls both back.
- Negotiation accepted-offer consumed at most once; only authorized buyer converts; immutable price snapshot.
- Seller/Buyer cancellation races lead to a single legal transition.
- Lock timeout/deadlock/retry strategy bounded and safe; failures observable.
- Query-count assertions for N+1 and keyset/page sizing; external calls are not performed within SQL transactions.
- Compare baseline MVC vs virtual-thread config and WebFlux+R2DBC proof before final runtime tuning.

## Documentation impacts

In the follow-up documentation alignment, replace reactive-specific stack, testing and architecture language in ARCHITECTURE.md, TEST-PLAN.md, IMPLEMENTATION-PLAN.md, API-SPEC.md and related docs. Retain ADR-001 as historical rationale, mark SUPERSEDED. Replace reactive `StepVerifier/WebTestClient/TransactionalOperator` and 'no JPA' ArchUnit rules with `MockMvc`, JPA/Testcontainers, `@Transactional`, and no domain-to-JPA coupling checks. Do not silently rewrite business decisions or declare implementation complete.

## Decision owner / acceptance

Product owner approved the technology choice on 2026-11-12. Independent S2 review still required for executable tests/implementation. This ADR is documentation only.
