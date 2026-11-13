# ZAKAZ Buyer Web — functional catalog prototype

React 19 + Vite + TypeScript. The main category catalog still uses **demo data**; the QR route `/q/{opaqueToken}` now uses the real Backend HTTP API. Working category navigation, shared characteristic filters per category, search, sellers, city, price range, sorting, numbered pagination, product details and saved bookmarks.

## Run
```bash
cd web
npm install
npm run dev
npm test
npm run build
```

**Important limitations:** Demo item descriptions/prices are placeholders, not marketplace listings. Favorites persist in this browser via localStorage only for demo; approved production contract is server-side Favorite unique(userId,listingId) synced between authenticated Web and Flutter. No backend catalog/facet/favorite endpoints are implemented. No order creation, negotiation, phone-based authentication or stock reservation is exposed in this prototype. Public React callback/QR listing flow is implemented behind Backend `zakaz.qr.http.enabled` and not approved for production yet. Opening `/q/{token}` fetches live published catalog fields, including the optional exact KZT unit price as a decimal string, permits contact callback (not a purchase), and shows failures accurately. Missing historical SKU prices are explicitly labeled "Цена уточняется"; this is not an order quote.

## Next server slice

1. Read-only catalog API, server-side PageRequest or keyset with stable tie-breakers, allowlist of filters/sort, seller/category/visibility enforcement, indexes, safe search.
2. Category-specific structured characteristic schema with allowed value type and unit, validated against catalog category; facets server-computed, not raw unbounded JSON predicates.
3. Authenticated shared `Favorite` CRUD with owner ACL, unique(userId, listingId), quota, idempotent add/remove and refresh across devices.
4. Buyer order/negotiation actions stay out of this Web sprint until security and price/stock invariants qualify.


## Live QR landing (R2.3)

Set `zakaz.qr.http.enabled=true` on a reviewed Backend staging instance, configure `zakaz.leads.phone-key-base64` and a canonical HTTPS `zakaz.qr.public-base-url`, and publish a seller listing. Seller `GET /api/v1/sellers/{sellerId}/listings/{listingId}/qr.png` returns a real QR PNG to `https://<your-domain>/q/{token}`. The React route uses actual public GET/POST; `vite.config.ts` proxies `/api` to `http://localhost:8080` for local development, and production needs a controlled HTTPS reverse proxy or explicit `VITE_API_BASE_URL` with approved CORS. No login UI or authenticated BuyerDemand bidding UI is present yet; the backend routes exist behind `zakaz.commerce.http.enabled`. Neither demo items nor favorites are real seller account data. See [R2.3 implementation](../docs/R2-3-HTTP-API-QR-JOURNEY.md).
