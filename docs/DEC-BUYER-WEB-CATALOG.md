# DEC-BUYER-WEB-001 — buyer Web workspace / category marketplace

**Product direction approved 2026-11-12.** This decision **supersedes** previous guest-only React / disabled Web-heart rule in REQUIREMENTS and UX-FLOWS; the documents need to be read with this override. First React implementation is a browser-local functional demo without backend integration, not a launched buyer marketplace.

## Buyer information architecture

- React Web: catalog, saved offers, category discovery, public QR listing, seller comparison, detail and public callback lead.
- Guest: can view public search/results, seller pages, category and offer details; temporary local favorites optional as demo-only with explicit non-synced wording, not durable account state.
- Authenticated BUYER: dedicated buyer workspace Favorites and price-watch, synchronized by server-side `Favorite(userId,listingId)` across Web and Flutter; only identity-scoped account can read/write; guest local list imported only after explicit user consent to merge and conflicts resolved by unique constraint. No permission to create authenticated order from public callback form.
- Browsing a category includes other sellers, including offers of the same Product; default discovery requires PUBLIC listing (UNLISTED viewable by link but excluded from categories/search); never include ARCHIVED or DRAFT.

## Catalog / filtering

- `GET /api/v1/catalog/listings?categoryId=&sellerId=&city=&priceFrom=&priceTo=&sort=&page=&size=` — conceptual read API; exact naming reviewed in S2. Server-side pagination with deterministic `sortField,listingId` tie-break; cap size 50, avoid offset pagination for deep pages (keyset later). Include `totalElements` only when affordable; no accidental leaks of private stock/location or unlisted content.
- Category facet definition `CategoryAttributeDefinition(categoryId, code, type, allowedValues, unit?, filterable, sortOrder, version)`: **new candidate** table if schema-managed dynamically; simple stable attribute catalog could initially be value objects/code instead. Seller/listing actual indexed values `ListingAttributeValue(listingId,definitionId,valueTyped)` or SKU-level `SkuAttributeValue` for variant attributes. Filter against **typed validated attributes** (NUMERIC ranges / ENUM / BOOLEAN / TEXT indexed only when genuinely filterable). No arbitrary JSONPath SQL constructed from client strings, no dynamic column injection. Prefer one source of truth per attribute.
- Attributes examples: phones brand/memory; services specialization/on-site work; furniture type/material; filter only eligible category/seller/product type. Locale labels separate from stable codes. Unit/currency normalized; multi-SKU price 'from' policy explicitly defined when several variants have different prices.
- Seller comparisons are read-only; filter by seller is optional, not enforced by scan origin QR. When arriving via QR, seller context is shown but the user can discover offers from others without modifying original QR attribution.

## State and security

- UI sort/filter/pagination are URL shareable (phase 2) and browser back history stable; initial local demo only, page resets on filters and never mixes pages across categories.
- Favorite is one listing per authenticated buyer; category cross-seller comparisons don't duplicate by seller. Price watch settings optional later and must be server-sided.
- Index needs: `listing(status,category_id, seller_id, id)`, `listing(city)` only if listing-origin locality is canonical; price model may be separate indexed read model; `favorite(user_id, listing_id)` unique. PostgreSQL planner tests before final indexes.
- Read API validates authorized seller-facing vs public fields. Rate limits for public read/search, XSS-safe rendering, pagination bounds.

## RED acceptance requirements

| ID | Given / when | Oracle |
|---|---|---|
| BW-01 | Buyer opens category phones | Only PUBLIC relevant offers; different sellers present, deterministic order |
| BW-02 | User checks brand Samsung and memory 128 | AND filter applies to valid typed attributes, not unrelated categories |
| BW-03 | User changes category while paging | resets page, facets and seller selection |
| BW-04 | User navigates 2+ pages | no duplicates, stable deterministic total order |
| BW-05 | User selects city / price range / seller | combined filters applied, no private warehouse data leaked |
| BW-06 | User favorites offer | after login shared Favorite persists across Web/Flutter, unique pair |
| BW-07 | Guest opens Favorites | must not claim guest bookmarks are remotely synced |
| BW-08 | User reads another buyer's favorites | 403/404 with no data leakage |
| BW-09 | DRAFT/ARCHIVED/UNLISTED in search | exclude from public catalog; direct-link UNLISTED rule separate |
| BW-10 | Special attribute numeric range or enum | stable typed validated index; invalid keys/values rejected |
| BW-11 | Query has untrusted sort/filter | allowlist enforced, no SQL injection |
| BW-12 | Multiple SKUs in one Listing | price-from and variant labels follow explicit price policy, never misrepresent untracked stock |
| BW-13 | Search or favorites empty | usable zero-result message and reset |
| BW-14 | A public Web visitor submits callback | only PurchaseLead created; never CustomerOrder |
| BW-15 | Buyer views category from QR | original attribution preserved in source event, discovery can show other sellers |

## MVP delivery phases

**BW-A (implemented frontend prototype)** React/Vite/TS demo with local category page, numbered pagination, shared category-specific characteristics, seller/city/price search and local demonstration favorites, item modal. TypeScript/logic tests + build CI.

**BW-B (next)** Backend public catalog read model, Categories/AttributeDefinition/typed values, secure visibility/filtering, DB pagination tests. Replace demo items with real API, query-string state.

**BW-C (next)** Auth Web buyer and server-side Favorites sync with Flutter, price watch and notification center.

**BW-D (later)** Real commerce if explicitly authorized for Web buyer with secure offer/order semantics; preserve Flutter P0 purchase journey.
