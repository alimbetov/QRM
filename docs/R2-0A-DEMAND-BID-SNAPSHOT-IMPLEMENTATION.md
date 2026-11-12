# R2.0A — BuyerDemand / SellerBid historical snapshot foundation

**Status:** S1 implementation in Draft PR #2; no independent S2 approval; no HTTP checkout or stock reservation. **Exact code commit:** `ca58c603e102479c96b6bfd8e480d6cb0dcfd351` (check CI before use).

## Delivered

- Domain-only `BuyerDemand`, `SellerBid`, `DealQualification` preserved (no changes to public records).
- `BuyerDemandSnapshot` + `BuyerDemandLineSnapshot` Hibernate entities capture buyer ID, city, deadline, immutable canonical-product quantity and optional preference price.
- `SellerBidSnapshot` + `SellerBidLineSnapshot` capture merchant ID, demand ID, revision, expiry, SKU/product correspondence, quantity and seller's stated unit price.
- `DealPersistenceService.createDemand` only accepts a nonexpired buyer request with existing canonical products in active categories. **Caller-supplied buyer UUID must originate from verified buyer identity once exposed via an API.**
- `DealPersistenceService.submitBid` checks supplied server-authenticated seller ID, locks demand row, compares exact requested basket and expiry, verifies strictly consecutive revisions for that seller, resolves published owned SKU facts from catalog, and rejects a different canonical product.
- Transactional read functions `getDemand` and `getBid` materialize plain domain records before closing the EntityManager; no lazy collections returned to caller.
- `@Version` in assigned-UUID roots makes Spring Data JPA choose `persist` for new root/child snapshots rather than mistakenly merging detached new lines.
- Flyway `V5__buyer_demand_seller_bid_snapshots.sql` has FKs, unique product per basket, unique bid revision `(demand_id,seller_id,revision)`, positive quantity and nonnegative price checks.
- Flyway `V6__append_only_deal_snapshots.sql` adds PostgreSQL triggers blocking direct SQL UPDATE or DELETE on four snapshot tables; future acceptance/status changes must reside in **separate state/event tables**.
- H2 tests cover recovery, seller forgery, missing/skipped revisions, expiry and mismatch of SKU canonical product.
- PostgreSQL tests cover V5 schema migration + bid rehydration, and SQL bypass attempts against V6 append-only guards.

## Production gates — NOT completed

1. **S2 Issue #3 remains open**, no independent security/migration verdict. This work is private/internal service only; no REST or UI buyer checkout added.
2. Confirmed seller eligibility, geographic coverage, inventory availability and source-of-truth monetary quote are **not** proved by an accepted catalog SKU.
3. **Unit-of-measure ambiguity:** `BuyerDemand.DemandLine` and `SellerBid.BidLine` do not yet carry a verified unit; converting to `OrderLine.Draft` would silently invent e.g. PCS for KG/service/digital. Do NOT create CustomerOrder until unit contract and seller's actual SKU unit are governed.
4. Buyer identity and seller membership for bidding must be extracted from verified server principals; `DealPersistenceService.submitBid` currently accepts only a trusted in-process seller UUID with no external API.
5. Quote expiration, delivery/pickup fees/region, discounts, KZT rounding and stock reservation policy need explicit binding to accepted bid **revision**. Never treat a price preference as a mandatory seller cap.
6. **No idempotency key** or unique accepted bid/order relationship yet; add `IdempotencyRecord`, `BidAcceptance` and order-link transaction with explicit competing-buyer/offer confirmation semantics.
7. **Physical PostgreSQL concurrency** for duplicate simultaneous bids/revisions and accept/order races needs independent Testcontainers verification under committed transactions.
8. Snapshot semantics: the order must pin accepted bid revision and exact displayed all-in price; live inventory is checked on seller confirmation, not `PLACED`.
9. Legal retention/PII deletion must reconcile append-only fact design; no indefinite storage of personal data as an unintended consequence.
10. ML event pipeline should follow verified demand/bid/order transitions via transactional Outbox; current snapshot tables are **not** equivalent to a complete, correctly labeled event stream.

## Next S2 / R2.0B

- Data contract: canonical unit and normalized quantity, all-in KZT quote, fulfillment location/territory, exact seller SKU and buyer-selected subset rules.
- RED: accept wrong buyer or stale/superseded revision; duplicate acceptance same/changed idempotency key; out-of-stock confirmation cannot reserve; competing confirmation; no partial multi-line reservation; DB append-only facts immutability.
- Review invariants independently; implement `BidAcceptance`/idempotency + `CustomerOrder(PLACED)` only after above contract.
- Then `InventoryBalance` / `InventoryReservation` and seller confirmation with ordered row locks and Outbox in one transaction.

**Release recommendation:** keep PR #2 Draft, no merge to main until independent S2 and complete R2 acceptance gates.
