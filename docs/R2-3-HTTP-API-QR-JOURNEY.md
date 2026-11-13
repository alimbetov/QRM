# R2.3 — QR-first HTTP vertical slice and authenticated commerce API

**Date:** 2026-11-13. **PR:** #2 Draft (single branch `docs/adr-002-jpa-architecture-review`). **Status:** internal API/React QR route and Testcontainers qualification, **NOT S2-approved for production**. See [R2.3 acceptance gates](R2-3-QR-FIRST-E2E-5K-GATES.md).

## What is implemented

### Public QR/guest lead path

1. Seller uses the existing authenticated `POST /api/v1/sellers/{sellerId}/listings/{listingId}/publish` with `catalog:write` and trusted `seller_membership`. Publication, stable `PublicListingLink` insertion and random 256-bit opaque 43-character URL-safe token are atomic. Unpublished drafts have no token; token is not derived from UUID, phone or merchant identity.
2. `GET /api/v1/sellers/{sellerId}/listings/{listingId}/qr.png` with `catalog:write` plus server membership/ownership returns a real 360px PNG QR. Target comes **only** from the configured HTTPS `zakaz.qr.public-base-url` plus `/q/{token}`, not request Host/X-Forwarded-Host. Test round-trips through ZXing's QR decoder.
3. React `/q/{token}` uses live `GET /api/v1/public/q/{token}`; renders the real product/category/SKU variants, not the static demonstration `web/src/catalog.ts`. Anonymous 404 for unknown, draft or non-PUBLIC; DTO contains no warehouse/stock balance, contact phone, buyer ID or private seller membership. **Pricing R2.3B:** seller-set nullable `catalog_sku.public_unit_price_kzt` (Flyway V18) is exposed as `variants[].priceKzt` decimal string only after successful publication; absent prices are null, never fabricated. Seller can set price only on DRAFT via membership-protected `PUT /api/v1/sellers/{sellerId}/listings/{listingId}/skus/{skuId}/price`, when QR HTTP is enabled. This public indicative unit price is NOT an accepted or all-in shipping quote, and is immutable on the SKU after publication. See backend README; revise with a versioned pricing model later.
4. Guest explicitly opts into contact; `POST /api/v1/public/q/{token}/leads` with JSON `{"phone":"+77011234567","consent":true}`, required `Idempotency-Key`. Response **202** means a callback request saved, **not CustomerOrder**, not a payment or SMS-verified account. E.164 phone required, no SMS login or registration created. Same idempotency key+payload returns 202 once, changed payload returns 409.
5. Contact phone encrypted at rest using AES-256-GCM with random per-lead nonce, key from `zakaz.leads.phone-key-base64`; no key means lead collection disabled (503). Never commit the key to Git or log phone content. HMAC-SHA256 digests provide stable request fingerprint and rate-limit source, not proof of caller identity. PostgreSQL atomic quota: max 5 new leads per published listing and source digest in 15min; 429 after quota. Source is `servletRequest.getRemoteAddr()` only, **never untrusted X-Forwarded-For**.
6. Verified seller with `SCOPE_leads:read` **and** `SELLER_ADMIN` membership can `GET /api/v1/sellers/{sellerId}/leads?page=0&size=20` (max 20 per page). A seller receives only their listing contacts. Contact is decrypted inside an audited transaction; every phone disclosure writes append-only `qr_lead_read_audit`; HTTP response has `Cache-Control: no-store`. No public lead-reading endpoint.
7. Flyway **V16** adds stable QR links, encrypted `qr_purchase_lead` and PostgreSQL quota; **V17** adds immutable, seller-scoped lead read-audit. There is **no** automatic migration or delivery to an external CRM.

### Authenticated commerce path

The following are real Spring MVC endpoints, not just internal Java services. **All disabled unless `zakaz.commerce.http.enabled=true`** and the server is configured with the real OIDC/JWKS decoder.

| Action | Route | Required JWT scope | Server-side identity |
|---|---|---|---|
| Create demand | `POST /api/v1/buyers/demands` | `deal:write` | active `buyer_account_link(iss,sub)` |
| Submit seller bid | `POST /api/v1/sellers/{sellerId}/demands/{demandId}/bids` | `deal:bid` | active `SELLER_ADMIN` membership for seller ID |
| Accept one bid | `POST /api/v1/buyers/demands/{demandId}/bids/{bidId}/accept` | `deal:write` | same owner as BuyerDemand, idempotency key |
| Read buyer order | `GET /api/v1/buyers/orders/{orderId}` | `order:read` | same verified owner as order |
| Confirm order | `POST /api/v1/sellers/{sellerId}/orders/{orderId}/confirm` | `order:confirm` | `SELLER_ADMIN` and order owner |
| Cancel by seller | `POST /api/v1/sellers/{sellerId}/orders/{orderId}/cancel` | `order:confirm` | `SELLER_ADMIN` and order owner |

