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
