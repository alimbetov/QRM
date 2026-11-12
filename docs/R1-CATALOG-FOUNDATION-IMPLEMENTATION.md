# R1 Catalog & Classification Foundation — implementation slice A

**Status:** implemented minimal JPA model and proposed manual PostgreSQL V001 DDL in branch PR #2; not a complete catalog API or production-authorized schema. Existing order/deal classes unchanged.

## Implemented
- `Category`: one fixed-width (20 digit) HCC/category hierarchy, explicit product type, single parent, unique code, optimistic version. Numeric-looking HCC stays a string.
- `AttributeDefinition`: globally reusable typed descriptor, limited value canonicalization.
- `CategoryAttributeRule`: class applicability and required/filterable flags. Exact allowed enum value registry and unit conversions remain separate work.
- `CanonicalProduct` independent from seller-specific `SellerListing`; `SellerSku` belongs to listing; `SkuAttributeValue` belongs to SKU, canonicalized and rule checked at factory boundary.
- Repositories and H2 integration/unit smoke for seller isolation of SKUs and globally unique category code.
- SQL `backend/src/main/resources/db/manual/V001__catalog_foundation.sql` is deliberately **not** executed at production startup. `ddl-auto=validate` remains in production; no Liquibase runtime added.

## Known S2 blockers
1. Security: no identity/seller ownership verification from authenticated principal and no endpoints. `SellerListing.sellerId` is just a persisted claim until application ACL.
2. Need enforce HCC 2/2/3/5/8 positional depth, ancestor code formats, code immutability and prevent cycles against DB state. Current parent prefix check alone is insufficient.
3. CategoryAttributeRule must enforce required field completeness for every public SKU, enum vocabularies with translations, no unapproved free-form values, dimensions/range/unit normalization, category inheritance, rule revisions, plus database-side race/integrity review.
4. Existing Category code semantics and canonical Product identity need explicit product-group/variant scope review; model permits seller SKU with same product different options intentionally.
5. JPA remains normal ORM; do not create separate tables for color/size for every class. Translation, synonyms, official geographic and external classification imports are not included yet.
6. Order reserve/stock/price bid transaction must validate live SKU owner, published Listing, current inventory; order engine not modified.
7. DDL PostgreSQL integration validation, controlled migration management, rollback policy and schema diff required before production. Do not claim a production migration has run.

## Required next independent R1B tests
- Seller A cannot create/edit/delete Seller B's listing/SKU, even by guessing ID.
- Inactive/unpublished/incorrect category cannot be used for published offer.
- Unit-normalized numeric facts and typed dictionary code, unknown/zero/missing distinctions.
- SKU attribute required rules fully met before publishing; violation race tests.
- Lookup buyer requirement `classCode + attributes` against **server retrieved** SKU facts.
- PostgreSQL Testcontainers executes V001 against clean database and checks FK/unique constraints with schema `validate`.

R2 starts only after R1B secured server-owned ownership and catalog fact lookup.