Authenticated HTTP acceptance produces **one PLACED order with zero reservations**. Seller confirmation retains the existing single PostgreSQL transaction for `CONFIRMED`, InventoryBalance locks, all reservations, Movement and Outbox. Existing idempotent accept/confirm logic is reused; no second commerce implementation. Buyer order GET materializes DTO within a transaction (no detached lazy state). Domain errors map to generic 403/404/409/422, without exposing internal SQL or membership details.

## Feature flags (default OFF)

| Property | Default | Meaning |
|---|---|---|
| `zakaz.qr.http.enabled` | false | Activates public GET/lead POST, seller QR PNG and lead inbox HTTP endpoints |
| `zakaz.commerce.http.enabled` | false | Activates authenticated BuyerDemand/Bid/Order API endpoints |
| `zakaz.qr.public-base-url` | unset | Canonical HTTPS origin, e.g. **configure to your real staging domain**; required to download seller QR PNG |
| `zakaz.leads.phone-key-base64` | unset | Base64 of a securely generated 32-byte AES secret; required for lead POST and seller phone read |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | prod required | Real trusted OIDC issuer; scopes alone are insufficient without server-managed links/roles |

Example **private staging** configuration (no real secrets shown):
```sh
SPRING_PROFILES_ACTIVE=prod
ZAKAZ_QR_HTTP_ENABLED=true
ZAKAZ_COMMERCE_HTTP_ENABLED=true
ZAKAZ_QR_PUBLIC_BASE_URL=https://staging.example.invalid
ZAKAZ_LEADS_PHONE_KEY_BASE64=${SECRET_MANAGER_LEAD_KEY}
ZAKAZ_JWT_ISSUER=https://your-trusted-oidc-issuer.example.invalid
```
Enable only in an isolated test environment after actual values are securely configured. HTTPS and same-origin `/api` reverse proxy required for browser deployment. Vite dev server proxies `/api` to `localhost:8080`; use `VITE_API_BASE_URL` only for a separately configured trusted origin.

## Test evidence

- `PublicQrJourneyPostgresIT`: token issued only by publication, public DTO whitelist, contact consent, no order, idempotent 202/conflict 409, encrypted phone, Postgres rate-limit 429.
- `SellerQrSecurityIT`: unauthorized/unenrolled seller denied, valid seller gets decodable PNG with fixed canonical HTTPS URL.
- `SellerLeadInboxPostgresIT`: only authorized seller reads phone, cross-seller denied, each view generates persisted immutable audit and `no-store`.
- `AuthenticatedDealHttpPostgresIT`: actual MockMvc HTTP + PostgreSQL Testcontainers. JWT decoder mocked for known claims, server DB membership and buyer ownership *real*: create demand → seller bid → buyer accepts → PLACED without reserve → seller confirms → CONFIRMED+reserve+Outbox, idempotency replay and buyer read. **Not a real external OIDC signature/refresh UAT**.
- `web/src/qr-api.test.ts`: input route parsing/phone E.164 tests, TypeScript build. **Not browser Playwright or running public HTTPS UAT**.
- Exact HEAD and CI state should be recorded after the latest code changes, not inherited from prior SHA.

## Outstanding security/product/ops gates

1. **Independent S2 #3** unchanged. Public / buyer / seller endpoints are disabled by default; external OIDC/JWKS, seller vetting/roles, rate-limit ingress IP preservation and real HTTP staging tests are NOT approved.
2. Contact PII retention/deletion, consent text/legal notice, seller misuse audit monitoring, key rotation (only v1 key supported), data subject deletion process and anti-spam defenses beyond IP quota must be approved before production.
3. The main React catalog still uses demo data and local favorites; the **QR route alone** fetches live Backend. Buyer OAuth browser login, seller workspace, real listing discovery/price and mobile Flutter UAT are not yet done. No fake orders or stock claims in QR UI.
4. A guest lead is deliberately **not an order**. No account verification via a lead phone, no SMS registration flow, no auto-fabricated buyer record.
5. Performance: `POST .../confirm` now exists behind the feature gate, but no 5000 distinct-order staging fixture set or distributed load run. See [k6 harness](../perf/k6/README.md). No RPS/p95/p99 evidence.
6. Outbox still uses an **internal** idempotent PostgreSQL projection; production external adapter, DLQ operational control, lease heartbeat for slow sinks, multi-pod deployment and historical migration rehearsal remain open.
7. No confirmed buyer payments, returns or fulfillment, no order-number collision retry policy, no public price quote tied directly to scanning listing.

**Release decision:** code-level R2.3 HTTP slice is a gated prototype until S2 review, real browser/mobile E2E, and verified staging telemetry.
