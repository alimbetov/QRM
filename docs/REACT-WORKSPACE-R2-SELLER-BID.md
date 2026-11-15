# React Workspace R2 — consented SellerBid from delivered ML-R2 invitation

**Date:** 2026-11-14 · **Branch:** `docs/adr-002-jpa-architecture-review` · **Status:** implementation candidate; exact-SHA CI + independent S2 + real Keycloak/Browser/Flutter E2E required before enabling.

## Scope and original contracts

Extend existing `SellerDealsPanel` in React 19 + TypeScript. Do **not** create a new deal model. The authoritative chain remains:

```text
BuyerDemand(created, quantity/unit/city, expiresAt)
   + buyer's explicit invitation-sharing consent
   + SELLER_ADMIN in-app invitation opt-in
   + MlSellerInvitation(ELIGIBLE) committed as event
   + MlEventDispatcher -> ml_seller_invitation_inbox
        -> SellerBid Workspace: context (no buyer PII)
        -> choose published own SKU for each requested product
        -> choose active own InventoryLocation and PICKUP/DELIVERY
        -> compose immutable bid revision + exact KZT prices + fee
        -> SellerBidSubmissionService.submit(...) (no order, no reserve)
        -> buyer reviews quote, explicitly accepts
        -> CustomerOrder(PLACED), no stock reservation
        -> SELLER_ADMIN confirms -> real inventory reserve + outbox
```

## Server contracts

Both `zakaz.seller.bid-workspace.enabled` and `zakaz.ml.r2.consent-api.enabled` must be `true` for these controllers to register; both default **false**. Access additionally requires `SCOPE_deal:bid` and verified JWT issuer/subject tied to active `SELLER_ADMIN` membership.

| Operation | Method and route | Invariants |
| --- | --- | --- |
| Quote context | `GET /api/v1/sellers/{sellerId}/invitations/{invitationId}/quote-context` | Only delivered ELIGIBLE invitation to verified seller with *currently* enabled buyer consent & seller preference, no accepted/expired demand, and live catalog/territory. Returns immutable basket product names/HCC/quantity/unit, matching own published SKU variants, own active origin locations and available tracked stock. Never buyer ID, phone, email |
| Submit bid | `POST /api/v1/sellers/{sellerId}/invitations/{invitationId}/bid` | Checks same consent/ownership again, stale revision 409, exact FULL basket (no partial offers), unit, valid price scale, each requested SKU available at chosen origin, Pickup fee=0, active delivery territory. Existing `SellerBidSubmissionService` and `DealPersistenceService` remain the ONLY writers of revision+quote |

- `nextRevision` equals `MAX(revision)+1` per demand/seller; a browser retry after successful commit is **not idempotent** and correctly returns conflict. Client must not show success until server returns `201 {bidId}`. Following slice should add a durable idempotency receipt for network-uncertain retries.
- Advisory stock is not reserved on bidding: confirmation reserves under PostgreSQL locking. Demand acceptance only possible by verified buyer; no fake auto order.
- `ml_seller_invitation_inbox` is an internal consented notification. It does not send SMS/Push/email, and does not grant unrestricted seller access to all BuyerDemand rows.
- The pre-existing legacy authenticated `POST /api/v1/sellers/{sellerId}/demands/{demandId}/bids` still exists for independent commerce flows and is not automatically governed by the new invitation-only UI. Harmonizing its invitation requirement is a separate compatibility/security decision; do not silently break existing end-to-end tests.
- No new Flyway migration: reuse V5 (immutable demand/bid), V8 quote, V24 events, V25 consent/inbox. The new endpoints are read-only except transactional bid append.
- Bounded: <=50 demand lines, <=20 matching SKU per product and <=40 own active locations per preview. R2 initial max 100 visible ML inbox entries. For large merchants replace N+1 SKU stock reads with a batched projection and keyset pagination before load qualification.

## React UI

`SellerDealsPanel` continues orders, guest lead workflow and ML invitation tabs. Under separate `VITE_SELLER_BID_WORKSPACE_ENABLED=true`, each invitation receives **Ответить ставкой**; lazy-load detailed basket only after seller click. Seller selects a **compatible own SKU**, actual active inventory location and pickup/delivery, inputs per-unit KZT prices and fee; only exact complete basket can be posted. The UI shows provisional available units by origin (not a reservation), buyer budget as guidance, revision and expiry. Use string KZT decimals and BigInt-based quantity comparison, never JS float for stock. Public Web/Flutter /q and /buy remain unchanged. Seller token stays in seller SPA session memory, not guest WebView.

