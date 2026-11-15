# QRM — Entity Sufficiency Audit (R0, 2026-11-12)

**Status:** static documentation review, not JPA implementation review. Branch: `docs/adr-002-jpa-architecture-review`. Sources: ARCHITECTURE.md, DOMAIN-CONTRACTS.md, REQUIREMENTS.md, SCENARIOS.md, API-SPEC.md, MVP-DECISIONS-R03.md, TECHNICAL-REVIEW-MVP.md, NIGHTLY-PROFILE-AGGREGATION.md. No executable backend or schema audited.

## Verdict

Existing model captures most marketplace concepts but is **not sufficient to implement stock-reserved orders safely**. Important mismatches: ER currently links PRICE_OFFER to CUSTOMER_ORDER even though negotiations precede order; no inventory/reservation schema in primary ER; listing-to-SKU semantics unspecified; receipt challenge, cancellation and idempotency persistence under-specified. Avoid implementing these as scattered fields in CustomerOrder.

## Retain / refine

| Domain | Retain | Clarify |
| --- | --- | --- |
| Identity | User, UserRole, SellerProfile separate | Credential, RefreshSession, verified independent recovery factor and alias ownership |
| Catalog | Category, Product, Listing, ListingTranslation, MediaObject, ListingMedia | What a SKU is, who owns inventory, units and variant cardinality |
| Pricing | PricingTier, DiscountCampaign | PriceQuote identity/TTL/version and immutable Order price snapshot |
| Lead | PurchaseLead | withdrawal-token hash, consent version, retention |
| Negotiation | NegotiationSession, PriceOffer | Offer -> NegotiationSession; Order -> acceptedOffer; no Order before agreement |
| Order | CustomerOrder, OrderStatusHistory | OrderLine cardinality, idempotency records, cancellation request |
| Fulfillment | Fulfillment, ReturnClaim, ReturnReason | Physical receipt evidence, receipt challenge and dispute lifecycle |
| Rating | ProductReview, SellerReview | one-per-order-and-target constraints, moderation/eligibility |
| Messaging | SellerInboxItem, persisted notification, OutboxEvent | separate read flags, event processing dedup |
| Insights | ViewEvent/QRVisit, nightly snapshots | avoid unproven daily/monthly rollups and partitions |

## P0 missing or insufficient models

1. **ProductSku** — explicit purchasable SKU/variant vs Listing and Product; define whether a Listing has one or several SKUs. Proposal for MVP: one Listing points to one SKU; multi-variant deferred until proven by real catalog scenario.
2. **InventoryBalance** — one balance per seller/SKU/stock location (or seller/SKU in single-location MVP), fixed precision quantity, nonnegative checks; physical listings only unless resource-capacity semantics separately approved.
3. **InventoryReservation** — order-linked reserved quantity with status (ACTIVE/RELEASED/CONSUMED/EXPIRED), createdAt/expiresAt, unique order-line reservation guard. Reservation must be coupled to a transaction that checks current stock.
4. **InventoryMovement** — immutable quantity ledger for receive/adjust/reserve/release/consume, actor, correlation, reason; balance changes and ledger must be transactional.
5. **OrderLine** — immutable SKU/Listing snapshots and quantity, units, agreed unit price and total. If MVP deliberately only allows one listing per order, this can be reduced to a single immutable line embedded in CustomerOrder; decide explicitly before schema design.
6. **IdempotencyRecord** — identity/operation/key/requestHash/result reference and expiry. Use DB unique constraint as final guard, especially acceptedOfferId -> one Order.
7. **ReceiptChallenge** — order/fulfillment-bound, hashed opaque one-time credential, TTL, consumedAt, authorizing actor, concurrency-safe redemption.
8. **CancellationRequest** — distinct from OrderStatus/ReturnClaim; requester, reason, deadline and reviewed outcome, expected legal workflow.
9. **QualifiedOrderEvent / OrderEventJournal** — auditable dated evidence of receipt/return/reversal for exact nightly nine-month profile without relying on mutable current state only. Might reuse OrderStatusHistory enriched with event-time, qualified/reversed semantics; do not duplicate if a single verified history suffices.

