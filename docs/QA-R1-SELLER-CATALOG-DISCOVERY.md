# QA-R1A — Seller catalog discovery without administrator-provided Product ID

**Repository:** alimbetov/QRM; **branch:** `docs/adr-002-jpa-architecture-review`; **release:** HOLD / Draft PR #2. No branch creation or merge.

## Verified code gap and implemented change

Previously, `web/src/SellerWorkspace.tsx` required a seller to **type the canonical product UUID** to create a draft. This was an integration/operator-only step, not usable as a self-service catalog flow. The PUBLIC catalog does not solve this: it intentionally lists **only PUBLIC listings**, so an unused canonical product would be invisible.

QA-R1A introduces:
- `GET /api/v1/sellers/{sellerId}/catalog-products?query=<name>` under the **existing default-OFF seller workspace flag**. Requires the `catalog:write` OAuth scope and *current server-verified active catalog-editor membership* for that exact seller.
- `SellerCanonicalProductSearchService`: bound SQL over `catalog_product JOIN catalog_category`, searching unpublished as well as published products; **only active PHYSICAL categories**, deterministic ordering, max 20 results, 2..80-character validated name query, no unescaped wildcard syntax, no stock/seller-private data, no create/update side effects. Product IDs come from the server and are not accepted as an authorization proof.
- `web/src/seller-product-api.ts`: fail-closed typed API client, response shape/UUID/HCC validation, abortable requests, no fake fallback products.
- React: input **Поиск канонического товара**, server-derived select **Канонический товар** showing actual name/category/HCC, delayed lookup, empty/loading/error states; create disabled until a real option is chosen. No UUID textbox.
- The external `web/e2e/staging-seller.e2e.ts` now selects the operator's **real** fixture by its name and checks exact expected product ID via the returned option rather than entering a UUID into the UI.

## Qualification/evidence

- Real PostgreSQL 17 + JWT-mocked HTTP integration test: find an unpublished canonical product, prove no seller listing preexists, select its returned ID, POST a real Draft and verify the same product. Separate test rejects unauthenticated / foreign-seller / wrong-scope / malformed / wildcard search.
- Vitest: accepted Unicode/canonical names, route+Bearer scope, 403, malformed response, missing product returns **empty** (never fictitious). Web TypeScript build and Playwright test declaration checks.
- Full repo CI on **exact final SHA** must pass; this document by itself is not proof of a browser run.
- **Still unverified:** real HTTPS Keycloak-based seller UAT; actual React screenshot, physical Android/iOS build, automatic stock-location and stock-intake setup, two-browser buyer/seller order acceptance, independent S2 APPROVE, multi-worker tests and 5000-VU throughput.

## Operator staging seller publication

1. Prepare a real Keycloak SELLER_ADMIN or CATALOG_EDITOR with scoped `catalog:write`, active `seller_membership`, existing **active PHYSICAL canonical product** and applicable HCC rules.
2. Set `VITE_SELLER_WORKSPACE_ENABLED=true` and `ZAKAZ_SELLER_WORKSPACE_ENABLED=true` **only** on an authorized isolated staging environment with the trusted PostgreSQL.
3. Set `STAGING_SELLER_BROWSER_UAT=true`, `STAGING_CANONICAL_PRODUCT_NAME=<actual canonical name>`, `STAGING_CANONICAL_PRODUCT_ID=<actual ID for assertion>`, `STAGING_SELLER_ID=<verified seller UUID>`, and the private seller credentials (never commit).
4. Execute Playwright's seller test with a real HTTPS base URL. Search results must be returned from the authenticated internal endpoint, not seeded into the React client or mocked.
5. Confirm a DRAFT+SKU appears, publication returns PUBLIC, and its QR opens the genuine canonical product with no invented catalog data.

## Next blockers

**QA-R1B:** Seller location and stock-onboarding UX with audited inventory receipts; current `InventoryStockService.receive` requires previously trusted-provisioned balance. Do not create stock implicitly from a public product search or listing publish.

**QA-R1C:** Full two-browser Keycloak BuyerDemand → naturally generated consented invitation → SellerBid → PLACED → confirmed reservation and read-only SQL witness. **QA-R2:** competing buyers, replay/expired bid, cancellation vs reserve and multi-pod worker crash/recovery. Then physical Flutter device UAT and independent S2.