## Tests and gates

- **PostgreSQL Testcontainers + MockMvc:** real buyer demand, ML invitation decision/event, at-least-once in-app delivery, seller context without PII, server-owned SKU/location and exact KZT, bid and quote insert, buyer acceptance, seller confirmation and single reservation; cross-seller, revoked consent, absent inbox, stale revision, wrong SKU and insufficient stock paths. No fake DB mocks.
- **Vitest:** `seller-bid-api.test.ts` verifies authenticated route, 201-only receipts, exact decimal validation, unavailable stock, replay/conflict 409, bad response and forbidden data.
- **Playwright staging:** requires operator-created real Keycloak seller+buyer, public listing, buyer demand and consent, delivered invitation, funded location and actual staging browser; normal CI only lists test declarations. Flutter seller AppAuth and physical Android/iOS still need on-device E2E.
- **Release HOLD:** independent S2 APPROVE, privacy/locking/concurrency review, load p95/p99 and multi-node worker, real RustFS/ClamAV, SMTP/Keycloak and dual-user Browser+Flutter E2E. Do not enable flags in default staging/prod without operator authorization.

## Open R2.1 requirements

1. Seller-friendly stock location selection/filtering and fulfillment terms preview; multi-line basket tests and insufficient stock race after preview.
2. Buyer UI opt-in toggle (per demand) and SELLER_ADMIN preference UI, no automatic enrolment.
3. Seller product picker + stock setup and deterministic idempotency key on seller bid POST.
4. Complete two-browser Playwright buyer accept → Seller Studio reserve and negative consent/no-stock cases.
5. Independent S2 review must be external to the implementation author; CI PASS alone is not APPROVE.

## R2 consent UI + acceptance evidence

The first bid-only baseline `780f6afcabce19007e8fa549c9c6e642607cf38e` passed all four workflows; Java fast **106**, real PostgreSQL **105** tests PASS, incl. `SellerInvitationBidWorkspacePostgresIT` **3/3** verifying delivery→bid→buyer acceptance→seller confirmation/reservation and deny/stock failures; JaCoCo **89.99% line**, **63.46% branch**. React Vitest/build, Flutter and Staging Configuration PASS. This is not a real-browser certification.

Added optional **`VITE_ML_R2_CONSENT_UI_ENABLED=false`** buyer/seller consent views and owner-only GET status endpoints. `GET /api/v1/buyers/demands/{demandId}/invitation-sharing` requires verified buyer + deal:write; `GET /api/v1/sellers/{sellerId}/invitation-preference` requires verified SELLER_ADMIN + deal:bid. Both return `{"enabled":boolean}`, missing means false, other owner denied. Existing PUT APIs perform actual opt-in/opt-out. Buyer consent is **per demand** and never implied by account registration; seller choice is per organization and remains separate. No phone/marketing permission is inferred, and setting a flag does not automatically generate a candidate, send Push, or contact the buyer.

Operator-only `web/e2e/staging-seller-bid.e2e.ts` accepts real seller credentials, a preprovisioned delivered invitation, own published SKU and stock; it is enumerated but **not executed by CI**. It validates actual React quote selection and POST 201. Full dual-browser flow still needs a server-side invitation evaluation scheduler/candidate finder with bounded consent/coverage, a complete seller stock/location setup UI and verified buyer Keycloak session. **Do not represent the current bid composer as autonomous discovery or automatic invitation delivery.**

To enable in private staging after S2 review: Backend `ZAKAZ_SELLER_BID_WORKSPACE_ENABLED=true`, `ZAKAZ_ML_R2_CONSENT_API_ENABLED=true`, `ZAKAZ_ML_R2_DELIVERY_ENABLED=true` (only internal event projection); React `VITE_SELLER_BID_WORKSPACE_ENABLED=true`, `VITE_ML_R2_CONSENT_UI_ENABLED=true`. Evaluate and deliver invitations under a separate trusted scheduler/workflow, never via a public invitation creation endpoint. New backend and web flags default **OFF**.


## R3 additive hardening — native clients, legacy write isolation and idempotency

