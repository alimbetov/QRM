# QRM Smart Deal R2 — Independent technical consistency audit (SD-AUD-01)

**Date:** 2026-11-12. **Scope:** specification review of SD-R2 against QRM Identity, Listing, Negotiation, Order, Outbox, Pricing and Notification Center. **Not** an implementation audit and **not** independent S2 test approval.

## 1. Verdict

**Conditional pass for specification completeness after corrections. Not ready to mark R2-S2 accepted.** Business concept is valuable but atomicity, bid lifecycle, visibility, stock and comparison had unsafe or ambiguous edges. Fixes below use PostgreSQL constraints, existing services and domain invariants; no Kafka, ML service, inventory microservice or distributed saga.

| ID | Severity | Finding | Corrective design |
|---|---|---|---|
| SD-AUD-01 | P0 | Select bid then create Order can race seller withdraw, expiry and listing archive | Select is non-binding, still revalidate bid/demand/listing at conversion, use one transactional lock/CAS and create one accepted-offer snapshot + Order + Outbox |
| SD-AUD-02 | P0 | Bid replacement/version rows and `UNIQUE current` are not formally consistent | Immutable bid revisions, `seller_bid_revision` or immutable bid rows; partial unique active/selected current row, supersede old before inserting new in same TX |
| SD-AUD-03 | P0 | Seller could retract selected bid without explicit rule | Selected bid **cannot** be changed/withdrawn during valid selection hold; may expire and cause clean buyer-visible conflict; seller cannot silently alter terms |
| SD-AUD-04 | P0 | UNLISTED listing matching implies a sharing ACL absent in core | Only PUBLIC listings for automated discovery; UNLISTED not matched in v1 (future explicit buyer share grant design) |
| SD-AUD-05 | P0 | Bid selection implicitly looks like committed agreement | `SELECTED` means buyer intent to convert, **no NegotiationSession AGREED or Order yet**; create accepted offer + Order at explicit conversion transaction only |
| SD-AUD-06 | P0 | Money comparisons can hide unknown shipping, tax and scope differences | Standard cost breakdown incl. `included/known_extra/unknown`, comparable groups by fulfillment; ranking cannot call unknown-fee offer cheapest |
| SD-AUD-07 | P0 | Demand quantity unit vs listing unit could be compared on numerically equal but physically distinct units | Match **canonical unitCode** exactly in v1, seller bid quantity equals demand (no partial fill); no implicit KG↔G or volume conversion |
| SD-AUD-08 | P0 | Order conversion may imply inventory reservation guarantee | No reservation in SD-R2; validate current listing/quantity and stock rules if available, seller confirms fulfillment later; no oversell guarantee without separate InventoryReservation |
| SD-AUD-09 | P1 | Runtime matching depends on shipping coverage and seller trust metrics not yet modeled | Match only explicit category/unit/city/publish flags initially; require coverage configuration for delivery if claimed; no inferred coverage or trust/rating gate |
| SD-AUD-10 | P1 | Invitations can be sent repeatedly on matching retry | Use `unique(demand_id,seller_id)` plus transactional Outbox, bounded stable ordering and retries, bounded recipient max; no automatic outreach beyond opted-in suppliers |
| SD-AUD-11 | P1 | Seller could use new bid version to circumvent selected or expired constraints | One current bid per seller+demand; replacement only ACTIVE and OPEN demand, only before expiration; rejected once selected |
| SD-AUD-12 | P1 | Demand expiry and bid expiry are independently defined | `effectiveValidUntil=min(demand.expiresAt,bid.validUntil)`, winner selected before expiry, conversion before both; at conversion any expired part 409, no zombie Order |
| SD-AUD-13 | P1 | Return to OPEN from SELECTED is underspecified | Explicit `release-selection` buyer-only command before Order; increment version, set selected bid ACTIVE only if still valid, otherwise EXPIRED; no auto-reopen |
| SD-AUD-14 | P1 | Bidder visibility through shared demand path could leak buyer data or other sellers' bids | Role-specific DTOs; seller sees only own invitation/demand sanitized fields/own bid; only buyer sees all bids, never direct buyer phone or exact address |
| SD-AUD-15 | P1 | Bad idempotency keys can be replayed with altered request bodies | Store `(actorId,operation,idempotencyKey,requestHash,responseReference,expiresAt)`; same key+different payload = 409; scoped keys and retention |
| SD-AUD-16 | P1 | Effective price from public listing vs privately offered bid ambiguous | Bid is seller-set fixed unit price and explicit delivery terms; catalog quote is only buyer context, not overwritten. Snapshot negotiated price; validate no negative totals, seller ownership and expiry |
| SD-AUD-17 | P1 | Spec calls `OPEN -> SELECTED` with zero acceptance proof but tests say accepted offer link sometimes on select | Amend all docs/tests: accepted offer link **only at order conversion**, never at selection |
| SD-AUD-18 | P1 | No clear exact same-click semantics for selection + convert retries | Deterministic idempotent status: first 201, identical replay 200 same result; conflicting key/body 409; transactional unique conversion per demand |
| SD-AUD-19 | P1 | ML/data collection could overfit private events or become financial reliability promise | deterministic baseline first, human-controlled seller bids, versioned features/ML shadow later; no automatic account action |
| SD-AUD-20 | P1 | Scope of Smart Deal could override existing QR-first MVP | Separate feature flag and post-core release, no schema/data migration that blocks basic QR/Lead/Order |

