# QRM — Second-Pass Technical Review (R0.2)

**2026-11-12 · Status: requirements and consistency review; not executable code audit, not independent S2 sign-off.**  
Base: [MVP technical review](TECHNICAL-REVIEW-MVP.md), [domain contracts](DOMAIN-CONTRACTS.md), [user journeys](USER-JOURNEYS-MVP.md).

## Findings: second pass

| ID | Severity | Under-specified or contradictory issue | Minimal corrective contract |
|---|---|---|---|
| R2-01 | P0 | Phone used as unique login although never verified; anyone can pre-register someone else's number and squatting blocks rightful owner | Never promise ownership, treat number as **unverified login alias**, suppress phone-based account lookup/recovery, throttle registration, allow controlled change/claim with verified independent factor; product/legal decision still required before broad public registration |
| R2-02 | P0 | Password recovery relies on optional email; no verified factor if lost device | Show recovery limitations at sign-up and **strongly encourage optional email verification**; no insecure phone-based reset; operational manual recovery policy before public launch |
| R2-03 | P0 | Terms of order receipt unclear; seller can self-report complete and inflate ratings/levels | `READY_FOR_RECEIPT` by seller ≠ buyer `RECEIVED`; count only qualified receipts, not self-declared payment; no silent auto-receipt; disputes handled separately |
| R2-04 | P0 | Discount percent / tier percent and rounding can produce inconsistent quotes; price changes between AGREED and order | One authoritative calculation sequence and versioned signed-or-stored quote, server recompute at order boundary, accepted negotiated unit price immutable for agreed terms until offer expiry, final total rounded once as documented |
| R2-05 | P0 | Web callback lead cancellation uses anonymous capability, but spec does not explicitly protect against disclosure and brute force | Opaque high-entropy withdrawal secret, return only at creation, hashed server storage, TTL, rate limits, no guessing/enumeration; no matching lead to User by supplied phone |
| R2-06 | P0 | Fulfillment vs Order cancellation and post-receipt claim conflated in state chart | Separate OrderStatus, FulfillmentStatus, CancellationRequest and ReturnClaim; an accepted cancellation never retroactively rewrites completed fulfillment/audit; exception handling per type |
| R2-07 | P0 | Review seller and product eligibility not fully declared, risk double ratings/cross seller | one review per `(order_id,target_type)` from owning buyer only, target seller/listing resolved from immutable Order snapshot, count only eligible/non-hidden reviews, support correction/moderation |
| R2-08 | P1 | Nightly `source_watermark timestamptz` does not guarantee transactional consistency of multiple queries when writes are concurrent | Use a stable Postgres `REPEATABLE READ` read transaction for coherent source extraction **or** a committed monotonically ordered outbox/journal cutoff with replay semantics; mere wall-clock timestamp is insufficient |
| R2-09 | P1 | Unique logical nightly job key may conflict with multiple rebuild generations and stale workers | Distinguish unique `run_id` and `(job,day,rules_version,generation)`; lease with fencing, bounded jobs; stage then atomic pointer swap only if publish cutoff not older than current |
| R2-10 | P1 | Snapshot tables duplicate monthly/daily data without demonstrated need | MVP: qualified Order event projection + three profile snapshot tables + run/pointer; defer daily/monthly rollups and partitions |
| R2-11 | P1 | Seller status and buyer status may be inferred as financial trust, poor neutrality | Activity tier != credit/reputation guarantee; seller rating separate; show sample count and as-of; no blocking/discounts based on score |
| R2-12 | P1 | Internal notifications and Seller Inbox both have read/unread states; race with updates | Keep independent read models, per-user ownership; `readActivityVersion` cannot consume unseen seller activity; notification dedup unique event+recipient+type |
| R2-13 | P1 | `GET` can't both be read-only and implicitly mark seller inbox item read | GET always side-effect free; explicit read-state mutation with observed version, UI calls after details load |
| R2-14 | P1 | Deploy order of Web QR login, Web Seller Cabinet, Flutter | Public React QR has no auth; protected Seller Web Cabinet requires working Flutter QR approval + session revocation on same release |
| R2-15 | P1 | Terms service/digital not qualified but all listing types appear enabled | Schema can recognize all three types; production publish restricted until each type has minimum tested fulfillment/claims, start physical route |
| R2-16 | P1 | Scheduled discount, favorites/alerts, YouTube and advanced analytics can block core pilot if treated as mandatory build order | Split vertical pilot critical path from next features; retain approved features in product baseline, avoid infrastructure expansion |
| R2-17 | P1 | Web consent/PII retention and seller contact purposes need enforceable policy | Consent version + timestamp + purpose + restricted seller visibility, retention & purge, redacted structured logs, KZ legal gate |
| R2-18 | P2 | Infrastructure overbuild: Kafka, Redis, full partitioning, analytical warehouse | Not needed; PostgreSQL + indexed source of truth + transactional outbox + same-deployable background worker until measurements justify |
| R2-19 | P1 | OpenAPI missing executable contract and concrete enum/validation cases | R0 freeze: OpenAPI 3.1, Liquibase, API-contract and concurrency RED tests before GREEN |
| R2-20 | P1 | Unverified identities and fake transactions could farm seller/buyer levels | No automatic `VERIFIED` badge; qualified Order requires independent buyer-confirmed receipt, fraud limits, moderation option, tier null/no-history for insufficient evidence |