The same working branch contains a native Flutter SellerBidComposer, native buyer invitation-sharing and seller in-app preference controls, and regression cases. The native seller form uses separate AppAuth, server-authorized quote-context, manual SKU and stock-location selection, exact-decimal calculations using BigInt, explicit submission confirmation, and no buyer PII in seller projection. It is gated by ZAKAZ_SELLER_BID_WORKSPACE_ENABLED=false (Dart compile-time flag).

The invitation-only Backend flag closes the legacy POST /sellers/{sellerId}/demands/{demandId}/bids path when zakaz.seller.bid-workspace.enabled=true: callers must use the consented invitation endpoint instead of bypassing current buyer and seller opt-ins. The old API remains available under the OFF-by-default compatibility setting. PostgreSQL authorization regression added.

Flyway V27 creates seller_bid_http_receipt: durable, transactionally committed idempotency receipt keyed by seller, invitation, and Idempotency-Key. Client passes a stable key and identical JSON terms for uncertain-network retries. Identical replay returns the same persisted bid identifier; changed payload with same key returns 409. Calls without a key retain old fail-on-revision behavior; no order or reservation is created. Fingerprint is SHA-256 of the Java request representation. This design does not promise replay after an invitation has expired or been revoked, or the demand was accepted; authorization is checked on every call.

Test additions: PostgreSQL duplicate request and changed terms, old API consent-bypass denial, Flutter consent API checks, native success/conflict/expired invite tests. These are code-level tests, not evidence of physical Android/iOS AppAuth UAT or external staging Playwright execution.

Deployment gate: Keep backend/React/Flutter bid flags disabled until exact-SHA CI passes, independent S2 review (transaction locks, consent revocation, fingerprint, Flyway V27), and a two-role live Keycloak+PostgreSQL E2E. Legacy direct bid API should eventually be restricted unconditionally after remaining non-invitation workflows are explicitly separated under a trusted internal policy.


## R3 autonomous candidate discovery (code candidate, qualification pending)

A separate, bounded internal candidate scanner now closes the previously manual BuyerDemand-to-invitation step. It is enabled ONLY when both existing zakaz.ml.r2.invitation-evaluation.enabled=true and the new zakaz.ml.r2.candidate-scanner.enabled=true; default is OFF in application.properties and staging Compose. It does not send Push/SMS/email. The scheduler defaults to a 24-pair batch every 5 seconds; configurable batch range is 1 to 100.

The scanner considers only unexpired, unaccepted buyer demand with explicitly enabled buyer-sharing consent and seller in-app preference, published compatible tracked SKU, active SELLER_ADMIN membership, active territory/pickup, and enough unreserved stock to supply the COMPLETE basket at a single seller location. Rechecks remain in MlSellerInvitationService.evaluate and MlEventProjectionSink; the existing PostgreSQL unique demand/seller/policy decision and transactional event queue enforce duplicate suppression under concurrent scanner pods. ML-R2 delivery worker is separate and must be explicitly enabled to populate Seller Inbox.

PostgreSQL tests validate successful automatic discovery and current consent, zero-stock and seller-opt-out suppression. The implementation still supports only policy version 1 and does not automatically retry invitations whose earlier historical decision was suppressed or whose delivery was missed due to revoked consent; policy-version/re-evaluation rules and multi-pod throughput must be reviewed before production. Operator must verify a naturally generated invitation in live dual-user staging, without manual SQL/service calls, before declaring USER_E2E_VERIFIED.

## R2 qualification hardening — exact invitation lookup and inbox depth

The initial `SellerInvitationBidWorkspaceService.context()` used `MlInvitationConsentService.listSellerInbox(seller,100).stream().noneMatch(...)`. This made a consented, not-expired invitation inaccessible once 100 newer seller-inbox records appeared. It was an **incorrect security and functional dependency on an arbitrary UI page limit**, not a legitimate expiration rule.

The fix introduces a targeted `isSellerInvitationVisible(sellerId,invitationId)` using the **same live consent, membership, catalog, fulfillment and acceptance predicates** as the list, filtered by `invitation_id` *before* `LIMIT 1`. The list retains its bounded 1..100 cap. Existing seller identity/JWT, buyer sharing and SellerBid transactional rechecks are unchanged.