## 2. Revised minimal event/transaction protocol

1. Buyer creates `DRAFT`, publishes `OPEN` within bounded TTL.
2. Matcher **reads PUBLIC listings only**, exact category, type PHYSICAL, same `unitCode`, city/declared servicing, and user eligibility. Candidate results read-only.
3. Worker consumes `BuyerDemandPublished` from existing Transactional Outbox, inserts deduplicated invitations `unique(demandId,sellerId)`, sends durable Notification Center events. No new queue/service.
4. Invited seller issues `SellerBid` for owned listing. One mutable *current pointer/state*, immutable historical revisions, seller offers entire demand quantity. Seller confirms availability but QRM does not reserve it.
5. Buyer sees all bids with consistent terms and delivery fee breakdown. Only buyer may SELECT one. Bid changes from ACTIVE to SELECTED without creating accepted negotiation or Order.
6. Buyer presses Create Order. **One DB transaction** locks/CASes demand and selected current bid, checks `min(expiresAt)` and listing state, pricing/tax policy, buyer/seller ownership and if available controlled-stock constraints; writes existing accepted offer and **one** `CustomerOrder(PLACED)`, conversion mapping and Outbox. Then demand ORDER_CREATED, bid CONVERTED.
7. Seller fulfillment and buyer receipt operate through existing Order/Fulfillment. No independent Smart Deal delivery/status model.
8. If conversion rejected (expired/archived) no half-created Order/offer, buyer gets clear reselect/recreate path. If app times out, identical idempotent request returns same Order.

## 3. SQL constraints / persistence principles

```sql
-- illustrative constraints, not executable Liquibase:
-- buyer_demand(id PK, buyer_user_id, state, version, selected_bid_id NULL, order_id NULL)
-- seller_bid(id PK, demand_id FK, seller_id, listing_id FK, version, status, ...)
CREATE UNIQUE INDEX seller_bid_single_current
  ON seller_bid(demand_id, seller_id)
  WHERE status IN ('ACTIVE', 'SELECTED');
-- selected bid belongs to demand is checked in same tx; consider composite FK when schema frozen.
CREATE UNIQUE INDEX smartdeal_conversion_demand_unique
  ON smartdeal_conversion(demand_id);
CREATE UNIQUE INDEX smartdeal_conversion_bid_unique
  ON smartdeal_conversion(accepted_bid_id);
CREATE UNIQUE INDEX smartdeal_conversion_order_unique
  ON smartdeal_conversion(order_id);
CREATE UNIQUE INDEX invitation_unique_seller
  ON match_invitation(demand_id, seller_id);
```

**Revision policy:** replace ACTIVE only by in-transaction setting existing ACTIVE → SUPERSEDED then inserting fresh ACTIVE with incremented version; both old and new economic terms are audit-immutable. No update of selected or converted economics. `bid.version` + demand `version` checked using SQL CAS or row locks, not in-memory locks. The exact schema and use of partial indexes must be validated in Testcontainers PostgreSQL; no claim they were executed.

**Idempotency contract:** a request hash and stored result ensure retries do not create duplicate invitations, bid revisions, accepted offers, Orders or Outbox records. Once ORDER_CREATED, `cancel` on demand cannot cancel the Order.

## 4. Updated state transition guards

