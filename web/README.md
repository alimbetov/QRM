# ZAKAZ Buyer Web — R2.3C HTTP clients

React 19 / Vite / TypeScript. **Live Backend data by default**, not the old static demo catalog.

## Run

```sh
cd web
npm install
npm test
npm run build
npm run dev
```

Routes:
- `/` — actual PostgreSQL published catalog with category/search/pagination and exact optional KZT SKU prices.
- `/q/{opaqueToken}` — live public listing and opt-in encrypted callback PurchaseLead (**not** an order).
- `/buy?productId=<canonical-uuid>&unit=PCS` — verified buyer OIDC login, BuyerDemand, live SellerBid quotes, explicit accept, customer order status.
- `/auth/callback` — OAuth2 Authorization Code + PKCE completion.

## Private staging setup

Backend flags `zakaz.catalog.http.enabled=true`, `zakaz.qr.http.enabled=true`, `zakaz.commerce.http.enabled=true` default OFF. They may be enabled only on an isolated security-reviewed test environment.

Register a *public* SPA OIDC client with redirect `https://<approved-web-host>/auth/callback` and scope `openid profile deal:write order:read`. Configure Vite build variables:

```
VITE_OIDC_AUTHORITY=https://your-real-issuer.example
VITE_OIDC_CLIENT_ID=your-registered-spa-public-client-id
```

Same-origin HTTPS `/api` reverse proxy recommended; `VITE_API_BASE_URL` requires explicitly approved origin/CORS. Authorization is unavailable when OIDC is not configured; no fake tokens/accounts or demo fallback are used. Buyer identity is looked up by issuer/subject in trusted `buyer_account_link` on Backend.

### Real functional limits

Search currently uses canonical product name and exact category, not full-text/facets/geospatial search. Catalog has no verified seller display name, photos, stock promise or public delivery price; do not invent these. Pricing is indicative SKU unit price; accepted SellerBid quote determines all-in order price. BuyerDemand waits for seller-authenticated bid submission; a seller web/mobile bidding interface does not exist yet. Reservations happen only at seller order confirmation. React auth and transaction screens have automated build/contract checks but not an external-IdP browser E2E signoff.

Previous static demo `web/src/App.tsx` and `catalog.ts` remain isolated legacy sources, **not** the default route. They must not be represented as live production data.

See `docs/R2-3C-REAL-CATALOG-BUYER-CLIENTS.md`, Draft PR #2 and S2 issue #3. Do not deploy publicly or merge to `main` before independent review.

### R2.3E verified buyer profile onboarding

After Keycloak PKCE login, the client calls authenticated `GET /api/v1/buyers/me`. When the backend returns 404, the user may complete a profile with nickname, E.164 contact phone and explicit storage consent via `POST /api/v1/buyers/enroll`. All ids and email-verification claims come from the **signed token and backend**; the UI never supplies a buyerId. This functionality requires staging `zakaz.buyer.enrollment.enabled=true` and an independent buyer phone encryption key, plus Keycloak email verification. The phone is not SMS-verified and may not be used for recovery. When the profile cannot be verified (403/503), checkout remains blocked.


### R2.3F — real signup and Keycloak logout

The buyer screen now offers a **Create account** flow via OIDC `prompt=create`, and normal Authorization Code+S256 login. Staging signup is intentionally disabled until real SMTP and verified mailbox acceptance pass. The **Logout** button invokes Keycloak's RP-initiated end session and redirects to the registered `/auth/signed-out`; the callback is validated before removing the in-browser session. An expired access token blocks further buyer API calls until a new login. This is not immediate revocation of an already minted bearer JWT at the Backend. Staging Playwright checks the logout/re-login lifecycle; execution against real Keycloak remains an operator UAT gate. See [R2.3F](../docs/R2-3F-KEYCLOAK-SMTP-SESSION-E2E.md).

### React Workspace R1 — Seller Studio (feature-gated)

The live Buyer Web now uses explicit React Router routes, a shared responsive navigation header, and an isolated seller workspace. The old `web/src/App.tsx` prototype is still **not active**. See [R1 technical specification](../docs/REACT-WORKSPACE-R1-IMPLEMENTATION.md).

**Seller endpoints (Backend `zakaz.seller.workspace.enabled=false` by default):** `GET /api/v1/sellers/{sellerId}/listings` and `GET /.../listings/{listingId}` show only the verified merchant's bounded list; `POST /api/v1/sellers/{sellerId}/listings` creates DRAFT + one tracked physical SKU from an existing CanonicalProduct (no new HCC, no stock reservation). Existing price/media/publish/QR endpoints are reused.

**Seller SPA OIDC configuration:** separate public client `zakaz-seller-web`, Authorization Code + PKCE S256, exact HTTPS `/seller/auth/callback` and `/seller/auth/signed-out`, scopes `catalog:write deal:bid order:confirm leads:read`; Backend verifies active `seller_membership` for every operation. No tokens in the URL or embedded Flutter WebView. Build with:

```sh
VITE_SELLER_WORKSPACE_ENABLED=true \
VITE_SELLER_OIDC_AUTHORITY=https://staging.example/identity/realms/zakaz-staging \
VITE_SELLER_OIDC_CLIENT_ID=zakaz-seller-web \
npm run build
```

Also configure Backend `ZAKAZ_SELLER_WORKSPACE_ENABLED=true` in a reviewed **private** staging environment. React and backend flags default **OFF**; use real verified seller membership and a known canonical physical Product UUID. The R1 form accepts Seller UUID and Canonical Product UUID only as temporary internal UAT inputs; the next UX slice must provide seller membership discovery and product picker. Publication may need taxonomy/SKU attributes and proper stock location prepared separately. The seller bid-entry form and full fulfillment remain missing. A separate feature-gated SellerDealsPanel now reads real seller orders, audited guest leads, consented invitations and confirms/cancels orders; it requires additional Backend `zakaz.seller.studio.enabled=true` and other individual API flags. Seller organization auto-discovery currently returns only SELLER_ADMIN roles, while CATALOG_EDITOR can still use a known Seller ID. This is code-tested, not real device/browser end-to-end qualified. Enable real Playwright manually using `STAGING_SELLER_BROWSER_UAT=true` plus private seller credentials and product ID; CI uses only `--list`. No Android/iOS production claim.
