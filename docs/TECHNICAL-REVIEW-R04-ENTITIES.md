# R0.4 — Independent-style repeat design review (2026-11-12)

**Status: REVIEW COMPLETED / NOT S2 APPROVED.** This is a static cross-document audit, not an independently signed S2 or executed JPA test report. PR #2 HEAD inspected: `9bc8504f5436e2b549c27c05064ac36ef6607eff`. Repository contains documentation only.

## Review scope

`DEC-ENTITY-MULTI-SKU-LOCATION.md`, `ARCHITECTURE.md`, `DOMAIN-CONTRACTS.md`, `REQUIREMENTS.md`, `API-SPEC.md`, `SCENARIOS.md`, `TEST-PLAN.md`, `NIGHTLY-PROFILE-AGGREGATION.md`, `MVP-DECISIONS-R03.md`, `IMPLEMENTATION-PLAN.md`, `TECHNICAL-REVIEW-MVP.md`, `DECISIONS.md`.

## Confirmed owner choices

- Listing 1..N SKU variants.
- Order 1..N OrderLine.
- Seller-selected `TRACKED | UNTRACKED` stock policy.
- Seller confirmation triggers reservation (no reservation on PLACED).
- InventoryLocation included now, multiple per seller, with region and structured address.

## Findings

| ID | Severity | Evidence | Required change |
| --- | --- | --- | --- |
| R04-01 | P0 | REQUIREMENTS.md §7 still says `one Listing per order`; new DEC-ENTITY says multiple SKU/lines | Replace older single-line rules in master requirements and API examples; explicitly limit order to one seller if adopted |
| R04-02 | P0 | PriceOffer previously targets listing/one unit; new DEC-ENTITY marks basket-vs-line scope OPEN | Define negotiated line item snapshot, per-line discounts, mixed negotiated and list-price lines; accepted offer identity remains unique |
| R04-03 | P0 | Reservation starts at seller CONFIRMED, but release/consume triggers not fully defined | State machine covering shipment/hand-over, cancellation, expiry, returns, idempotent movement ledger |
| R04-04 | P0 | Multiple InventoryLocation and multi-line Order, but Fulfillment is singular at order level | Decide single/multiple fulfillment groups; model per-line location, pickup/delivery split, privacy and order aggregation |
| R04-05 | P0 | Inventory tracking mode seller configurable; transitions not fully specified | Define safeguards against TRACKED -> UNTRACKED, negative adjustments, active orders and unlimited untracked demand |
| R04-06 | P0 | Conditional reserve SQL by (location, sku) assumes row exists; SKU ownership/validity and duplicate same-SKU lines not fully specified | Unique balance; canonicalize duplicate lines or aggregate required quantities per balance; validate same seller/location, active SKU, qty/unit |
| R04-07 | P1 | PricingTier/DiscountCampaign tied to Listing in legacy ER; new contract requires SKU effective price | Define SKU-vs-Listing price inheritance, exclusivity, campaign precedence and product variant snapshot |
| R04-08 | P1 | API-SPEC.md lacks comprehensive inventory, SKU, multi-line confirmation and allocations contracts | Add listing variants and private inventory endpoints, order.lines DTO, confirmation request with location selections, 409/422/403 semantics |
| R04-09 | P1 | NIGHTLY-PROFILE-AGGREGATION.md references Asia/Almaty, R2DBC, Liquibase; ADR-002 selects Asia/Qyzylorda/MVC/JPA | Reconcile time zone, JDBC integration and schema policy before batch implementation |
| R04-10 | P1 | REQUIREMENTS.md references WebFlux/R2DBC; SCENARIOS.md and TECHNICAL-REVIEW-MVP.md retain legacy stacks | Replace active-baseline text, mark historical documents as snapshots where appropriate |
| R04-11 | P1 | H2 CI mapping fast lane cannot certify PostgreSQL conditional reserve/locking/indexes | Required Postgres Testcontainers gate, concurrency and DB-constraint assertions |
| R04-12 | P1 | Location region/address fields include optional city/region ambiguity and potential privacy exposure | Define Kazakhstan address normalization, required settlement, pickup-public flags and authz |
| R04-13 | P1 | `InventoryReservation` only one per line proposed R1; multiple locations are supported by seller, but line split remains unsupported | Make R1 one-location-per-line explicit, or introduce separate per-line allocations; prohibit silently confirming split shortages |
| R04-14 | P1 | `Fulfillment` order-level receipt proof while order has multiple lines/locations | Decide whether receipt is whole-order or per-shipment; reviews/qualified event cannot precede all required delivery proofs |

## Recommended design closure before JPA

1. **Order owner:** one seller per CustomerOrder in R1, multi-seller cart (if later) split at checkout.
2. **Pricing:** effective unit price resolved per SKU; OrderLine immutable. Negotiated offer should identify one or more proposed lines; avoid blanket basket discount until explicit contract.
3. **Reservation:** `PLACED` has no stock hold; `CONFIRMED` has atomic reservation for every TRACKED line; fail-all on insufficient/invalid location. Aggregate duplicate SKU demands before conditional SQL and sort stock keys to mitigate deadlocks.
4. **Inventory:** durable `InventoryLocation`, `InventoryBalance`, `InventoryReservation`, `InventoryMovement`; location seller authorization and nonnegative balance invariant. UNTRACKED never pretends stock is guaranteed.
5. **Fulfillment:** model need for order-line allocation and multiple dispatch/pickup groups. An MVP can intentionally require one fulfillment group per order while keeping multiple SKU and multiple managed locations; alternatively add FulfillmentGroup upfront. Choose explicitly, do not conflate inventory location with delivery route.
6. **Tests:** H2 for DTO, mappings and pure rules; PostgreSQL Testcontainers for atomic confirm, lock/version, idempotency, duplicate sku, rollback, missing balance row, inactive warehouse, and release/consume.
7. **Timezone and docs:** one business-time policy and reconcile active spec stack references.

## Additional RED oracles proposed

| Test | Oracle |
| --- | --- |
| INV-19 | Two order lines share same SKU and location, each individually fits but total does not: entire confirm rejects; zero stock change |
| INV-20 | TRACKED SKU has no InventoryBalance at chosen location: confirm rejects, no implicit creation |
| INV-21 | Region exists but inactive or seller-mismatched location: unauthorized/invalid, no reservation |
| INV-22 | Same SKU in one order at two locations requires explicit allocation; no implicit split |
| INV-23 | Confirm transaction experiences forced deadlock/timeout: rollback and bounded retry behavior, no duplicate Outbox |
| INV-24 | Concurrent stock adjustment during confirm cannot result in reserved > onHand |
| INV-25 | Order with mixed price versions and negotiated SKU line retains exact per-line immutable totals |
| FUL-ML-01 | Two lines fulfilled from separate warehouses: no premature order COMPLETED or review qualification |
| FUL-ML-02 | Receipt challenge only marks eligible fulfillment scope, never unreceived lines |
| API-ML-01 | Cross-seller SKU and location ID tampering: no disclosure or cross-tenant state mutation |

## Decision gate

**NOT READY** to freeze physical JPA schema. Next step: update master requirements + canonical ER/OpenAPI/state machines, get owner agreement on multi-line negotiation and fulfillment-group rules, then S1 executable RED tests and independent S2. No code/test execution performed.
