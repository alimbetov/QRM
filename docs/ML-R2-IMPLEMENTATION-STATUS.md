# ML-R2 — executable PostgreSQL trade journal and invitation eligibility

**Date:** 2026-11-14. **Branch:** `docs/adr-002-jpa-architecture-review`.
**Release status:** ML-R2 transactional event journal + *internal* at-least-once delivery and opt-in invitation inbox implemented, PostgreSQL CI PASS; **independent S2 NOT APPROVED**, production worker OFF, real Push/SMS/email NOT implemented.

## Implemented

- **Flyway V24**: `ml_trade_event` append-only journal with authoritative DB triggers. Trade transitions are captured **inside the business transaction**, so failed/rolled-back commerce writes cannot leave ghost ML labels. Events: `DEMAND_CREATED`, `BID_SUBMITTED`, `BID_REVISED`, `BID_ACCEPTED`, `ORDER_CONFIRMED`, `SELLER_INVITATION_EVALUATED`. Each event has event_id, aggregate_type/id, source_revision, demand_id, optional seller_id, schema_version=1, occurred_at and ingested_at. **No JSON payload, email, phone, buyer IP or private address**. Database CHECK/UNIQUE and immutable triggers forbid duplicate business revisions and historical changes.
- **Invitation decision**: `ml_seller_invitation` with `UNIQUE(demand_id,seller_id,policy_version)`. It stores only `ELIGIBLE/MATCHED` or `SUPPRESSED` with reason. Invitation history is append-only and represents **evaluation**, not authorization to send a notification.
- **`MlSellerInvitationService`**: internal Spring service behind `zakaz.ml.r2.invitation-evaluation.enabled=false`. Reads existing BuyerDemand and active SellerMembership, PUBLIC Listing, active Catalog HCC Category, tracked SKU matching the requested product/unit and delivery area or enabled local pickup. It refuses expired/accepted demands. Uses PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` and then loads the canonical decision to provide concurrency-safe idempotency.
- No duplicate HCC or AttributeRegistry, no new broker and **no reuse of `order_outbox_event`** (its aggregate ID is constrained to CustomerOrder). No seller notification/push is sent, even for `ELIGIBLE`.

## Tests and evidence

| Step | Exact GitHub SHA | Evidence |
| --- | --- | --- |
| RED | `d7fa336ba6d42d487220ef06fd0aa29754721888` | PostgreSQL CI failed with `relation "ml_trade_event" does not exist` (3 expected missing-schema errors) |
| GREEN | `d3c73b2220014d471d14c05172111458d31b2f13` | GitHub Actions all 4 workflows SUCCESS, Java 21 Maven `verify` BUILD SUCCESS, 80 PG integration tests, 0 failures; new ML trade 3 and invitation 4 tests PASS, Flyway V1–V24 applied |
| Hardening | `7c3f9c0f607242ab33462a0e22777d1220958a54` | Transactional rollback and HTTP event assertions; all four CI workflows PASS |
| Delivery RED | `af1c3150211fdd6e268f44880956a23081cc8f86` | Backend expected compile failure: event queue/consumer not yet present |
| Delivery GREEN | `c4abe58a85345353f74c5a3a8ab4c7d0da48fe1c` | Four workflows PASS, 88 PostgreSQL IT PASS; V25 migration |
| Delivery hardening | `5bc001531863f605f27831717676bd47578c372c` | Four workflows PASS, 92 PostgreSQL IT PASS, 0 errors, 89.04% line / 63.33% branch coverage |

**PostgreSQL negative scenarios covered:** direct SQL duplicate decision rejection, append-only DELETE/UPDATE, duplicate revision, expired demand, inactive/non-member seller, DRAFT-only/non-matching SKU, absent destination fulfillment, concurrent 6-worker decision replay, and distinct policy versions. The hardening tests extend verification of a failed transaction producing **zero events** and one BID_ACCEPTED/ORDER_CONFIRMED event despite repeated seller confirmation.

## ML-R2.1 — reliable internal delivery and consent (2026-11-14)

- **RED `af1c3150211fdd6e268f44880956a23081cc8f86`:** PostgreSQL delivery tests refer to the absent queue and projection sink; Backend CI expected RED.
- **GREEN `c4abe58a85345353f74c5a3a8ab4c7d0da48fe1c`:** Flyway `V25__ml_event_delivery_and_consent.sql` creates a per-event PostgreSQL queue, transactional consumer ledger, buyer-demand sharing consent, seller in-app preference, private seller inbox; migration replays actual V24 trade events only, no invented time history. All four CI workflows PASS; Java21 `mvn verify` BUILD SUCCESS, 88 PostgreSQL IT tests, zero failures, V1–V25 applied.
- **`JdbcMlEventQueue`:** bounded cross-pod `SKIP LOCKED` claims, per-demand event ordering, fencing by lease token + monotonically increasing claim generation, ACK only if lease valid, exponential retry/backoff, expired claim recovery, DEAD_LETTER terminal state and per-status health.
- **`JdbcMlEventProjectionSink`:** event fingerprint + unique consumer/event inbox written in a transaction, with internal invitation-inbox projection in that SAME transaction. If the consumer commits then dies before queue ACK, retry is harmless; conflicting fingerprints fail closed.
- **`MlEventDispatcher`:** ACK after sink commit, sanitized error names on failure. Internal-only scheduler is **OFF by default** (`zakaz.ml.r2.delivery.enabled=false`), external webhook, Push, email and SMS are NOT called.
- **Consent boundary:** only a linked buyer can toggle sharing of their demand; only authenticated `SELLER_ADMIN` can toggle seller in-app invitations/read inbox. Both must be explicitly enabled. Missing or revoked consent suppresses access. Public/listing/active membership/geography/expiration and prior acceptance are rechecked at delivery AND read. HTTP consent/inbox endpoints are **OFF by default** (`zakaz.ml.r2.consent-api.enabled=false`). Candidate evaluation stays internal and OFF (`zakaz.ml.r2.invitation-evaluation.enabled=false`).
- **Tests:** producer rollback, two-worker claim race, stale ACK/lease recovery, sink-before-ACK replay, retry/dead letter, buyer+seller opt-in, endpoint ACL/revocation, causal ordering, conflicting fingerprints and one-sided opt-in. **Hardening SHA `5bc001531863f605f27831717676bd47578c372c`: all four GitHub CI workflows SUCCESS; Java 21 PostgreSQL `mvn verify` BUILD SUCCESS, 92 IT tests, 0 failures/errors, Flyway V1–V25. JaCoCo: 89.04% lines and 63.33% branches.**

## Required gates before production or ML-R3 qualification

1. **Independent S2 Review on exact SHA** for V24/V25 migration, temporal semantics, role/consent checks, privacy, event replay and ACLs. Neither automatic CI nor this author is an independent APPROVE.
2. **Real staging multi-pod worker UAT:** worker restarts, PG outage, lease contention, simultaneous policy revoke, snapshot retention, dead-letter alerting, controlled audited redrive and backup/restore. No real multi-pod evidence yet.
3. **Actual outbound notification/push integration** is deliberately NOT included. If enabled later it needs channel-specific delivery/outbox, seller device tokens, consent/quiet hours, retry ACK from provider and separate E2E. An `ELIGIBLE` candidate is never permission to push.
4. **Performance and privacy:** 5K load gate, delivery backlog/lag, slow consumer/connection-pool contention, row-retention policy, database least privilege, time skew and revocation races require qualification.
5. **ML-R3 prerequisite:** temporal datasets must use immutable `ml_trade_event` and consumed event evidence, with separate `occurred_at` / `ingested_at` / `applied_at`, no late-feature leakage and no duplicated labels.

**Migration numbering:** V23 media audit, V24 ML-R2 event journal, V25 ML-R2 internal delivery/consent. SEARCH Favorites must start **V26 or next free version**, and category-watch/Push at V27+.

**The internal ML-R2 delivery slice is implemented and CI-green; the full product release remains HOLD** until external staging/independent review. ML-R3…ML-R6 are not implemented.
