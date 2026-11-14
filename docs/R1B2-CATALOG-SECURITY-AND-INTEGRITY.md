# R1B.2 — Catalog Security, Data Integrity and Publication Freeze

**Date:** 2026-11-12. **Status:** implemented and CI qualification pending on current HEAD; no HTTP endpoints, no approved production migration deployment, no claim of end-to-end authenticated merchant access. Companion: [R1B](R1B-CATALOG-QUALIFICATION.md).

## User decision / objective

Close the highest-risk catalog mutations before buyer demand/bid order workflows. Preserve existing `CustomerOrder`, `OrderLine`, `BuyerDemand`, `SellerBid` and their database contracts. In particular, historical published SKU facts must remain consistent and matching must not accept unreviewed enum values.

## Implemented in Java

- `CatalogDraftMutationService`: transactions for owned **DRAFT** listings only; create SKU and idempotently upsert one attribute per SKU; no user-controlled seller ownership verification inside HTTP yet. Reads listing under `PESSIMISTIC_WRITE`, identical lock to publication, so worker-based draft changes and publishing serialize at listing level.
- `AttributeEnumOption` and repository: approved canonical ENUM code registry, uniqueness by `attribute_id + code`; `CatalogEnumPolicy` refuses unknown, inactive or malformed values. Draft mutation, publication and seller SKU facts resolution each enforce the check.
- `CatalogPublishingService`: lock listing, check supplied trusted seller actor ID against owner, require nonempty SKU set, all required category attributes per SKU, and approved enum values before transitioning DRAFT → PUBLIC.
- `SellerSku.create`, `SkuAttributeValue.of` and package-restricted `SkuAttributeValue.replaceCanonicalValue`: refuse edits after publication through normal domain factory/service paths.
- `Category.create`: when a parent is supplied, child must be an immediate next-level HCC descendant (2/2/3/5/8), not a skipped level. Existing standalone imported roots of deeper depth remain permitted for compatibility; authoritative full parent graph/forest governance remains unresolved.
- `AttributeDefinition`: bounded decimal precision/scale and canonical uppercase enum codes; data type/physical unit mapping to explicit SQL schema remains to be formalized.

## PostgreSQL evidence

- `db/manual/V001__catalog_foundation.sql`: existing catalog tables.
- `db/manual/V002__catalog_enum_governance.sql`: unique approved enum vocabulary with a database check on its code.
- `db/manual/V003__catalog_published_sku_guard.sql`: PL/pgSQL trigger rejects INSERT/UPDATE/DELETE to SKU or SKU attribute after listing is no longer DRAFT. The trigger acquires a row lock on `catalog_listing` to avoid a publish/mutate race. It also forbids moving an existing SKU/attribute fact to another listing or attribute by raw SQL.
- `CatalogPostgresMigrationIT` loads V001+V002 in PostgreSQL 17 Testcontainers, verifies valid/invalid HCC and ENUM uniqueness/format.
- `CatalogPostgresPublishedGuardIT` loads V001+V002+V003 in PostgreSQL 17 Testcontainers and attempts to insert, update and delete published SKU facts by raw SQL.
- JPA smoke covers wrong owner rejection, draft-only edits, idempotent upsert, required values, unapproved enums and revocation fail-closed at read.

**Deployment warning:** the three scripts are manual review candidates under `db/manual`. Neither Liquibase nor Flyway is enabled, and the repo still has legacy Hibernate schema-auto paths for dev/test. Production requires a baseline migration for **the whole database** (not only catalog), schema diff against existing deployments, rollback policy and immutable migration history. Do not run these files against an unassessed existing production database.

## Accepted limitations / next gates

1. **Authenticated seller principal is not connected**. UUID argument to server service is only trusted if resolved by future authentication/merchant membership adapter. No new HTTP write route until identity access is verified via integration tests. Listing owners cannot be inferred from user-supplied request JSON.
2. **Publication freeze applies to SKU and SKU attribute rows at DB level**. Global attribute rules/options and canonical product hierarchy still lack revisioned "as-of" governance; their edits may invalidate existing published facts. Plan versioned dictionaries and immutable classification snapshots before external ML features depend on them.
3. **No authorized revoke/edit republish workflow** yet. Support revisions/clone-to-draft with immutable published snapshot, not in-place mutation. `ListingStatus.PUBLIC` is not proof of live stock, seller KYC or delivery ability.
4. **Direct catalog_listing.status SQL update is not itself an audited publish event** and bypasses required-attribute service checks if unsafe DB credentials are granted. Implement DB privileges/service-only update path and, where required, validation trigger and audit outbox.
5. **Zero-data enum vocabulary** fails closed. Governed seed/import of actual `AttributeEnumOption` codes and locale translations is required.
6. Need concurrency DB IT with separate transactions verifying draft mutation vs publication lock ordering; current database trigger test verifies post-publication rejection but not full simultaneous race exploration.
7. Need independent S2 review of threat model, SQL function, indexing, migration integration and business ownership before accepting this slice into main.

## S2 acceptance matrix

| ID | Case | Evidence |
|---|---|---|
| CAT-S01 | Owner can create SKU, foreign actor cannot | `CatalogR1B2DraftIntegrityTest` |
| CAT-S02 | Upsert is idempotent, published content immutable in service | `CatalogR1B2DraftIntegrityTest` |
| CAT-S03 | Unknown enum prevented before save and before publish | `CatalogR1B2DraftIntegrityTest` |
| CAT-S04 | Revoked global enum prevents stale matching | `CatalogR1B2PublishedImmutabilityTest` |
| CAT-S05 | Adjacent HCC parent required | `CatalogR1B2HccParentTest` |
| CAT-S06 | PostgreSQL V001 + V002 constraints execute | `CatalogPostgresMigrationIT` |
| CAT-S07 | PostgreSQL prevents direct published SKU mutation | `CatalogPostgresPublishedGuardIT` |
| CAT-S08 | Actual security principal, no forged merchant actor | **Open — pre-HTTP P0** |
| CAT-S09 | Immutable versioned category/attribute facts across changes | **Open — pre-ML P0** |
| CAT-S10 | Full controlled migration registry | **Open — pre-deploy P0** |

## Next decision

Proceed to **R2 scenario design/RED tests** once CAT-S08 (auth principal boundary) and CAT-S10 (controlled migrations) are solved or strictly scoped behind non-exposed internal workflows. R2 starts with one seller/one order/reservation as required by the QR-first business flow, then adds buyer-led competition. Keep Tribuo learning and batch scheduling out of the purchase transaction.
