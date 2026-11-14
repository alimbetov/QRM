# QRM — Single active development branch policy and Smart Deal reconciliation

**Current workflow (2026-11-13):** `main` is protected conceptually as release/base; **all active specs + backend + frontend development live in `docs/adr-002-jpa-architecture-review`** until an explicitly approved change to branching strategy. No other feature branches should receive new work. Existing Draft PR #2 remains the review gate.

## Consolidation
- Preserved from `docs/smart-deal-r2`: [Smart Deal feature requirements](SMART-DEAL-R2.md) and [20-finding technical audit](SMART-DEAL-R2-AUDIT.md); this is **documentation transfer**, not a source merge.
- Excluded: `smartdeal-contract-tests/` detached PostgreSQL-only fixture and `SMART-DEAL-R2-S1-DB.md`, which duplicate/contradict the JPA/Flyway V1–V11 implementation and must not be promoted.
- The user's adopted stack: **Java 21 + Spring MVC + Spring Data JPA + Hibernate + PostgreSQL, Flyway, Testcontainers**, `@Transactional` business operations.
- Business pipeline is already partly implemented: demand/bid snapshots and immutable revisions (R2.0A), accepted bid and `CustomerOrder(PLACED)` (R2.0B), stock reservation + Outbox on seller confirmation (R2.1). Continue **delta implementation**, not a second vertical stack.
- IMPORTANT: Smart Deal's draft `SELECTED` state, candidate matching, explicit seller invitations, buyer bid comparison and additional UX flows are **future proposals** until they are reconciled with existing `BidAcceptanceService`, `DealPersistenceService`, `OrderConfirmationService`, existing multi-line demand/bid, Flyway naming and S2 authority. In particular, do not insert any additional pre-acceptance status transition without validating historical accept/Order semantics.

## Next qualification sequence
1. Inventory existing code entities and migration V1–V11 by exact commit SHA. No duplicate demand, bid, order or inventory schema.
2. Produce a delta matrix for the 52 Smart Deal oracles: `already tested`, `covered partially`, `missing`, `conflicts with implemented contract`.
3. New `MockMvc` + `@SpringBootTest` + PostgreSQL Testcontainers JPA RED tests only for gaps: auth/ABAC of invite & offers, deterministic PUBLIC-only matching, idempotent delivery of invitation, buyer selection + existing acceptance path, money total/quote, reservation/Outbox transaction, privacy and downstream receipt.
4. Independent S2 Review of changes and existing PR #2. Do not claim S2 approval from passing CI.
5. GREEN implementation, exact-SHA CI and E2E qualification. Preserve `main` unchanged until review.

## Branch cleanup

Obsolete `docs/smart-deal-r2` may be deleted **after this documentation is confirmed on the canonical JPA branch**; its distinct DB fixture is intentionally not retained as production work. Git history is not copied to the canonical branch; only the reviewed document contents are consolidated. Do not delete `main` or `docs/adr-002-jpa-architecture-review`. Once the obsolete ref is removed, GitHub may still retain closed PR or commit records.

**Note:** This document is a project policy, not evidence that the GitHub branch ref has been deleted. 
