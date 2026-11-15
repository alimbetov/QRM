# R2.1 Hardening and R2.2 Inventory Lifecycle — qualification record

**Date:** 2026-11-13. **Branch:** `docs/adr-002-jpa-architecture-review`, Draft PR #2, **not merged**.
**Independent S2 status:** OPEN / NOT APPROVED. This document is a development qualification record, not independent review.

## R2.1 Hardening (V12–V13)

- **V12 sealed orders:** Adds `customer_order.expected_line_count` and populates it from existing line count. Requires positive count, freezes it from later alteration. A serialized `order_line BEFORE INSERT` trigger prevents inserting more than the declared count or adding lines after PLACED. A DEFERRABLE PostgreSQL trigger ensures at COMMIT that `SUM(line_total)+delivery_fee == total_amount` and exact declared cardinality. Normal JPA `CustomerOrder` now initializes expectedLineCount from immutable draft lines before persistence; existing direct SQL test fixtures write order header and lines in one transaction. Existing migration of incomplete orders is **fail-closed**, not silently corrected.
- **V13 seller/stock references:** PostgreSQL rejects balance rows whose seller is not the real owner of both warehouse location and SKU listing. Reservation trigger checks order seller, order line ID/order ID/SKU/quantity and presence of the matching `InventoryBalance`. Seller ownership on catalog listing/location and inventory identities cannot be silently reassigned via raw UPDATE.
- **Test evidence:** `SealedOrderLinesPostgresIT` tests initial line insertion, late append rejection and incomplete transaction rejection; `InventoryRelationalIntegrityPostgresIT` tests foreign warehouses/SKUs and mismatched order-line reservations; `OrderLineIntegrityPostgresIT` validates economic fields and allowed status transitions. R2.1 green baseline `580ffa2b56cba690163d5978fbe03cd9ae0bbf0f` (Java 21 `test`, PostgreSQL `verify`, Buyer Web UI PASS).
- **Pure-Java ML correctness:** `WeeklyIntelligenceAggregator.Window.complete()` requires seven known days per requested week, not merely presence of an ISO week row. `AdaptiveSufficiencyCalibrator` compares competing candidates only over identical cohort/period populations, otherwise returns conservative policy with `INCOMPARABLE_COHORTS`. Business config time zone aligned to `Asia/Almaty`.

## R2.2 initial vertical slice (V14)

- **Audited movement ledger:** immutable `InventoryMovement` with `RECEIPT` / `RESERVATION` / `RELEASE`, seller/location/SKU/optional order references, signed on-hand and reserved deltas, actor issuer+subject, source key, occurrence timestamp. Unique `(seller_id,kind,source_key)` prevents duplicate logical movements. PostgreSQL CHECK validates operation-specific delta signs. UPDATE/DELETE prohibited by trigger.
- **`InventoryStockService.receive`:** internal server-authorized SELLER_ADMIN receipt on an existing provisioned seller-owned location/SKU balance, with a row lock. Same idempotency key and matching request returns prior event ID, conflicting key data fails. Receipt increments on-hand exactly once, in the same transaction as the movement. **This is seller-declared stock, not WMS-verified stock; no public intake API.**
- **`OrderConfirmationService`:** in addition to existing reservation + order status + Outbox atomicity, now creates immutable `RESERVATION` movement records per order line inside the same transaction.
- **`OrderCancellationService.cancelBySeller`:** verified SELLER_ADMIN, per-order pessimistic lock, supports PLACED or CONFIRMED before processing. PLACED has no reserves to release; CONFIRMED releases each RESERVED line under sorted SKU balance locks, writes corresponding `RELEASE` movement, sets `CANCELLED_BY_SELLER` and inserts `ORDER_CANCELLED_BY_SELLER` Outbox, all in one DB transaction. Repeated cancellation on already seller-cancelled order returns previous result and doesn't double release.
- **V14 migration guard:** refuses to apply automatically to a database already containing inventory reservations, because their provenance cannot be invented. Environments with existing transactions need an approved reconciliation/backfill runbook and separate migration strategy. Do not disable the guard merely to force deployment.
- **RED/GREEN:** `OrderConfirmationPostgresIT` covers repeated receipt with same key, changed-key-payload conflict, seller authorization, confirmation+cancel+release/Outbox, cancellation of PLACED, concurrent duplicate cancellation, and full rollback when Outbox unique conflict is injected. R2.2 baseline source `ec710ce7af71b5330630396eac210f86bdde4e9f` passed Backend `test`/`verify` and Buyer Web UI. Latest supplemental regression commit `fc5d7ce6e97d8d4b2b2d810c1bb91d36aaf9a611` needs its own exact-SHA CI verification.

## Outstanding P0 gates — NOT DONE

1. **Independent S2 Review** with reviewed SHA, signed JWT/JWKS and real tenant onboarding, DB/schema transition rehearsal; Draft PR stays unmerged.
2. **Stock source:** no supplier/WMS feed, opening-balance provenance, warehouse reconciliation, authoritative stock adjustment reason codes, cycle count, partial shipment/return/receipt support.
3. **Buyer cancellation** and customer-initiated dispute, late cancellation after PROCESSING, refund and POD — not implemented; seller cancellation is limited to PLACED/CONFIRMED.
4. **Outbox delivery worker:** rows are durable but no multi-pod claim/lease/fencing, dispatcher, retries, DLQ, consumer idempotence or actual delivery yet. Next workstream is PostgreSQL `SKIP LOCKED` worker + bounded concurrency with failure injection and distinct worker deployment. Redis is not needed for the initial protocol.
5. **5K concurrent users:** real authenticated API, p95/p99, RPS, pool/locks/deadlocks, error rates, inventory invariants across multiple pods, online SLO under nightly load — not measured.
6. **Price/fulfillment approval:** tax, delivery reliability, returns/refunds, KZT rounding business rules, durable receipts, QR-first guest buyer and live React catalog integration are pending.

## Next acceptance gates

- R2.2B: RED→Test Review→GREEN PostgreSQL multi-pod-safe Outbox dispatcher, idempotent consumer, backoff/dead-letter and dashboard. Keep fixed low worker count initially; no Redis/Kafka by default.
- R2.3: real JWT buyer/seller HTTP flow + QR landing + exact order/stock journey; separately qualify guest ordering / registered buyer.
- R2.4: Testcontainers multi-process concurrency and k6/Gatling 5K controlled run, formal independent S2 code/security and operational qualification.

**Release verdict:** production blocked. The described changes strengthen internal transactional foundations only.
