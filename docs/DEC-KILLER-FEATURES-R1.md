# DEC-KILLER-FEATURES-R1 — Reverse Marketplace + Smart Bargaining + Smart Deal Finder

Status: **approved product direction; domain-only Java implementation slice**. No public HTTP endpoints, seller matching, actual ML inference, orders, push delivery or PostgreSQL persistence are provided by this commit.

## One coherent trading journey

1. Authenticated buyer creates `BuyerDemand`: 1..N canonical Product targets, quantity, optional budget per item, city (user-selected), expiry and privacy policy. Seller SKUs differ, so a request must **not** be tied to one seller SKU.
2. Server identifies **eligible sellers** with published matching products and allowed delivery/service area. Seller selection cannot be based on client-submitted seller claims. Opt-in seller participation, request quotas and spam controls required.
3. Multiple merchants create competing **SellerBid** proposals with entire itemized set, 1..N seller SKU mapping, per-item price, shipping/fulfillment terms, expiry, revision and seller identity verified against server catalog. Partial fulfilment requires explicit buyer authorization in later release; MVP requires full set from one seller.
4. Buyer sees total price + confirmed delivery/service fee, region and ETA where validated. Buyer may counteroffer in a **NegotiationSession / versioned PriceOfferLine** — prior versions are immutable, seller cannot silently change quantity or included items.
5. On an explicitly AGREED version, buyer may create exactly one `CustomerOrder(PLACED)` with their chosen seller and immutable per-line price snapshots; inventory reserves **only on seller confirmation**, atomically; expiry or changes require requote.
6. `SmartDealFinder` compares truly equivalent product/quantity and **confirmed all-in cost** (price + quoted fee); exclude private/unpublished/ineligible offers and unknown delivery price. It supplies candidate ranking for a future Tribuo model; it does **not** send push or claim ML training.
7. Once behavioral data and consent exist, Tribuo classifies usefulness for recommendation ranking, with explicit NotificationDecisionEngine gating (opt-in, quiet hours, caps, dedup, privacy, availability refresh).

## Model/ownership

| Model | Owner | Database proposal | Boundary |
|---|---|---|---|
| BuyerDemand | buyer-request | New aggregate root, R1b | created/withdrawn/expired; private contact info |
| BuyerDemandLine | buyer-request | child entity, R1b | canonical product + qty + attributes + budget |
| SellerBid | buyer-request | aggregate root, R1b | each seller may have successive immutable revisions |
| SellerBidLine | buyer-request | child entity, R1b | seller SKU, qty, price, matching product |
| NegotiationSession / PriceOffer / PriceOfferLine | negotiation | as already in registry | per-line counteroffers, no implicit order |
| DealComparisonResult | recommendation | computed VO | all-in price, validated data provenance, explainability |
| BuyerInterestProfile / MlModelVersion | recommendation | later | opt-in data, versioned ML |
| NotificationDecision | notification | later reuse outbox | rate-limit and audit |

**Do not conflate PurchaseLead** (anonymous callback) with BuyerDemand (authenticated shopping request), and do not create a CustomerOrder when a SellerBid appears.

## Required production conditions

- Owner-scoped authorization on all buyer demands and seller bids; do not disclose buyer phone, full address or GPS to competing sellers pre-selection.
- Enforce seller matching by SKU→Listing→Seller chain and publication/region availability; product synonyms/variants require approved comparison attributes (brand/model/storage, service scope, etc).
- `UNTRACKED` inventory disclosure; seller-confirm all-or-nothing reserve rule; no false promise at bid stage.
- Strict request and bid revision consistency, `UNIQUE(demand_id,seller_id,revision)`, one accepted bid/offer and idempotent one-order conversion.
- No user-content-driven SQL, no unbounded scans, no unsolicited seller messages; moderation and anti-spam quotas.
- Quotes and delivery estimates expire; recheck current terms before accepting/ordering. KZT BigDecimal and defined rounding. Show fees clearly.
- Buyers can opt out of promotional recommendation tracking and push independently of transactional order notifications.

## First executable domain slice

`backend/src/main/java/kz/zakaz/deal`: `BuyerDemand`, `SellerBid`, `DealQualification`, `SmartDealFinder`. Unit tests verify duplicate product rejection, all-line seller bids, expiry, immutable snapshots and true savings including delivery. These are **pure Java 21 domain rules**; persistence and security are intentionally gated by later PostgreSQL integration tests.

## Acceptance pipeline

- **R1/S1** (current): pure domain RED→GREEN for structural rules.
- **R2/S1**: PostgreSQL Testcontainers schema, unique constraints, race-safe accepting seller bid, buyer authorization and one-order conversion; true RED tests before infrastructure GREEN.
- **R3**: React/Flutter buyer demand form, seller inbox, compare bids, explicit accept and negotiation UI; E2E for sellers competing and buyer declining.
- **R4**: server-backed all-in deal search, basic personal preferences. ML feedback capture only with consent.
- **R5**: Tribuo trained ranking and safe marketing Push; offline metrics, opt-out, dedup, cap and A/B evaluation.