## Recommended single-source-of-truth invariants

1. **PurchaseLead** exists only for seller callback; no agreement/order via anonymous Web lead; cannot derive identity by phone.
2. **Negotiation** latest-offer accept by opposite actor; max five counter messages **proposed**; AGREED no order. Buyer executes explicit idempotent conversion; unique accepted-offer link.
3. **Order pricing** uses server-side BigDecimal, explicit pricing rules version, immutable price/quantity/terms snapshot at committed order; campaign & tier application reproducible. Tax/rounding policy must be finalized before charging or pricing to users.
4. **Fulfillment evidence** requires buyer confirmation or formally reviewed dispute process; seller READY_FOR_RECEIPT insufficient for trade-level/review eligibility; platform never claims externally settled money.
5. **Product/Seller review** allowed only for eligible order and authenticated buyer, targets derived from that Order, one per type per Order; moderation corrections rebuild rollups.
6. **Nightly** consistency: fixed business cutoff at midnight Asia/Almaty; DB-consistent snapshot or replayable committed journal, run fencing and atomic pointer switch, no profile recomputation per HTTP GET.
7. **Security**: phone UNVERIFIED, password Argon2id, rotating refresh sessions, QR Web approval from signed-in Flutter, recovery only with verified independent factor, internal Notification Center authoritative, push optional.
8. **Workload**: no separate queue/database/service, no eager monthly partitioning, no compensating distributed saga before a proven need.
9. **Enabling types**: physical first in integrated tests; service and digital publishing disabled until own tested acceptance for fulfillment, cancellations and claims.
10. **Account/reputation safety**: no automatic economic discrimination by tier and no unverifiable paid/verified badge.

## Precise open decisions (cannot be silently 'approved')

| ID | Minimal recommendation | Blocked component |
|---|---|---|
| OPEN-01 | Counter round = one counter-offer message; explicit buyer order creation | Negotiation |
| OPEN-03 | KZT monetary scale & rounding, versioned quote TTL and agreed price finalization | Pricing |
| OPEN-04 | Fulfillment proof/returns for services and digital | Non-physical listings |
| OPEN-11 | How user recovers if phone not verified and email/device unavailable | Public registration |
| OPEN-13 | Nonresponse to receipt confirmation; dispute review and self-dealing checks | Ratings, tiers |
| OPEN-14 | One seller owner only in MVP, staff subaccounts later | Seller permissions |
| OPEN-15 (NEW) | How rightful phone owner can reclaim unverified alias; consider replacing unique phone with internal accountId as sole identity | Identity |
| OPEN-16 (NEW) | Concrete PostgreSQL source snapshot/watermark strategy and rerun fencing | Nightly profile |
| OPEN-17 (NEW) | Consent/retention texts and legal compliance in Kazakhstan | Public release |

## Minimal implementation order and gates

- R0: ratify blocking rules, one OpenAPI and Liquibase schema; S0 oracles; S1 RED; independent S2.
- R1: password identity/security and transactional R2DBC/Outbox spike (PostgreSQL Testcontainers).
- R2: seller profile, listing, authoritative pricing, QR React, anonymous callback lead.
- R3: Flutter buyer/seller negotiation, Order, cancellation, physical fulfillment, verified reviews, internal notifications.
- R4: seller cabinet + QR auth only when integrated Flutter approval works; nightly buyer/seller snapshots after qualifying receipt events exist.
- R5: optional scheduled discounts, favorites and price-watch, Push, YouTube and advanced QR analytics according to release priorities.
- Release: real-device E2E, security/PII and Kazakhstan legal review, DB backups/restore, monitoring; exact-SHA CI.

**Conclusion:** current spec is viable as modular monolith, but not qualified for immediate feature-complete implementation until listed P0 contracts are closed. This document **does not** assert passing tests or completed production code.
