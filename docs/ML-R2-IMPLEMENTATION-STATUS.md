# ML-R2 — executable PostgreSQL trade journal and invitation eligibility

**Date:** 2026-11-14. **Branch:** `docs/adr-002-jpa-architecture-review`.
**Release status:** Candidate GREEN verified by CI, **independent S2 NOT APPROVED**, external notification consent and delivery NOT implemented.

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
| Hardening | `7c3f9c0f607242ab33462a0e22777d1220958a54` | Added transactional rollback and end-to-end HTTP commerce event assertions; exact hardening commit CI must pass before final acceptance |

**PostgreSQL negative scenarios covered:** direct SQL duplicate decision rejection, append-only DELETE/UPDATE, duplicate revision, expired demand, inactive/non-member seller, DRAFT-only/non-matching SKU, absent destination fulfillment, concurrent 6-worker decision replay, and distinct policy versions. The hardening tests extend verification of a failed transaction producing **zero events** and one BID_ACCEPTED/ORDER_CONFIRMED event despite repeated seller confirmation.

## Remaining ML-R2 acceptance blockers

1. **Buyer notification/marketing consent** and seller notification preferences are not present. Do not automatically dispatch messages based on an `ELIGIBLE` candidate.
2. Add source event consumer/delivery ledger for actual at-least-once processing, duplicate conflicting IDs, crash between dispatch and ACK, retries and dead letters. Current local event journal is transactionally durable, **not a live messaging pipeline**.
3. Real schema migration in staging, DB write throughput/latency and contention tests with concurrent bids/confirmations; 5,000-VU commerce load and correctness remain open.
4. Harden exposure: no public ML-R2 invitation endpoint, do not trust client seller IDs or consent booleans; restrict SQL write privileges, auditor access, tenant scoping and retention.
5. Independent S2 Review of migration/triggers, security, data lineage, temporal semantics, concurrency tests, worker failure modes and exact SHA.

**Next milestone:** complete ML-R2 event replay/delivery and consent policy before classifying R2 as fully complete. Then ML-R3 PostgreSQL point-in-time Dataset Builder may consume these immutable authoritative facts, with `occurred_at` and `ingested_at` controlled separately and no future leakage.

**ML-R3…ML-R6 remain planned, not implemented.**
