# QRM — Technical Consistency Review and Lean MVP Baseline

**Дата:** 2026-11-12. **Статус:** архитектурный review требований, не code audit и не S2 approval. Действующие явно APPROVED DEC-01…27 сохраняются. Спорные параметры ниже — **recommended defaults / PROPOSED**, до утверждения бизнес-оракулов.

## 1. Итог

Стек: один Spring Boot WebFlux/R2DBC modular monolith, PostgreSQL, одна S3-compatible media store, React public/seller Web, Flutter buyer/seller, Transactional Outbox + worker **в том же deployable**. Liquibase в отдельном migration step. Не нужны Kafka, Redis, Kubernetes, ML, отдельный CQRS server, data warehouse или scoring microservice для первого MVP.

Обязательный критерий первого интегрированного среза: SellerProfile → Listing/price → QR → React view/callback OR Flutter offer → buyer confirms Order → seller fulfillment → buyer receipt → verified review → nightly profile read. Web callback — **только звонок**, без Web Order.

## 2. Cross-document issue register

| ID | Severity | Disagreement | Lean correction |
|---|---|---|---|
| REV-01 | P0 | Negotiation PENDING/ACCEPTED в master и OPEN/AGREED в domain | canonical OPEN/COUNTER_PENDING/AGREED/REJECTED/WITHDRAWN/EXPIRED/ORDER_CREATED; presentation labels localize |
| REV-02 | P0 | Order COMPLETED mixed with delivered/received | separate Order status from fulfillment evidence; seller completion != buyer receipt |
| REV-03 | P0 | AGREED->Order allowed generic authorized actor; UX insists Flutter buyer | proposed buyer-only explicit POST create-order with idempotency, seller can accept/counter via Web/Flutter |
| REV-04 | P0 | QR seller Web access in MVP but Web QR login in P1 | if Web seller cabinet is released, AUTH-QR is a release prerequisite; public QR page needs no login |
| REV-05 | P0 | Product review exists; seller reviews/endpoint incomplete | one verified order allows distinct optional product and seller ratings; unique (orderId, targetType), eligibility and moderation |
| REV-06 | P0 | Unverified phone uniquely claimed but no number control/recovery | phone is login only; require verified email/trusted session for recovery; address account squatting with abuse controls and explicit user warning; no fake phoneVerified |
| REV-07 | P1 | Daily/monthly/rolling & nightly snapshots described as simultaneous requirements | implement auditable fulfillment projection + three nightly snapshot tables; day/month aggregates only if needed for load or exact boundaries |
| REV-08 | P1 | Seller reputation tier confused with star rating or paid orders | seller tier=qualified fulfilled Orders, rating=eligible review avg+count, never 'paid' |
| REV-09 | P1 | Three product types imply full lifecycle in first release | common Order core, explicit per-type fulfillment policy; physical first integration; service/digital must pass own minimal acceptance before enabling publishing |
| REV-10 | P1 | API paths mix prefix and separate duplicate /sellers route | external API always /api/v1, UI routes /p and /s only; make one owner per route |
| REV-11 | P1 | Static tier eligibility lacks anti-abuse and as-of consistency | nightly snapshots mark as-of + data freshness; no auto financial privilege; qualifying events must be independently auditable |
| REV-12 | P1 | Inbox read vs notification read | two independent read states; durable notifications internal; push is optional |
| REV-13 | P2 | YouTube/photo review/attribution advanced analytics consume early scope | keep YouTube single normalized external link; defer video storage, advanced funnel, dynamic loyalty |

## 3. Canonical MVP state/actor contracts

### 3.1 Negotiation (proposal to ratify)

Initial buyer price creates OPEN (not Order); each counter-offer increments counterCount by one, up to five **total counter-offer messages** per negotiation. Only the opposing party may accept latest active offer; expired/withdrawn/rejected cannot be accepted. AGREED means price accepted only. Buyer presses Create Order in Flutter, server checks listing validity, quantity, price/offer snapshot and creates **at most one** linked Order, after which negotiation is ORDER_CREATED. New negotiation creates a new session, not a second Order from the same accepted offer.

### 3.2 Order versus fulfillment

Order statuses for initial contract: PLACED, CONFIRMED, PROCESSING, CANCELLATION_REQUESTED, COMPLETED, CANCELLED_BY_BUYER, CANCELLED_BY_SELLER, CANCELLED. **COMPLETED only after eligible buyer receipt confirmation or an independently approved dispute/timeout policy**; seller marks fulfillment READY_FOR_RECEIPT before that. Physical transport sub-status is separate. Claims/returns are separate records and do not overwrite completed order history. Until nonresponse/claim policy approved, don't auto-complete/auto-award tier.