| Aggregate | From | Command | Guard | To |
|---|---|---|---|---|
| Demand | DRAFT | publish | owner, valid scope, expiry future | OPEN |
| Demand | OPEN | select bid | owner, current ACTIVE bid, same demand, current listing PUBLIC, before both deadlines, version | SELECTED |
| Bid | ACTIVE | seller replace/withdraw | owner, demand OPEN, no selection, before expiry | SUPERSEDED / WITHDRAWN |
| Bid | ACTIVE | select by buyer | demand OPEN, same demand, valid bid | SELECTED |
| Demand | SELECTED | release-selection | buyer, no Order, version | OPEN |
| Bid | SELECTED | release-selection | valid bid -> ACTIVE; expired -> EXPIRED | ACTIVE / EXPIRED |
| Demand | SELECTED | create-order | buyer, bid valid, listing PUBLIC, accepted price/terms, constraints, idempotency | ORDER_CREATED |
| Bid | SELECTED | create-order | same transaction, one accepted Offer+Order | CONVERTED |
| Demand | OPEN/SELECTED | expiry | system or request-time guard; no Order | EXPIRED |
| Demand | OPEN/SELECTED | cancel | buyer owner, no Order | CANCELLED |

**No bidder-provided buyer/seller IDs** are trusted. `SELECTED` is a temporary buyer choice, not seller agreement again (seller already issued priced bid), and cannot count as a completed transaction. Buyer may not unilaterally modify seller-offered price.

## 5. New RED regression oracles

| ID | Test | Oracle |
|---|---|---|
| SD-37 | SELECTED bid already expired before create-order | 409, zero Order/Offer/Outbox conversion, demand can release/reselect |
| SD-38 | Seller tries change/withdraw SELECTED bid | rejected; immutable terms, no race |
| SD-39 | After ACTIVE replacement, concurrent select old revision | old rejected, at most current revision selected |
| SD-40 | Matching sees UNLISTED but no explicit share grant | excluded from automatic matching |
| SD-41 | Bid quantity is less than demand or unit mismatched | 422, no bid |
| SD-42 | Same idempotency key with different payload | 409, first response remains authoritative |
| SD-43 | Same demand converted twice under different idempotency keys | one Order, safe conflict/existing reference |
| SD-44 | Delivery cost absent and pickup/delivery bids compared | no false lowest-total ranking |
| SD-45 | Same published event replay 10 times | one invitation per (demand,seller), no duplicate notification |
| SD-46 | Seller has no configured city coverage for delivery | no invented delivery eligibility |
| SD-47 | Buyer release-selected request before Order | demand OPEN, bid only ACTIVE if not expired, audited |
| SD-48 | Demand expires while worker creates invitations | no new actionable invitation/eligible bid |
| SD-49 | Stock changed after seller bid, before conversion | inventory guard applied if available; no 'reserved' promise |
| SD-50 | Seller tries to access another seller's bid/demand private fields | 403/404 or redacted DTO, never PII |
| SD-51 | Same request key after timeout and successful conversion | 200 same Order, no second outbox |
| SD-52 | Smart Deal feature disabled | core QR → React Lead / regular Flutter Order unaffected |

## 6. Remaining decisions to approve

- SD-ACT-01: 10 invitees / 7d demand / 48h bid / max selection hold (proposal: selection until min expiry; no extension).
- SD-ACT-02: Whether physical seller coverage requires explicit city setting (proposal: yes) and exact region taxonomy.
- SD-ACT-03: Whether SellerBid includes shipping/taxes as fixed final quote (proposal: require a complete amount **or** mark shipping unknown and disallow ranking it as total-cheapest).
- SD-ACT-04: Existing Negotiation order conversion integration contract — transactional creation of accepted offer without five-round loop; reuse same Order invariants, avoid extra accepted-offer semantics.
- SD-ACT-05: Whether to add inventory reservation after first pilots (proposal: **no** unless real stock-controlled use case and concurrency evidence).
- SD-ACT-06: Identity account squatting and direct payment disputes upstream remain unresolved, not solved by Smart Deal.
- SD-ACT-07: Reopening selected demand vs UX simplicity (proposal: allow one explicit release before Order; not automatic re-open).

**Exit gate:** convert SD-01…52 oracles to executable RED on real PostgreSQL + WebTestClient/UI, obtain independent S2 review, then implement. No actual executable tests or production code are claimed.
