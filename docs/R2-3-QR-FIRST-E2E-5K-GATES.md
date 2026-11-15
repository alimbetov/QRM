# R2.3 — QR-first Buyer E2E → 5K Concurrency Qualification

**Date:** 2026-11-13. **State:** R2.3 HTTP/React QR vertical slice implemented behind default-OFF flags, PostgreSQL MockMvc E2E is available; full HTTPS browser/mobile E2E, external OIDC and the 5000-VU benchmark remain OPEN. See [R2.3 implementation](R2-3-HTTP-API-QR-JOURNEY.md).

## Two distinct buyer paths — do not confuse them

**QR guest/public lead path (DEC-BUYER-WEB-001):**
1. Seller creates a PUBLIC listing with canonical SKU(s), visible multilingual description, valid unit and approved seller association.
2. Listing gets a stable opaque public token and HTTPS QR link; do not put seller/account secrets or phone in the URL.
3. Guest scans QR on phone; a public `GET` returns only safe published fields; UNLISTED is direct-link only, ARCHIVED/DRAFT 404. Seller attribution is logged server-side once, not trusted from client query string.
4. Guest submits callback/price request. Store an authorized/rate-limited `PurchaseLead` with consent, phone and source QR, **NOT a CustomerOrder**. Phone verification/recovery policy must follow the separately approved auth decision (do not silently require SMS for every login or allow identity takeover).
5. Seller can contact guest via an approved channel; future guest order creation needs its own risk, legal identity and fulfillment policy. Do not pretend purchase completed on a public lead.

**Authenticated Buyer / Smart Deal path (current internal foundation):**
1. Verified buyer creates `BuyerDemand` (canonical product, explicit unit, quantity, city, expiry).
2. Verified seller submits `SellerBid` and immutable all-in KZT quote. Hard constraints qualify public SKU and delivery coverage.
3. Buyer explicitly accepts a specific seller-bid revision, idempotency key => one `CustomerOrder(PLACED)` with frozen terms and **zero inventory reservations**.
4. Verified SELLER_ADMIN confirms, row-locks inventory, reserves *all* order lines, writes audited movement + `ORDER_CONFIRMED` Outbox in the same transaction.
5. Outbox worker projects/delivers once to business consumer despite at-least-once transport. Optional seller cancellation releases all reservations and writes `ORDER_CANCELLED_BY_SELLER`. Buyer cancellation and returns remain a separate gated slice.

## Test-first acceptance roadmap

| ID | Expected executable RED test | Status |
|---|---|---|
| QR-01 | Public seller token resolves PUBLIC listing; no draft/unlisted leak | PostgreSQL MockMvc integration PASS; UNLISTED transition not exercised |
| QR-02 | Unknown/expired QR → no private data | Unknown token 404 PASS; expiry policy not implemented |
| QR-03 | Guest submits lead and never auto-creates order | PostgreSQL HTTP PASS, AES-GCM and quota |
| QR-04 | QR attribution survives later authenticated buyer order, cannot be forged | Not implemented |
| QR-05 | Verified buyer identity and seller ownership enforced through signed JWT | Actual HTTP routes, mock JWT decoder + DB identity IT; external OIDC UAT pending |
| QR-06 | One seller bid acceptance yields one order, same-key replay returns same order | HTTP + PostgreSQL IT PASS |
| QR-07 | Confirmed multi-line order reserves exactly once or rolls back all | PostgreSQL IT tested |
| QR-08 | Seller cancellation releases once and durable Outbox produced | PostgreSQL IT tested |
| QR-09 | Two independently deployed outbox workers restart during delivery without extra projection | Not independently deployed |
| QR-10 | React/Flutter API journey with actual HTTP traffic and mobile viewport | Live React /q route and TypeScript build; browser + Flutter E2E still OPEN |
| PERF-01 | 5000 concurrent distinct confirmed orders, RPS/p95/p99/error, DB locks, CPU/heap | Harness prepared, **not executed** |
| PERF-02 | Contention for same last SKU, exactly one success; no oversell | Small 2-thread PostgreSQL test only |
| PERF-03 | 5000 concurrent sessions + nightly Intelligence (resource isolation) | Not executed |

## Load test prerequisites and fairness

- Stable S2-approved private test environment with real JWT issuer/JWKS, no public production transactions; mTLS/HTTPS, test companies and seeded anonymous buyer accounts.
- 5000 **distinct pre-created PLACED orders**, per-order explicit seller authorization, SKU/warehouse and expected outcome; never reuse one order as though it were 5000 different purchases.
- REST `POST /api/v1/sellers/{sellerId}/orders/{orderId}/confirm` is now **implemented behind `zakaz.commerce.http.enabled=true`**; must pass independent S2/real OIDC plus staging qualification before public enablement.
- Load script: [k6 R2 confirmation](../perf/k6/r2-confirm-reserve.js), one confirmation per virtual user; attach immutable order fixture and do not commit real tokens. Compare separate low-contention and oversell-contention datasets.
- Capture p50/p95/p99 response time, achieved RPS, CPU, JVM heap/GC, PostgreSQL active connections, lock waits/deadlocks, Hikari pending connections, Outbox backlog age and errors. Pin DB CPU/RAM/PG version, DB pool sizes, test dataset and machine types. Integrate OpenTelemetry/Micrometer after S2.
- AFTER the run verify SQL invariants: zero negative stock, `reserved<=on_hand`, reservations match line/order/seller, no duplicate outbox order-confirmed, all accepted orders in correct state, no orphan actions. See [post-run checks](../perf/k6/post-run-integrity.sql).
- Report actual timestamp, commit SHA, config, VUs, RPS/p95/p99, CPU/memory, errors, correctness, failures and reproduction instructions. Do not infer 5000 capability from 2-thread test results.

## Product gate

Keep current React demo explicitly labeled; do not call static front-end items live catalog. QR link + public landing + callback PurchaseLead is implemented as a gated HTTP/React slice, with seller QR PNG and audited lead inbox; authenticated commerce is available as gated REST and PostgreSQL IT. Real browser, staging IAM, production privacy/anti-abuse review and 5K fixtures remain before this becomes a qualified buyer checkout.

**No merge to main before independent S2 Review and R2.3 E2E test approval.**