The real PostgreSQL regression `olderActiveInvitationRemainsUsableBeyondNewestHundredInboxRows` creates a genuine consented delivered candidate via the existing ML event mechanism, then inserts 101 additional synthetic SQL inbox rows solely as a deterministic pagination test fixture; it asserts the original is absent from the newest 100, remains accessible through quote-context, and becomes inaccessible immediately after buyer consent is revoked. Synthetic rows are **not** evidence of real notification/ML consumer delivery.

This fix does **not** qualify external seller/buyer browser E2E, Android/iOS, multi-pod delivery, live RustFS/ClamAV, load tests or independent S2. GitHub exact-SHA CI must be verified after committing it.

## R2 dual-browser acceptance without REST shortcuts — operator-run

`web/e2e/staging-r2-dual-browser.e2e.ts` exercises two **separate** browser contexts with genuine Keycloak Authorization Code/PKCE, never copies either JWT into the other context and never uses Playwright `request.get/post` to create demands, bid, acceptance or order. All business actions use actual React controls:

1. Verified SELLER_ADMIN signs in at `/seller`, selects their organization and explicitly enables in-app invitations.
2. Verified BUYER signs in at `/buy?productId=<fixture>&unit=PCS`, creates an actual BuyerDemand and explicitly enables sharing of that one demand.
3. Real scanner + ML-R2 worker must evaluate and deliver the matching invitation; the seller UI polls its actual inbox.
4. Seller selects its matching published SKU at an active origin, enters exact KZT price and submits SellerBid. Buyer explicitly accepts, creating `PLACED`.
5. Seller opens own orders and confirms the *specific public order number* received by buyer. Buyer refreshes and sees `CONFIRMED`. PostgreSQL reserve/outbox invariants remain the responsibility of DB integration tests and an operator read-only DB witness.

**Execution gate:** `STAGING_R2_DUAL_BROWSER_UAT=true` plus private `STAGING_R2_PRODUCT_ID`, `STAGING_R2_SELLER_ID`, `STAGING_R2_SELLER_SKU_CODE`, `STAGING_R2_UNIT_PRICE`, `STAGING_BROWSER_BUYER_USERNAME`, `STAGING_BROWSER_BUYER_PASSWORD`, `STAGING_SELLER_BROWSER_USERNAME`, `STAGING_SELLER_BROWSER_PASSWORD` and a real `STAGING_BASE_URL=https://...`. Operator must provision verified user accounts and membership, active public physical SKU with sufficient actual unreserved inventory, active pickup/territory, and run with separate seller/buyer consent UI flags, seller studio and bid workspace flags, candidate scanner + delivery worker enabled. No blanket activation of these flags in production.

Invoke explicitly from a provisioned secure staging environment:

```sh
cd web
STAGING_R2_DUAL_BROWSER_UAT=true npm run e2e:staging -- --grep "real buyer and seller browsers"
```

Default CI only lists Playwright declarations; it neither provisions live users nor runs this test, therefore there is **NO USER_E2E_VERIFIED claim** until the operator attaches actual test trace, linked PostgreSQL witness, clean absence of leaked PII, and independent S2 sign-off. Test credentials must not be committed to GitHub or CI artifacts.

## R2 exact-SHA CI evidence: 5455bc1 (2026-11-14)

**Fix/hardening commits:** `13c6abe937b319ccf1f08c7895ba05f29a29c888` initially detected SQL text-block concatenation defect and was intentionally **CI RED** (PostgreSQL malformed `$2ORDER`). The corrected `5455bc1e4ad876b104b811d775f3071f449e16d9` completed all **four** GitHub Actions workflows **SUCCESS**:
- Java 21 fast: **106 tests, 0 failures/errors**.
- Real PostgreSQL Testcontainers: **111 tests, 0 failures/errors**, including `SellerInvitationBidWorkspacePostgresIT` **8/8 PASS** with the newer-101-inbox regression.
- React Web UI: Vitest, TypeScript production build and Playwright `--list` **PASS**. The operator-only dual-browser test is discovered, **NOT executed** in normal CI.
- Flutter Mobile + Staging Configuration: **PASS**.
- JaCoCo total **89.75% lines**, **63.81% branches**.

This certifies a CODE/DB/API regression slice only, **not** independent S2 approval or live two-user browser/device UAT. Any later HEAD includes independent parallel changes and must be checked at its own SHA. Keep all feature flags OFF until dedicated release qualification.
