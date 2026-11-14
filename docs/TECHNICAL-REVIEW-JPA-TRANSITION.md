# QRM architecture review — JPA transition (2026-11-12)

**Reviewed:** `main` HEAD `7811211529fbd75e5949a5a1d394324697f7e9bb` and `docs/consolidated-requirements-v1-2` at `7ac4e986a7e9b52c77a1942eef98710626d0be7c`.

**Scope & limits:** static repository and specification review. `main` contains README only; docs branch has requirements, ADR, scenario and review documents but no executable Spring backend, migrations, tests or load evidence. Thus no JPA dependency replacement or tests can actually be executed yet.

## Findings

| ID | Priority | Finding | Action / gate |
| --- | --- | --- | --- |
| A-01 | P0 | WebFlux/R2DBC is only a proposed ADR with no performance or correctness spike | Propose ADR-002; choose architecture on proof, not assumed speed |
| A-02 | P0 | No executable backend, SQL migrations or tests in reviewed branches | R1 bootstrap MVC/JPA skeleton + Liquibase + PostgreSQL Testcontainers |
| A-03 | P0 | Same-SKU concurrency, order idempotency and Outbox atomicity unproven | Conditional stock SQL, uniqueness constraint, race/rollback integration tests |
| A-04 | P0 | Unverified-phone alias squatting and recovery policy remain release blockers | Independent identity contract review; no phone possession inference |
| A-05 | P0 | KZT pricing policy and rounding, quote expiry, immutable Order snapshot need ratification | Explicit monetary contract and boundary tests before public orders |
| A-06 | P0 | Seller READY != buyer RECEIVED and disputes/receipt proof are release-critical | Explicit state machines, OTP/QR anti-replay, manual dispute path |
| A-07 | P1 | Reactive-specific dependencies/rules repeated across docs | Follow-up consistency sweep after ADR acceptance |
| A-08 | P1 | Query scalability, JDBC pool pressure, N+1 and locking load profiles undocumented in executable form | Metrics, bounded pools, query plans, p95/p99 + correctness in 5,000-VU test |
| A-09 | P1 | Nightly read-consistency and stale-worker fencing decisions open | Repeatable-read or committed journal approach; recovery tests |
| A-10 | P1 | PII, data retention and Kazakhstan compliance need approval | Security/legal gate before launch |

## Target technical structure

```text
backend/
  bootstrap/
  modules/
    identity/
    seller/
    catalog/
    pricing/
    negotiation/
    ordering/
    inventory/
    fulfillment/
    notification/
  common/
  db/changelog/
  tests/
```

Modules should have API/application/domain/infrastructure separation; DB transaction boundaries live in application services, not HTTP controllers. Cross-module invocation stays in-process and domain-independent. No extra message broker at R1.

## Execution plan and evidence

1. **R0 / ADR:** ratify ADR-002 and outstanding P0 business rules; update all specs consistently.
2. **S0:** define invariants/acceptance oracles, explicit reservation expiry/release and stock adjustment semantics.
3. **S1 RED:** implement Testcontainers PostgreSQL concurrency, idempotency, transaction rollback, authorization and pricing tests; demonstrate expected failures.
4. **S2 Review:** independent test/architecture sign-off.
5. **S3 GREEN:** implement MVC/JPA vertical slice: accepted negotiation → buyer explicit order → conditional inventory reserve → Outbox.
6. **S4:** regression, race/stress, query budgets, lock/cpu/memory profiles; compare MVC/JPA with and without Virtual Threads and R2DBC counterpart under common constraints.

**Do not** label any gate PASS absent test execution evidence (CI URL, SHA and logs). Do not merge to main before documentation consistency and S2.

## Review verdict

**Architecture proposal: proceed to ADR review. Production readiness: NOT QUALIFIED.** Runtime migration: not applicable because backend implementation has not started.
