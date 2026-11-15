# R2.1 — Transactional Inventory Reservation on Seller Confirmation

**Status:** internal Java/JPA + PostgreSQL Flyway V10/V11 implemented in PR #2; production approval pending independent S2 and full transaction/operational review.

## Transaction boundaries

```
verified Seller JWT + SELLER_ADMIN membership
    → SELECT customer_order FOR UPDATE
    → verify PLACED and immutable BidAcceptance/Quote linkage
    → verify active seller origin and current catalog SKU facts
    → SELECT inventory_balance FOR UPDATE (ascending SKU key)
    → available = on_hand - reserved; reserve all lines or nothing
    → insert InventoryReservation per order line
    → CustomerOrder: PLACED → CONFIRMED
    → insert ORDER_CONFIRMED Outbox event (unique per order)
    → COMMIT
```

No external network call, invoice/payment charge, Tribuo or Redis access happens in this transaction. Retry on already CONFIRMED returns same order after seller authorization; no duplicated stock increments, reservations or events.

## Implemented

- `InventoryBalance`: seller/location/SKU row with 19,3 quantity, version, nonnegative stock and reserved<=on_hand invariants, `PESSIMISTIC_WRITE` row claim.
- `InventoryReservation`: one reservation per `OrderLine`, immutable reference to seller/sku/location/quantity, initial status RESERVED.
- `OrderConfirmationService`: validates privileged `SELLER_ADMIN` membership via verified JWT issuer+subject, checks accepted bid/quote and origin location, revalidates current SKU and item measurement unit, acquires balance locks in deterministic order; atomic all-or-nothing reservation + confirmed status + event.
- `OrderOutboxEvent`: minimal stable event UUID, aggregate order ID, event type, timestamp and UUID-only payload; one ORDER_CONFIRMED event per order enforced by DB unique constraint; an actual dispatcher and consumer deduplication **are not implemented yet**.
- Flyway V10 creates inventory_balance, inventory_reservation and order_outbox_event with unique/FK/CHECK constraints.
- Flyway V11 disallows direct SQL UPDATE/DELETE of accepted order lines and mutation of order identity/price/bid economic fields. It still allows controlled status/version transition.
- `DealPersistenceService.submitBid` rejects further bids on a demand after buyer acceptance under the same demand lock.

## Verification

- RED tests: `InventoryBalanceInvariantTest`, `OrderConfirmationPostgresIT` for idempotent seller confirmation, multiline insufficient stock full rollback, competing orders for final unit. PostgreSQL uses **separate transactions** from parallel threads.
- Hardening: `OrderLineIntegrityPostgresIT` attempts unauthorized direct SQL changes to header amount and line price and verifies status transition remains permitted.
- GREEN status must be attached to exact SHA from GitHub Actions, not inferred from earlier runs.

## Open blockers

1. No seller inventory restocking/adjustment API or trusted InventoryMovement audit ledger. Current `InventoryBalance.open` is an internal seed/foundation, NOT a verified seller stock feed.
2. No cancellation, reservation release, partial shipment, POD/received, returns or reconciliation; these transitions require their own transaction and movement audit before production.
3. Outbox publisher/consumer dedup, retries/lease, error queue, delivery metrics, backfill and operation support not present. Outbox records are **committed locally**, not known to be delivered.
4. Seller KYC/status and geo fulfilment eligibility, buyer address/contact and payment/economic terms still require authoritative adapters and legal policies.
5. Test verifies TWO concurrent confirms competing for one stock unit, not 5000 concurrent users. Execute Postgres load tests, deadlock/timeouts metrics, DB pool and outbox failure injection; unknown performance under production load.
6. On accepted bid and quote, submit/confirm paths rely on trusted in-process principals; no public APIs for R2.0B/R2.1. Independent S2 review still missing.
7. `InventoryBalance` is a tracked physical SKU prototype. Services/digital/untracked products require separate business logic; do not fabricate inventory for them.
8. Multi-warehouse allocations, grouped fulfilments and seller stock movement/reconciliation remain future slices.

**Release decision:** Draft PR only. Require independent S2 review and R2 Test Review before new production checkout API.
