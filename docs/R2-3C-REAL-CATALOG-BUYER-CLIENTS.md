# R2.3C — Live Catalog + Authenticated Buyer Web + Minimal Flutter

**Date:** 2026-11-13. **Branch:** `docs/adr-002-jpa-architecture-review`, Draft PR #2.
**Status:** code committed; independent S2, real OIDC and device/browser UAT OPEN. No merge to `main` without gates.

## HTTP/DB implementation

### Public catalog
- `GET /api/v1/public/catalog?query=...&categoryCode=...&page=0&size=12`, optional query/category; page 0..1000, size 1..24; deterministic order `canonical_name, listing_id`.
- `GET /api/v1/public/catalog/categories` lists categories with at least one public listing, bounded to 100.
- Both endpoints are behind default-OFF flag `zakaz.catalog.http.enabled=true` (separate from QR and commerce flags). They are anonymous READ-ONLY when enabled.
- PostgreSQL read projection joins `catalog_listing`, `catalog_product`, `catalog_category`, `qr_public_link` with hard-coded `status='PUBLIC'` and active category. `UNLISTED`, `DRAFT`, `ARCHIVED` never appear in search. Separate batch query fetches variants for one page, avoiding Hibernate N+1.
- Public response fields: listingId, canonical productId, sellerId, random public qrToken, title, category/categoryCode, productType, SKU id/code/unit, optional `priceKzt` string. No phone, buyer ID, inventory stock, warehouse, seller contact, secret membership data.
- Seller display name, city, images, detailed localized descriptions, facet predicates and verified stock are **not modeled for this endpoint**, so none are invented. Search is name-based only; do not claim full-text index or sophisticated filtering.
- Price is an indicative published unit KZT price; **NOT** a final bid, delivery quote or checkout price.
- Validate bounds & search characters, parameterized JDBC SQL; no arbitrary client SQL sort.

### Buyer authenticated quote discovery
- `GET /api/v1/buyers/demands/{demandId}/bids` behind default-OFF `zakaz.commerce.http.enabled=true`; requires `SCOPE_deal:write` and **server-provisioned active buyer_account_link**, checked against demand owner.
- Returns at most 50 live latest-per-seller immutable bid revisions and all-in KZT quotes, delivery fee/method and line snapshots; no seller warehouse address or private account identity.
- Existing `POST /api/v1/buyers/demands`, `POST /api/v1/buyers/demands/{demandId}/bids/{bidId}/accept`, `GET /api/v1/buyers/orders/{orderId}` are **reused**. Same acceptance service/idempotency, no second Order engine.
- Acceptance creates `CustomerOrder(PLACED)` **without inventory reservation**; seller ADMIN later confirms and reserves all lines atomically. Real seller bidding/confirmation UI remains absent.

## React changes
- `/` replaced the static demo catalog with `LiveCatalog` backed by HTTP. Error / empty / loading are honest; no demo fallback, no local fake favorites or invented ratings.
- `/q/{token}` retains guest callback (PurchaseLead, not an order).
- `/buy?productId=...&unit=...` requires OIDC Authorization Code + PKCE using `oidc-client-ts`, then creates BuyerDemand, reads live bids, explicitly accepts one, reads order. An accepted quote has a stable in-memory idempotency key on retries; owner and economic verification remain server-side.
- `/auth/callback` completes authorization. Required build-time values: `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`. SPA public client redirect `https://<approved-web-origin>/auth/callback` must be registered with real IdP. Tokens use session storage, are not logged, and caller never sends arbitrary buyerId.
- Public API should be behind same-origin HTTPS `/api` proxy; `VITE_API_BASE_URL` must not point to untrusted origins. Real IdP/PKCE browser UAT is **not** complete.

## Flutter source module
- `mobile/` Flutter SDK module with real published catalog, QR guest lead, AppAuth OIDC login, BuyerDemand/bid/accept/order flows and http client contract tests. Tokens in memory only, **no hard-coded account identity, passwords or secrets**.
- Separate workflow `Flutter Mobile`: `flutter pub get`, `flutter analyze`, `flutter test`. Native platform runner scaffolding is not checked in; see `mobile/README.md` for `flutter create --platforms android,ios .` and deep-link registration.
- No APK/IPA or installed-device acceptance evidence yet. Do not claim mobile production release.

## Required S2/production gates
1. Independent review issue #3 of exact final SHA, JWT issuer/JWKS/audience and buyer/seller provisioning/revocation, PII threat model and existing-schema Flyway migration rehearsal.
2. Live deployed HTTPS browser UAT with real IdP, no permissive CORS, correct reverse proxy forwarded IP and secrets management. QR guest lead consent/retention/legal deletion and anti-spam review.
3. Android/iOS runner generation, app link/URL scheme/AppAuth callback test, real-device error recovery and token lifecycle review. Complete seller bidding/confirmation UI or a clearly operated approved seller API for pilot.
4. Multi-process outbox sink, monitoring/backups and 5000 distinct authenticated order load tests with p95/p99 and PostgreSQL correctness (issue #9).
5. Business gating: source-of-truth public listing seller identity, versioned public repricing, pricing/tax/rounding policy, SKU categories and full fulfillment/receipt/dispute path.

**Verdict:** code-level R2.3C implementation/prototype, **NOT a signed-off complete marketplace MVP or production-ready checkout**.
