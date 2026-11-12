# R2 Scenario Design — BuyerDemand → SellerBid → CustomerOrder → InventoryReservation

**Status:** S0 Scenario Design and test oracles prepared; NO R2 transactional implementation claimed. Branch PR #2 remains Draft; S2 authorization/migration gate is independent and still pending.

## Target business flow

1. Buyer creates `BuyerDemand` with canonical product identities, quantities, city, expiry and optionally maximum preferred price. Demand identity/correlation is immutable. Unknown product/invalid units/expired demand fail early.
2. Merchant authenticates via signed JWT mapped to server-owned active `SellerMembership`. Merchant submits `SellerBid` containing exact requested basket (R2 MVP), own authorized `SellerSku` per product, immutable price, currency KZT, revision and expiry. Server resolves SKU facts, not client "eligible" flags.
3. Deterministic eligibility: same buyer demand, all requested lines, current canonical SKU product identity and hard attributes, published listing, merchant ownership, quantities/units, verified seller status, delivery-area coverage, live stock status and non-expired bid. Missing proof is NOT success. Tribuo may rank *after* these hard checks in future; no training/inference in order transaction.
4. Buyer explicitly accepts one eligible immutable seller bid revision; create one `CustomerOrder(PLACED)` with immutable `OrderLine` snapshots (SKU, quantity, unit price, city/region, KZT) and idempotency key. **PLACED does not reserve stock**.
5. Seller confirms order after validating own seller association. Reserve on seller confirmation in the SAME PostgreSQL transaction: lock all affected `InventoryBalance` rows in deterministic order (seller/location/SKU) to avoid races/deadlocks, check `available = onHand - reserved >= requested`, ensure no negative balance, create per-order-line `InventoryReservation`, change order to `CONFIRMED`, add `OutboxEvent`. For multi-line order, failure on one line rolls back all reservations and status change.
6. Retries are idempotent by `(actor,operation,key)` with request fingerprint. Outbox events are at-least-once delivered and deduplicated by event ID. Demand is counted **once**, even when invited to multiple sellers. Incomplete retries and status transitions do not emit duplicate sales.
7. Explicit buyer cancellation/return/release events evolve balances and immutable history; not in initial R2 order acceptance slice but API must not imply that missing behaviour is implemented.

## Domain ownership and new minimum entities (subject to S2 review)

| Owning context | Existing code | Needed for R2 |
|---|---|---|
| Catalog | `Category`, `CanonicalProduct`, `SellerListing`, `SellerSku`, `CatalogSkuFactsResolver` | Verified seller status, live status and coverage gateway, attributes revision |
| Deal | `BuyerDemand`, `SellerBid`, `DealQualification` (pure Java records) | Persistence, bidder identity, revision lock, acceptance record |
| Order | `CustomerOrder`, `OrderLine` JPA; one seller | Idempotent application service, legal order transitions and permission checking |
| Inventory | `InventoryLocation` JPA | `InventoryBalance`, `InventoryReservation`, `InventoryMovement`; row lock/version |
| Events | Planned | `OutboxEvent`, unique correlation/event ID and reconciled snapshots |

## RED → Test Review acceptance scenarios

| ID | Scenario | Objective test oracle | Priority |
|---|---|---|---|
| R2-01 | BuyerDemand of 2 canonical products | Persist same buyer/lines/expiry, reject duplicate product or invalid qty | P0 |
| R2-02 | Seller bid from different seller than SKU owner | HTTP 403 and no persisted bid | P0 |
| R2-03 | Unpublished/disallowed/incompatible SKU | Reject bid; never trust client-supplied eligible flag | P0 |
| R2-04 | Expired demand/bid | Reject accept; order count unchanged | P0 |
| R2-05 | SellerBid immutable revision | Historical price/quantity snapshots preserved; no hidden mutations | P0 |
| R2-06 | Buyer explicit accept one bidder | One seller per order; exact line/price/KZT snapshots | P0 |
| R2-07 | Duplicate accept retry, same key and payload | Same order ID, no extra lines/events | P0 |
| R2-08 | Duplicate key, changed payload | 409 conflict, no unexpected order | P0 |
| R2-09 | Order PLACED | Inventory reservations and reserved units remain 0 | P0 |
| R2-10 | Seller confirmation | Single transaction creates status, reservations and outbox | P0 |
| R2-11 | Two sellers or two orders race for final SKU | One reservation wins, other 409; invariant `reserved <= onHand` | P0 |
| R2-12 | Multiline basket insufficient stock on second line | Entire confirmation rolls back, zero partial reserves | P0 |
| R2-13 | Duplicate confirm retry | One reservation per order line, one unique confirmation event | P0 |
| R2-14 | Missing merchant authorization at confirmation | Deny and persist nothing, including no outbox | P0 |
| R2-15 | Seller inventory location mismatch | Reject fake location, no cross-merchant reserve | P0 |
| R2-16 | Contract failure after stock update before outbox | Transaction rolls back stock and status | P0 |
| R2-17 | Outbox replay / worker restart | Exactly-once business effect via event ID, at-least-once transport | P0 |
| R2-18 | BuyerDemand shown to 5 sellers | Demand aggregation counts once, not five independent purchases | P1 |
| R2-19 | Weekly snapshot after cancellations | Correct immutable revisions, no false positive sale | P1 |
| R2-20 | 5K concurrent users with real PostgreSQL | Measure p95/p99, errors, race integrity, DB pool CPU and throughput | Performance gate |

## Decisions before R2 RED commit

- Finalize order transition authority: `PLACED → CONFIRMED` by seller, after live stock check; no reservation on `PLACED`.
- Explicit price units: use existing KZT decimal representation until monetary rounding/legal policy is confirmed; reserve exact price snapshots.
- Choose inventory grain: `(seller_id, location_id, sku_id)`; enforce FK and same-seller/location checks, unique reservation per order-line and location.
- Order-number collision and idempotency must be enforced in service with transactional retries; random number generator alone is insufficient.
- Model the first executable scenario with physical `TRACKED` inventory; SERVICE/DIGITAL have distinct fulfilment rules and will be separate slices. Do not fabricate inventory balances for untracked services.
- Keep first release as modular monolith, MVC/JPA with PostgreSQL row locking. Preserve future source events for ML, but no Tribuo or Redis in purchase transaction.

## Suggested order of implementation

R2.0: domain invariants, migrations and immutable bid/acceptance persistence (RED, review, GREEN).
R2.1: inventory balance/reservation/movement with PostgreSQL concurrency Testcontainers (RED, review, GREEN).
R2.2: order confirmation/idempotency/outbox transaction + negative tests.
R2.3: authenticated API, smoke journey, buyer web integration, performance qualification.

No merge to main before S2 architecture/security review and R2 independent test review.
