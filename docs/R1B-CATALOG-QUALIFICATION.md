# R1B — Catalog qualification, seller ownership and PostgreSQL DDL

**Date:** 2026-11-12. **Status:** code implemented in draft PR #2; do not promote without S2 review and current HEAD CI.

## Changed foundation
- HCC checked by **2/2/3/5/8 digits**, no zero-segment gaps, strict ancestor relation by prefix and deeper segment; persisted as fixed-width 20-character text, never BIGINT/ML ordinal. Category still requires DB-side full parent-cycle and parent-depth validation for persisted independent writes.
- `CatalogPublicationPolicy.verify` checks category-specific rule applicability, duplicates, required attributes per SKU, canonicalized values; no price, seller verification, territory or stock assumptions.
- `CatalogPublishingService.publishForSeller` loads the listing and SKU/attribute/rule facts from JPA, compares server-identified seller ID and refuses empty/incomplete listings; only drafts can be published. **Security caveat:** `actorSellerId` must be supplied by an authenticated server principal when a controller is later created — there is no identity integration or HTTP endpoint yet.
- `CatalogSkuFactsResolver.resolveForSeller` retrieves owned PUBLIC SKU, canonical product, HCC and attributes **from repositories**. It is not permission to bid, since seller KYC, live stock, unit quantity and fulfillment territory remain outside this slice.
- `V001__catalog_foundation.sql`: manual PostgreSQL DDL enhanced with no-gap HCC checks; migration is not automatically enabled and not a replacement for Liquibase/Flyway reviews.
- Unit and JPA tests cover hierarchy gaps, required attributes, seller ownership, draft/public, duplicate attributes. Separate `CatalogPostgresMigrationIT` executes DDL using Testcontainers/PostgreSQL 17 and expects an invalid HCC to fail.

## Remaining P0/S2 blockers
1. Authenticated user/merchant identity (real ACL integration), tenant/membership verification, public GET DTO filtering.
2. Durable approved attribute enum options and revisions; size system, numerical unit conversion/dimensions, missing/unknown/not-applicable; required attributes enforced at DB/application transactional boundary.
3. HCC effective parent chain and valid level for PHYSICAL/SERVICE/DIGITAL, no cycles, concurrency-aware no-code-reuse policy, official mappings and localization.
4. Robust PostgreSQL migration lifecycle/version history (Flyway/Liquibase), schema validation when existing order tables are present; rollback/backfill of live data.
5. SKU eligibility via verified seller status, availability/reservation, current delivery coverage, price quote; never translate a catalog fact into an automatic eligibility boolean.
6. Independent S2 review of security, data migration and query performance before production acceptance.
7. Test concurrency publication of one listing with revisions; ensure no update-after-publish and validate attribute mutations in separate transaction.

## Next vertical slice
R2 contract-first BuyerDemand → SellerBid → verified catalog SKU facts → deterministic hard constraints → one-seller order → seller-confirmed transactionally reserved quantity, with idempotency, ledger and outbox. Do not initiate Tribuo training until authoritative event/canonical data is available.

## Verification
RED commit `28519202` intentionally failed backend CI before the implementation existed. GREEN code committed after; verify exact current head actions for pass/fail rather than relying on earlier status.
