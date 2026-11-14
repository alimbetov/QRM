# R2.0B — Verified Buyer Bid Acceptance and CustomerOrder(PLACED)

**Status:** implemented as INTERNAL services and Flyway V7–V9 in draft PR #2, qualification recorded in GitHub Actions. No production checkout endpoint or external identity onboarding.

## Implemented

- `BuyerDemand.DemandLine` now carries optional `MeasurementUnit`; historic legacy 3-field demand requests are readable but null units **cannot be accepted**. `BuyerDemandLineSnapshot.unit` persisted via Flyway V7.
- `SellerSku.salesUnit`: seller product variant unit is server-owned, immutable once published; historical untyped SKUs are **ineligible** for checkout until explicitly versioned/reissued, not silently interpreted as PCS.
- `SellerBidSubmissionService.submit`: in one transaction validates canonical published SKU identity, requested product, matching unit and TRACKED inventory mode, active seller origin location, pickup eligibility or a server-managed active delivery-area record, then captures bid revision and immutable `SellerBidQuoteSnapshot`. Seller quote records KZT, selected fulfillment mode, source location/city/region, destination, shipping fee and all-in total. No fabricated shipping fees.
- `BuyerAccountLink` / `BuyerIdentityService`: validate verified JWT issuer+subject against a trusted backend-provisioned buyer ID; never trust a buyerId request field. Guest phone ordering and account provisioning are not implemented.
- `BidAcceptanceService.accept`: lock demand, verify buyer ownership, constrain exactly one accepted bid per demand, reject expired/superseded revision, verify seller owner and SKU units, recheck delivery/pickup eligibility and quote integrity. An idempotency key scoped to buyer with request SHA-256 fingerprint returns existing order on same payload and rejects changed payload. Creates `CustomerOrder(PLACED)` and `BidAcceptance` in the same database transaction.
- Existing `CustomerOrder.placed()` preserved for backward-compatible tests, while `placedFromBid()` records bid ID, shipping fee and quoted amount.
- Flyway V7 adds buyer binding, eligible area and SKU/demand units. V8 adds `bid_quote_snapshot` and `bid_acceptance` FK/UNIQUE/CHECK plus accepted-bid order linkage. V9 blocks historical quote/acceptance UPDATE/DELETE.
- No inventory reservation or Outbox is created in the R2.0B acceptance transaction, by business design.

## Test evidence

`BidAcceptanceFlowTest` covers JWT-bound buyer ownership, price including shipping, same-key replay, key reuse collision, expired bid, and refusal of new seller revisions after accepted demand. `BidAcceptancePostgresIT` executes all Flyway migrations on PostgreSQL 17 Testcontainers and validates schema existence. CI achieved full pass at `25075999c8064d2c39805f8a97f0307f80081b83`; subsequent improvements require their own exact-SHA CI.

## Gaps for production

1. Independent S2 security and migration review is still open: [Issue #3](https://github.com/alimbetov/QRM/issues/3). Real OIDC/JWKS, trusted membership provisioning and migration rehearsal for legacy databases not verified.
2. Buyer account creation/guest phone mapping, explicit buyer consent, legal receipts and returns not implemented. There is **no public checkout API**.
3. Delivery-area approval is an internal server record; full shipping price/coverage validation or delivery provider contracts, pickup constraints and SLA are not verified externally.
4. Current financial calculation is KZT to 2 decimal places, HALF_UP for line amounts. Business/legal monetary rounding, fees/taxes/discount decisions remain open and must be settled before paid orders.
5. No automatic order-number collision retry in separate transactions; DB uniqueness prevents duplicates but exceptionally colliding generation currently fails the transaction.
6. Only physical, TRACKED SKU with matching explicit unit are accepted in this slice. SERVICE / DIGITAL and buyer-selected subset lines have distinct contracts to implement later.
7. Idempotency collision scope is current `buyerId + key` (global per buyer in acceptance service). Require retention/expiry and PII lifecycle policy before production.
8. Current app-layer read checks protect ownership; stronger DB tenant isolation, direct SQL protection and concurrency stress are S2 areas.

## Next

R2.1 seller CONFIRMED → atomic InventoryReservation & Outbox, then cancellation/release/restocking/audit and E2E API tests. Do not mark ML-ready: neither a sent outbox event nor an actually received order has been evidenced here.
