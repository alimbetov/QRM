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
