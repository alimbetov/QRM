# Seller Deals R2 — Seller Studio / Seller Inbox (implementation slice)

**Status:** committed candidate; exact-SHA CI required; real Keycloak/Android/iOS browser E2E and independent S2 remain OPEN.  
**Branch:** `docs/adr-002-jpa-architecture-review` (no new branch, no merge to main).  
**Date:** 2026-11-14.  
**Dependencies:** [React Workspace R1](REACT-WORKSPACE-R1-IMPLEMENTATION.md), [QA-03 Journey Audit](QA-03-USER-JOURNEY-AUDIT.md), [R2 Commerce](R2-3-QR-FIRST-E2E-5K-GATES.md).

## Goal and baseline

Make the already implemented and PostgreSQL-tested seller confirm/cancel flows accessible through the existing React workspace and a separate native Flutter seller screen. Do not create a second order/reservation module, do not expose buyer PII, and do not use Flutter WebView for seller OIDC.

The existing R1 draft/listing management remains in `web/src/SellerWorkspace.tsx`. R2 adds:
- verified seller organization membership discovery (server-sourced, no client-supplied owner proof);
- seller-scoped paginated order list, immutable economic line totals and statuses;
- seller CONFIRM (atomic reservation) and CANCEL (atomic release + order outbox), no invalid transitions;
- an audited callback inbox with terminal CONTACTED or DECLINED disposition;
- existing ML-R2 in-app invitation inbox as a read-only optional section. **No bid-entry UI, no automatic seller outreach.**

## Backend endpoints and auth

| Method | Endpoint | Server controls |
|---|---|---|
| GET | `/api/v1/sellers/me` | scope `order:confirm`, verified JWT issuer+subject; only active SELLER_ADMIN memberships; no user-controlled seller ID |
| GET | `/api/v1/sellers/{sellerId}/orders?page=0&size=20&status=ALL` | scope `order:confirm`, `requireSellerAdmin`, max size 20 and page 1000, fixed ORDER BY, no buyerId, email or phone |
| POST | `/api/v1/sellers/{sellerId}/orders/{orderId}/confirm` | existing `OrderConfirmationService`, only seller-owned PLACED, inventory transaction and outbox |
| POST | `/api/v1/sellers/{sellerId}/orders/{orderId}/cancel` | existing `OrderCancellationService`, seller-owned PLACED/CONFIRMED, exactly-once reservation release and event |
| GET | `/api/v1/sellers/{sellerId}/leads?page=0&size=20` | existing `SellerLeadInboxService`, decrypt only under SELLER_ADMIN scope `leads:read`; immutable `LeadReadAudit` per disclosure |
| PUT | `/api/v1/sellers/{sellerId}/leads/{leadId}/status` | same SELLER_ADMIN + scope `leads:read`, body `{"status":"CONTACTED"}` or `DECLINED`, 204 on success/replay, 409 conflicting replay, 404 missing/foreign lead |
| GET | `/api/v1/sellers/{sellerId}/invitations?limit=24` | existing optional ML-R2 consent + seller preference gating, scope `deal:bid`; no auto-SMS/Push |

Seller account and orders APIs require `zakaz.seller.studio.enabled=true`, **OFF by default**. Seller listing management separately requires `zakaz.seller.workspace.enabled=true`, OFF by default. Public QR callback requires `zakaz.qr.http.enabled=true`. ML invitation inbox requires `zakaz.ml.r2.consent-api.enabled=true` and real consent; this flag is independent and OFF by default.

Order data is read through bounded `SellerOrderReadService` / JDBC. Only seller ID in SQL predicate is from verified membership. Economic values are decimal strings; order lines are immutable. `GET /sellers/me` is denied without verified JWT issuer+subject and appropriate scope. The frontend never uses route IDs as authorization.

## Guest callback lifecycle

**NEW → CONTACTED or DECLINED**; these are terminal seller acknowledgments, **not** evidence that a phone call actually connected, nor payment/order creation. NEW = absent workflow record. Migration `V26__seller_lead_workflow.sql` adds a separate table `qr_lead_workflow` with immutable row after the first disposition, actor issuer/subject and update timestamp. The encrypted `qr_purchase_lead` and consent source stay untouched.

The handler first verifies seller admin membership, then locks the parent lead row using PostgreSQL `FOR UPDATE`, verifies real listing ownership, and inserts the terminal disposition atomically. Repeating the same disposition returns 204, conflicting disposition returns 409. Cannot reopen the lead. Seller Inbox still logs every disclosure of decrypted phone text. No phone is written to the new status row.