## P1 modeling decisions

- **Stock ownership / fulfillment:** seller-owned stock only in MVP? Multi-warehouse and partial fulfillment deferred until need; `InventoryLocation` optional.
- **PriceQuote:** explicit persisted quote only if expiry/replay/price binding requires it; otherwise a versioned signed quote plus immutable Order snapshot, but never trust client price.
- **Media:** one MediaObject source-of-truth and references, avoid binary blobs in JPA or duplicated asset records.
- **Security:** verified email, recovery factors and session revocation as separate records; unverified phone is not proof.
- **Outbox:** delivery attempt/claim metadata and consumer idempotency, may live in OutboxEvent rather than extra table.
- **Timezone:** ADR-002 chooses Asia/Qyzylorda business setting; legacy specs reference Asia/Almaty for nightly cutoff. Reconcile explicitly before S0/S2; timestamps UTC.
- **Schema generation:** Hibernate update in local dev only; PostgreSQL constraints, indexes and lock semantics qualified against PostgreSQL Testcontainers, not H2.
- **Audit debt:** ARCHITECTURE.md Mermaid still contains `API -->|R2DBC| PG`; old TECHNICAL-REVIEW-MVP.md and SCENARIOS.md contain reactive/Liquibase references. These are stale docs; no runtime code changed.

## Minimal proposed relational topology (subject to S0)

```text
User -- SellerProfile -- Listing -- ProductSku -- InventoryBalance
                         |              |
                         |              +-- InventoryReservation -- CustomerOrder/OrderLine
                         +-- ListingTranslation / ListingMedia / PricingTier / DiscountCampaign
NegotiationSession -- PriceOffer(accepted) -- CustomerOrder -- OrderLine
CustomerOrder -- OrderStatusHistory / CancellationRequest / Fulfillment / ReceiptChallenge
CustomerOrder -- ReturnClaim / ProductReview / SellerReview
CustomerOrder -- OutboxEvent (via aggregate_id/event, not hard required FK)
```

## Mandatory acceptance tests

- Two simultaneous orders competing for the final SKU: one wins, never negative available stock.
- Order + reservations + movement ledger + Outbox commit/rollback atomically.
- Duplicate/replayed idempotency key or acceptedOffer never creates second order/reservation.
- Expiry/cancel/return events release or consume reserve exactly once, with auditable movement.
- Catalog offer without stock-tracking policy does not bypass physical-stock contract accidentally.
- OrderLine price/unit/quantity snapshots immutable on later listing edits.
- Buyer-confirmation receipt challenge replay, wrong-buyer, concurrent redemption denied.
- PostgreSQL unique/check constraints and locking tested under Testcontainers; H2 fast lane cannot certify these.
- Nightly extraction of qualified receipt and corrected/returned order stays consistent with cutoff.

## Decisions required before physical schema

1. Listing: one SKU or multiple SKU variants?
2. First MVP: one-line orders or basket/multi-line orders?
3. Stock: always tracked for physical goods? Single seller stock location?
4. Reservation point: at explicit buyer Order creation, after seller CONFIRMED, or other? Default recommendation is at Order creation with bounded TTL.
5. Reservation expiry/confirmation, partial availability, partial cancellation, returns.
6. Business timezone `Asia/Qyzylorda` vs nightly `Asia/Almaty` (same offset currently but distinct policy).
7. Persisted quote vs signed versioned quote.
8. Decide whether fulfilled qualified event can be represented entirely by existing OrderStatusHistory.

**Do not mark entity model S2-approved until these are resolved and reviewed.**

## Updated owner decisions (2026-11-12)

Earlier one-SKU, one-line and reserve-at-order-creation proposals are **superseded**. Approved: Listing 1..N SKU, CustomerOrder 1..N OrderLine, seller opt-in/out stock tracking per SKU, reservation on seller confirmation, InventoryLocation now with region and structured address (multiple locations per seller). One seller per order, one stock location per line, and all-or-nothing confirm are recommended R1 scope for independent S2. [Canonical contracts and RED scenarios](DEC-ENTITY-MULTI-SKU-LOCATION.md).