### 3.3 Rating and abuse

Product and seller ratings are separate subject types, each allowed once per qualifying Order and subject, editing retains audit. Seller/ buyer themselves may not rate their own own transaction. Missing rating -> null average and 0 count, not zero stars. Public labels 'завершено по подтверждённому получению', not 'оплачено'.

### 3.4 Authentication and privacy

No SMS/OTP. Phone as **unverified username**, password Argon2id, rotating refresh sessions and rate limits. Web callback contact stays unverified; withdrawal by opaque per-lead proof. Protected seller Web cabinet requires browser-bound QR challenge approved from authenticated Flutter, unless a different secure Web login is expressly implemented and tested. Push token never authenticates.

## 4. Consistent lean MVP priorities

| Must have for first vertical pilot | Conditional or later |
|---|---|
| Identity/session, seller profile/logo, Listing one language + 3–5 photos, KZT pricing/tier, QR public card, Web callback, buyer Flutter order, seller inbox/order operations, cancellation, fulfillment receipt, ratings, internal notifications, privacy, PostgreSQL outbox | Scheduled discounts, optional YouTube, Flutter favorites & watches, advanced QR source attribution, push/FCM, review photos, extensive seller analytics, dynamic loyalty |
| If releasing React Seller Cabinet: secure QR-login + session revoke must ship with it | If only Flutter seller during earliest pilot, defer QR web login until cabinet launches |
| Nightly buyer/seller/seller-buyer snapshots as read-only profile capability once Order+Review events exist | Full raw event/monthly partitioning, incremental stream computation, external job framework |

Original broader approved product scope remains in master requirements; this table is **implementation sequencing**, not deletion of approved functionality.

## 5. Lean database and profile projection

- Source tables: User/credentials/sessions; SellerProfile; Listing/Media/Translation; PriceRules; QR; Lead; Negotiation/Offer; Order/FulfillmentEvent; Review; Inbox; Outbox; UserNotification.
- After Order and Review exist, **one** nightly worker runs by leased schedule and stages buyer_profile_snapshot, seller_profile_snapshot and seller_buyer_profile_snapshot under one run_id, then atomically flips active_run_id. No extra daily/monthly stats table required in v1 if indexed qualified-order history supports exact nine-month window.
- The source-of-truth must store qualifying event instant, cancellation/reversal, actor and version to handle history/returns. Snapshot cutoff in Asia/Almaty converted to UTC for filtering.
- Initialize zero-history profiles by safe profile fallback (NO_DATA vs zero; document distinction); jobs need not create empty row for every inactive account if API can safely join parent account to current snapshot.
- Seller's private repeat-customer view cannot expose buyer activity at other sellers; phone from Web lead must not be matched to authenticated buyer.
- Schedule 02:30 Asia/Almaty is a configurable default, not SLA. If no Order/Review yet, profile may return NO_DATA.

## 6. Key implementation gates

| Gate | Executable evidence |
|---|---|
| G0 contracts | Enum/state chart, OpenAPI 3.1, Liquibase scripts aligned with this baseline |
| G1 reactive proof | Real PostgreSQL Testcontainers transaction: accepted offer → at most one Order + Outbox; rollback/409 concurrency |
| G2 public QR | Live seller/listing → QR → React view → anonymous callback → seller receives only Lead |
| G3 buyer transaction | Flutter login, buy/listing offer, order confirmation, cancellation race, receipt proof |
| G4 profiles | eligible product/seller reviews, nightly snapshot idempotency, stale snapshot fallback, per-seller privacy |
| G5 shipping | secure Web session if cabinet included; restore/backups, authz/PII, CI exact SHA, legal review KZ |

## 7. Open, not silently approved

OPEN-01 define counter count (one counter message proposed); OPEN-03 KZT rounding/quote expiry; OPEN-04 per-type fulfillment; OPEN-11 secure recovery without verified email; OPEN-12 pilot feature boundary; OPEN-13 receipt timeout/disputes; OPEN-14 seller staff roles (propose single owner-only seller in MVP). None should be implemented as if explicitly approved without independent S2 validation.

## 8. Review verdict

**Spec consistency after editorial fixes: improved, R0 not yet qualified.** Main still has no executable backend/frontend. This review validates document alignment only; cannot certify runtime, security, actual payment/fulfillment or user acceptance. Complete S0 decisions and S2 oracles before code changes.