No client may mark a foreign lead; cross-seller access returns a denial. The UI only loads guest phones after an explicit click on «Обратные звонки». Do not transmit phones to analytics or the ML feature pipeline. Do not permit use for marketing without separate consent.

## React / Flutter and identity isolation

**React:** `SellerWorkspace` reuses separate Keycloak seller public SPA client/PKCE, adds authorized organization selector, `SellerDealsPanel` tabs: «Заказы», «Обратные звонки», «Приглашения». Status/replies are read from Backend; no optimistic fake success. Confirmation/cancellation and terminal callback disposition require explicit human confirmation.

**Flutter:** `SellerAuth` uses separate mobile public OIDC client/PKCE, external browser redirect and in-memory session; `SellerInboxScreen` is a **native screen** linked from NativeCatalog and HybridCatalog under `ZAKAZ_SELLER_STUDIO_ENABLED=true` default OFF. It uses the same seller APIs; no tokens enter the embedded public React WebView or query parameters. The WebView allowlist continues to block `/seller`.

**Environment example for private staging**:
```bash
# Backend server (via environment / Spring relaxed binding)
ZAKAZ_SELLER_STUDIO_ENABLED=true
ZAKAZ_SELLER_WORKSPACE_ENABLED=true
ZAKAZ_QR_HTTP_ENABLED=true
# prerequisite: approved JWT decoder, scoped seller OIDC clients, membership

# React build
VITE_SELLER_WORKSPACE_ENABLED=true
VITE_SELLER_OIDC_AUTHORITY=https://staging.example/identity/realms/zakaz-staging
VITE_SELLER_OIDC_CLIENT_ID=zakaz-seller-web

# Flutter
--dart-define=ZAKAZ_SELLER_STUDIO_ENABLED=true
--dart-define=ZAKAZ_OIDC_ISSUER=https://your-idp.example
--dart-define=ZAKAZ_SELLER_OIDC_CLIENT_ID=zakaz-seller-mobile
--dart-define=ZAKAZ_SELLER_OIDC_REDIRECT_URI=kz.zakaz.mobile:/seller-oauthredirect
--dart-define=ZAKAZ_SELLER_OIDC_LOGOUT_REDIRECT_URI=kz.zakaz.mobile:/seller-logout
```

The Android AppAuth manifest/Info.plist must register these **exact** additional redirects. The repository still has no checked-in native runners or real APK/IPA UAT. The default buyer experience remains unchanged when flags are OFF.

## Test coverage, release gates

- **Backend PostgreSQL:** `AuthenticatedDealHttpPostgresIT.sellerStudioListsOnlyOwnedOrdersAndKeepsMoneyAsDecimalStrings`: own/foreign orders, untrusted JWT, exact KZT strings, filtering, authorization, confirm→reserve→cancel→release.
- **Backend PostgreSQL:** `SellerLeadInboxPostgresIT.onlySellerAdminCanReadEncryptedGuestContactWithAuditableAccess`: encrypted guest Lead, recorded read, CONTACTED/duplicate 204, conflicting DECLINED 409, foreign seller denial and NEW→CONTACTED visible.
- **React Vitest:** `seller-deals-api.test.ts`: trusted memberships, owner-scoped order response, stable decimal strings, 204-only mutation, fail-closed 403/409, explicit lead handling, optional invitation gating.
- **Flutter unit/widget:** `seller_inbox_screen_test.dart`: separate auth boundary, order confirmation, contacts only after click, manual disposition and server refresh; no fake terminal state on 403/409.
- **Real Playwright:** `web/e2e/staging-seller-deals.e2e.ts` requires `STAGING_SELLER_DEALS_UAT=true` and disposable authorized order/callback fixtures. It is NOT run by default CI.
- **Real mobile:** operator Android/iOS native AppAuth login, URI callback, admin membership, confirm/cancel and callback disposition still require separate on-device execution.

**Do not claim production READY until** independent S2 review, real Keycloak seller and buyer OIDC, real storage and SMTP tests, HCC/stock validity, two-worker outbox, physical Android/iOS and complete buyer→seller fulfillment UAT. This R2 slice does **not** implement SellerBid entry, 5-round bargaining, shipping, receipt/returns, actual phone verification, push delivery or ML model inference.

## Next P0 slice

Seller Demand/Bid Workspace: verified buyer consent + seller preference → server-projected eligible demand details (HCC, required quantities, city, no PII) → seller selects its own compatible SKU and authorized inventory location → creates a valid SellerBid with all-in KZT and immutable revision → buyer explicitly accepts → Seller Studio sees PLACED → confirms → reserve. Write genuine PostgreSQL RED, S2 review then GREEN and real browser/Flutter E2E.
