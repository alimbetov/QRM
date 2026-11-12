# ZAKAZ Buyer Web — functional catalog prototype

React 19 + Vite + TypeScript, standalone **demo data** with working category navigation, shared characteristic filters per category, search, sellers, city, price range, sorting, numbered pagination, product details and saved bookmarks.

## Run
```bash
cd web
npm install
npm run dev
npm test
npm run build
```

**Important limitations:** Demo item descriptions/prices are placeholders, not marketplace listings. Favorites persist in this browser via localStorage only for demo; approved production contract is server-side Favorite unique(userId,listingId) synced between authenticated Web and Flutter. No backend catalog/facet/favorite endpoints are implemented. No order creation, negotiation, phone-based authentication or stock reservation is exposed in this prototype. Public React callback/QR listing flow remains in scope.

## Next server slice

1. Read-only catalog API, server-side PageRequest or keyset with stable tie-breakers, allowlist of filters/sort, seller/category/visibility enforcement, indexes, safe search.
2. Category-specific structured characteristic schema with allowed value type and unit, validated against catalog category; facets server-computed, not raw unbounded JSON predicates.
3. Authenticated shared `Favorite` CRUD with owner ACL, unique(userId, listingId), quota, idempotent add/remove and refresh across devices.
4. Buyer order/negotiation actions stay out of this Web sprint until security and price/stock invariants qualify.
