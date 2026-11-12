# ADR-001 — Reactive Backend Architecture

**Status:** SUPERSEDED by [ADR-002](ADR-002-MVC-JPA.md) on 2026-11-12. Retained as historical proposal, not the implementation baseline.  
**Date:** 2026-11-12

## Context

QRM expects concurrent QR listing reads, pricing, buyer/seller trading flows and async events. Existing documentation used Spring Data JPA and Java 17; user prefers R2DBC. Framework choice must not weaken transactional constraints.

## Decision proposed

- Java **21 LTS** (proposed baseline; deployment Java compatibility to verify).
- Spring Boot supported 3.x line, **Spring WebFlux**.
- PostgreSQL via **Spring Data R2DBC**, reactive PostgreSQL driver, R2dbcEntityTemplate/DatabaseClient for tailored SQL.
- **No Spring Data JPA/Hibernate** in request path. No blocking JDBC on event loop.
- Liquibase can run via JDBC as an **isolated startup/migration step**, outside reactive handling.
- Spring Security reactive, REST/OpenAPI, Reactor Test/StepVerifier, WebTestClient.
- TransactionalOperator for use cases requiring multiple DB writes, including negotiated Order creation + Outbox.
- Domain business rules stay plain Java, without mandatory Reactor types; application adapters can expose Mono/Flux.
- Nonreactive SDK (S3 storage, media processing, translation, FCM) executes via bounded async workers or nonblocking clients; do not block Netty event loop.
- PostgreSQL indexes, isolation levels, optimistic version checks, conditional SQL and uniqueness constraints are required for concurrent state machines.
- Connection pool sized and measured against actual PostgreSQL budget.

## Alternatives

| Alternative | Advantages | Limitations |
|---|---|---|
| Spring MVC + JPA | mature persistence, straightforward transactions | blocking request threads, different chosen strategy |
| WebFlux + JDBC/JPA | easy migration | undermines nonblocking request model unless carefully isolated |
| **WebFlux + R2DBC** | reactive end-to-end SQL IO | manual aggregates/joins and more explicit transaction/retry design |

## Proof before R1

Create dedicated technical spike with real PostgreSQL Testcontainers:
1. Run Liquibase schema from migration process, boot WebFlux app.
2. Pricing query + transaction test in R2DBC.
3. Accept an offer; atomically create one Order and one Outbox event.
4. Competing accept/reject and duplicate create-order requests show deterministic 409/idempotency.
5. Confirm rollback if Outbox write fails.
6. Simulate notification outage; order remains valid and Outbox retries.
7. Measure p95 under documented concurrency and check event-loop blocking.

## Consequences

R2DBC is a technology choice, not a performance guarantee. Team must maintain SQL/query quality and reactive execution discipline. Final runtime version and library compatibility are checked during implementation.
