# QRM — Test Strategy, Acceptance Gates & Release Qualification

**Status:** specification only. No tests executed by creation of this document.

## 1. Quality protocol

S0 Scenario Design → S1 executable RED tests → S2 independent Test Review → S3 implementation GREEN → S4 regression, security, native UX and release qualification. Tests run against exact commit SHA. Red tests must fail for **expected functional absence**, not setup errors. Review business oracles before GREEN.

## 2. Automated layers

| Layer | Stack | Purpose |
|---|---|---|
| Unit | JUnit 5, AssertJ, Reactor Test StepVerifier | invariant calculation, reactive signals, pure rules |
| DB integration | Testcontainers PostgreSQL, Spring Data R2DBC, Liquibase | actual SQL schema, transactions, isolation, locking, indices |
| API contract | WebTestClient, OpenAPI validator, security tests | verbs, status, DTO, problem details, authz |
| Architecture | ArchUnit | dependency direction/domain isolation; no JPA |
| React | component tests, Playwright | public pages, lead, seller inbox, QR login |
| Flutter | unit/widget/integration | favorites, negotiations, App Links, notifications |
| System/E2E | deployed backend + PostgreSQL + S3 compatible + controlled FCM stub | real flows, retries, QR redirects |
| Load/recovery | k6 or equivalent, crash/restart/restore | SLA, outbox and schedule recovery |

## 3. Critical matrix by module

- **Identity:** password hash, brute force/rate limit, unverified phone, unique account, refresh replay/rotation, verified-email recovery, authz, session revocation. No SMS/OTP.
- **Seller:** upload/replace logo, old asset cache invalidation, slug uniqueness, seller ownership.
- **Catalog:** category permission, locale fallback, photo 3–5 publication guard, archive/unlisted access.
- **Pricing:** 1/9/10/49/50/199/200 boundaries, non-overlapping tiers, decimal rounding KZT, fixed vs percent, snapshot.
- **Discount:** DST/timezone, start inclusive/end exclusive, concurrent schedule overlap, activation after downtime, push on real reduction only.
- **QR:** unmodifiable URL after listing edit, source attribution, QR invalid/unlisted/archived handling, no open redirect.
- **Web Lead:** phone/callback consent, anti-abuse and secure withdrawal, no Order created on callback, seller PII permission.
- **Flutter Negotiation:** rounds 1..5, forbidden 6, accept/counter/reject/withdraw/expiry, race accept-vs-withdraw, after-agreed create once.
- **Order:** one-time conversion, idempotency keys, immutable price snapshots, buyer cancel and cancellation request, race dispatch, role transitions, no invented paid flag.
- **Product types:** physical delivery vs service completion vs digital provision, claim eligibility, type-specific data.
- **Favorite:** only authenticated user, unit/quantity price watch, same preference across sessions, archive retained correctly.
- **Notification:** durable internal record, duplicate Outbox event, independent read state, optional best-effort Push, revoke token, marketing opt-out, failed FCM retry, no storefront outage.
- **Inbox:** read/unread/bookmark filters + counters, per-user isolation, event version race, cursor pagination and indexes.
- **Analytics:** no re-render counting, dedup 24h, QR source correctness, bots/excess clicks, privacy retention, nonblocking ingestion.
- **Review:** verified transaction only, one order-one review, photo moderation, post-return review permitted, cancellation does not rate product.
- **Security:** IDOR matrix, private URLs, CSRF, QR challenge replay, upload protection, PII log leakage.

## 4. Key concrete scenarios and oracles

| ID | Setup/action | Oracle |
|---|---|---|
| P0-PRICE-001 | purchase quantity exactly tier boundary | exactly one effective unit price |
| P0-DISC-001 | concurrent overlapping discount creation | one accepted, one rejected, no conflicting price |
| P0-WEB-001 | unauthenticated guest submits Web callback | callback lead only; no order/negotiation agreement |
| P0-NEG-001 | seller accepts offer, then creates order | AGREED before order; one acceptedOfferId → one Order |
| P0-NEG-002 | two create-order requests concurrently | same single persisted order / deterministic conflict |
| P0-ORD-001 | buyer cancels PLACED before seller confirms | seller cannot confirm cancelled order |
| P0-ORD-002 | seller begins processing vs buyer cancel | valid single outcome with audit; no illegal state |
| P0-INB-001 | mark read at version N, inbound event N+1 races | remains unread for N+1 |
| P0-PUSH-001 | discount worker restarts/replays event | at most one logical notification for same watcher/event |
| P0-AUTH-001 | QR login challenge replay/other browser | denied, no Web session leak |
| P0-SEC-001 | seller A requests seller B's lead details | no phone disclosure |
| P0-R2DBC-001 | tx creates Order but outbox insert fails | both rollback |
| P0-R2DBC-002 | blocking provider call on event-loop | architecture/static test or BlockHound-inspired test rejects blocking |
| P0-ANA-001 | analytics insert fails | public page still returns normally |

## 5. Release evidence requirements

Per PR: scope, approved decisions and scenarios, RED evidence, S2 reviewer verdict, GREEN and regression results, DB migration check, security tests, exact commit SHA. Release: complete E2E on actual Web and Flutter Android/iOS, backup/restore rehearsal, tracing and alert checks, authorized test of real S3 storage, load profile, explicit known limitations and rollback plan.

## 6. Gate for R0 → R1

- Approved versioned requirements and ADRs, scoped bounded contexts and consistent ER.
- State matrices and event schemas, draft OpenAPI.
- Business test-oracles reviewed independently.
- Type-specific fulfillment/claim rules, offer round semantics, session/callback privacy decisions resolved.
- Prioritized execution slices, exact resources/owners identified.

## Nightly buyer/seller profile test suite

NPA-01…NPA-20 из [NIGHTLY-PROFILE-AGGREGATION.md](NIGHTLY-PROFILE-AGGREGATION.md) обязательны для отдельного RED → S2 Review: replay, month boundary, snapshots, lease fencing, concurrent writes, ratings moderation, buyer/seller role independence, seller customer privacy and profile fallback. Никакой профиль не показывает искусственно свежие метрики при неудаче job.

## DEC-27 acceptance

All AUTH-01…10 and NOT-01…10 from [AUTH-NOTIFICATIONS.md](AUTH-NOTIFICATIONS.md) are mandatory RED→S2→GREEN acceptance tests; special attention to account takeover without confirmed phone, password reset recovery, QR approval, push disabled and Outbox replays.

## Post-review gates

[TECHNICAL-REVIEW-MVP.md](TECHNICAL-REVIEW-MVP.md) задаёт единые P0/P1 границы. Дополнительные RED оракулы: Order и Outbox атомарно; buyer-only explicit create-order (до S2), подтверждение исполнения не равнозначно получению, отдельные Seller/Product reviews, no-rating null, один snapshot при повторном ночном пересчёте, web seller cabinet не выпускается без QR auth, phone unverified не доступен для восстановления, internal Notification Center сохраняется при отказе Push.
