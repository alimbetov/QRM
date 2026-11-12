# DEC-UCE-001 — Universal Classification Engine for Buyer/Seller Matching

**Status:** PROPOSED taxonomy architecture, 2026-11-12. Design/S0 only. Not implemented as JPA, migrations or a trained Tribuo model. Standards mappings below are **references**, not claims of GS1/UNSPSC certification or ownership.

## Objective and positioning

Build a single multilingual, versioned, typed classification for PHYSICAL, SERVICE, DIGITAL, shared by buyer demand, seller listing/SKU, public Web facets and ML feature extraction. The classification identifies *what is being requested/offered*; deterministic matching establishes eligibility; Tribuo ranks **eligible** candidates. A classification prediction cannot by itself approve a seller or an order.

References: GS1 GPC (Segment/Family/Class/Brick/Attribute): https://ref.gs1.org/standards/gpc/ ; UNSPSC goods/services: https://www.unspsc.org/ ; Schema.org Product/Offer: https://schema.org/Product and https://schema.org/Offer . Maintain optional external code mapping with edition/source; use own stable UUID/code as identity.

## Three orthogonal dimensions

1. **What:** `ClassificationNode` hierarchical category, terminal `ClassificationClass` semantics, typed characteristics, `CanonicalProduct` or `CanonicalServiceSpec`; independently of merchant prices.
2. **Which variant:** product/service facts defined at canonical vs SKU/offer scope. E.g. phone brand/model canonical; 256GB/color SKU; service work type canonical, service coverage/time and inclusions seller offer.
3. **Commercial terms:** `Listing`, `ProductSku`, `SellerBidLine`, `BuyerDemandLine` per seller/buyer — unit price, quantity, KZT, geography, condition, shipment/appointment and consent, all snapshotted. Never put merchant prices in canonical taxonomy.

## Model candidates / authoritative ownership

| Type | Ownership | Role |
|---|---|---|
| `ClassificationNode` | catalog | stable code, parentId, PHYSICAL/SERVICE/DIGITAL classification domain, lifecycle, version |
| `ClassificationTranslation` | catalog | nodeId + locale (kk/ru/en/zh), title/synonyms; unique key |
| `AttributeDefinition` | catalog | stable code, datatype ENUM/NUMBER/BOOLEAN/TEXT/DATE/REFERENCE, unit/dimension, multivalue, min/max, revision |
| `ClassAttributeRule` | catalog | classId + attrId, required/filterable/matchingRole (HARD/SOFT/INFO), allowed values/range and source owner |
| `AttributeEnumOption` | catalog | stable enum value code and translations, not free-text |
| `ClassifiableAttributeValue` | catalog/listing | typed value and subject (canonical product, variant, service spec or listing); avoid generic polymorphic FK without integrity |
| `ExternalClassificationMap` | catalog | GS1/UNSPSC/schema.org external code, scheme edition, own classId, mapping confidence/manual approval |
| `CanonicalProduct` / `CanonicalServiceSpec` | catalog | approved canonical identity for cross-seller comparability; distinct merchant listings |
| `ClassificationProposal` | catalog/moderation | seller-submitted unknown category/value with review and audit |
| `FeatureSchemaVersion` | recommendation | versioned ML mapping definitions and normalization; may be a config artifact rather than table |

**Do not automatically add 10 physical tables.** Reuse existing `Category`, `Product`, `Listing`, `ProductSku`, `ListingTranslation`, `MeasurementUnit` and pending category attribute models where appropriate. S2 should choose normalized relational schema, FK strategy and indexes; no duplicated independent `Category` hierarchy. A class node may itself be Category with typed rules; introduce extra table only for necessary semantic distinction.

## Classification example / normalization

- Buyer: `classification=electronics.smartphones`, `brand=SAMSUNG`, `model=GALAXY_A56`, `storage_gb=256`, qty=1 PCS, KZT budget=160000, requested city=Almaty.
- Seller listing SKU: own `skuId` and `sellerId`, normalized same canonical product family, brand/model/storage, city and authorized supply area; quoted amount 155000 + actual confirmed delivery fee.
- Equivalent hard features: product category, model and storage when exact demanded, unit semantics and quantity compatibility. Condition/brand may be hard if buyer selected exact, otherwise SOFT preference.
- A different model is *not* automatically equivalent merely because it is a smartphone; substitutions require explicit opt-in and buyer consent.
- Service: `classification=home.electrical.socket_installation`, `work_units=5`, `materials_included=true`, `onsite=true`, location coverage=Almaty. Seller service bid has scope/time/materials assumptions. Service canonical identity is **work specification** rather than EAN/GTIN.

## Data contract and semantics

- Stable machine codes (`storage_gb`, `condition`, `service_scope`); multilingual display strings belong to translations only.
- Numeric values canonicalize units (e.g. GB, kg, km, hour), use BigDecimal/typed unit for money/quantity, store source input and conversion/provenance where warranted; reject dimensionally invalid conversions.
- Missing / unknown / not applicable are different states. Absence must not be silently interpreted as zero or false.
- Schema versions apply to **both** BuyerDemand and SellerBid/Listing snapshots; publish schema migrations and compatibility adapters, never reinterpret historical values in place.
- Seller-entered untrusted strings can suggest classification but must not silently create public matching keys; moderated canonicalization/validation needed.
- Hard vs soft attributes defined per class and request; buyer explicitly chooses acceptable substitutions, price ceiling, radius/time flexibility.
- Product certification/legal attributes (e.g. safety class or regulated product restriction) require verified provenance and policy, not ML guess.
- Category/attribute synonyms and translations RU/KK/EN/ZH should map to identical canonical IDs, with supported local unit aliases.

## Matching pipeline

```text
BuyerDemand + DemandLine (typed, versioned)
   -> Normalize & validate against Classification schema
   -> Candidate recall by canonical class / compatible products and seller availability
   -> Deterministic hard eligibility: published, seller owner, units, variant must-haves, coverage, qty, expiry
   -> True all-in quote: item price + confirmed fees, KZT, provenance and TTL
   -> Feature encoding (schema-versioned)
   -> Tribuo inference (when trained), else deterministic rule-based rank
   -> Explainable shortlist + explicit buyer selection and optional bargaining
```

A seller may bid on an entire multi-line demand (MVP), but per-line `MatchEvidence` must show correspondence between **buyer canonical requirement** and **seller's actual SKU**. A match score cannot bypass this verification.

## Feature engineering for Tribuo

Tribuo features are **named numeric features**; sparse categorical encoding with stable names and exact dictionary. Example:
- `match.brand_exact=1`, `match.model_exact=1`, `match.storage_exact=1`, `price.budget_deviation_pct=-3.1`,
  `delivery.days=2`, `seller.fulfillment_rate_90d=0.97`, `listing.condition.NEW=1`,
  `coverage.city_match=1`, `buyer.category_affinity=0.72`.
- Missingness: include explicit `delivery.fee_known=0` and suppress all-in cheaper claims when unknown; not zero delivery price.
- Distinguish model training labels: seller reply within specified horizon vs buyer order creation after exposure within specified horizon. Avoid labeling unseen or filtered offers as rejected.
- Store feature schema version, data as-of timestamp, source facts and label eligibility. Avoid target leakage (e.g. using completed order/acceptance as predictor for whether order will be created).
- Restrict PII: no raw phone, GPS, chat free text, password, token or other secret in feature vector. Consent and retention gates separate from listing matching.

## Suggested REST contracts (not yet implemented)

- `GET /api/v1/catalog/classifications?parentId=&type=&locale=`
- `GET /api/v1/catalog/classes/{id}/attribute-schema`
- `GET /api/v1/catalog/listings?classId=&attr.brand=&attr.storage_gb=&priceTo=&city=&page=&size=` (whitelisted typed filters)
- `POST /api/v1/buyer-demands` typed demand lines include `classificationId`, `schemaVersion`, hard/soft requirements and acceptable substitutions.
- `POST /api/v1/buyer-demands/{id}/bids` server verifies SKU ownership and compatible fact snapshots.

Final DTO shape, routes, validation errors and authorization to be reviewed before implementation.

## S0 → S4 testing and release gates

1. UCE-01 hierarchical category cannot contain cycles; stable codes unique, translations unique per locale.
2. UCE-02 equivalent Kazakh/Russian/English labels normalize to one attr code/value.
3. UCE-03 typed `storage_gb=256` matches 256GB, does NOT match 128GB under hard rule.
4. UCE-04 impossible units/types invalid; unknown distinct from zero/false.
5. UCE-05 buyer exact model rejects mismatching substitute without explicit opt-in.
6. UCE-06 service scope/inclusions differ → no silent exact match.
7. UCE-07 cross-seller SKU ownership validated; no seller can spoof other's listing/SKU.
8. UCE-08 listing DRAFT/UNLISTED/ARCHIVED excluded from public candidate search per visibility, seller bid-specific exposure explicitly authorized.
9. UCE-09 classification/attribute schema upgrades preserve existing demands/offers snapshots and model compatibility.
10. UCE-10 precision of all-in comparisons includes verified delivery, never treats unknown fee as zero.
11. UCE-11 model input fields stable on training/inference; no identity leakage or future feature leakage.
12. UCE-12 catalog facets computed with PostgreSQL typed attributes, indexed and paginated, not interpolated SQL.

## Incremental roadmap

- UCE-R0: finalize first two pilots: smartphones and at-home electrical services, define ~8-15 attributes each, canonical codes and translations; test domain match hard constraints.
- UCE-R1: JPA schema and PostgreSQL migrations + SKUs/attribute value and per-class rules, constraints and Testcontainers.
- UCE-R2: seller catalog ingestion and moderation, Web typed facets, buyer typed demand builder.
- UCE-R3: rule-based candidate matching with proper sku ownership/stock and costs; explainable seller competition.
- UCE-R4: collect consent-eligible outcomes/exposure, build Tribuo feature schema/dataset and offline evaluation.
- UCE-R5: deploy model ranking only if offline and A/B quality gates passed. Keep deterministic fallback.

**Unresolved S2 choices:** canonical product identity and exact-brand substitute policy, attribute version merge/migration, unit conversions, service delivery scope, whether variable/bespoke service work fits one canonical spec, external taxonomy licensing/translation and source versions, and type-safe PostgreSQL attribute storage strategy.

## DEC-UCE-002 — Strict positional coded HCC + versioned ML feature layout (2026-11-12)

**Status: APPROVED design direction, pending S2 physical schema and numerical-width review.** UniversalDictionary + stable generated numeric identifiers remains the source of truth. The positional classification code is a **separate stable classification path**, not the database primary key, and **not an ordinal ML feature**.

### Two codes, different jobs

1. `DictionaryNode.id BIGINT GENERATED ...`: immutable DB surrogate key for categories, brands, colors, materials, size systems, enum values, translations, etc. Sequential generator values have **no semantic proximity**.
2. `ClassificationPath.code CHAR(20)`: optional published fixed-width hierarchical code for taxonomy navigation / external interchange, built from fixed-width zero-padded *segment allocations*. Example `01|02|003|00123|00000001` = `01020030012300000001`; slots (2,2,3,5,8) denote kind, group, class, canonical subject, optional variant. **This is a proposed layout, not a commitment to encode every object in exactly 20 digits**. Physical width, overflow and cross-type semantics must be validated before migrations. Store as CHAR/VARCHAR, never as an integer (leading zeros, overflow). Root/id identity does not depend on positional code; moving a class requires new mapping/alias/version, not silently renumbering historical facts.
3. `AttributeDefinition` and `DictionaryNode` values form universal dimension dictionaries (COLOR, MATERIAL, BRAND, SIZE_SYSTEM, SIZE_VALUE, CONDITION, etc.). Links `ClassAttributeRule` choose which are applicable and required in each class; named numerical values store units, not every scalar as a dictionary leaf. No Cartesian explosion of color × size × material.
4. `FeatureSchemaVersion` declares an **immutable logical slot layout** for each task/model family (seller-response, deal-conversion, buyer segmentation), with feature name, source attribute, data type, normalizer, missing-policy, categorical-vocabulary version, dimension, optional category hierarchy ancestors and ordering.

### Encoding contract (strict order is for interoperability, named features are source of truth)

For `SMARTPHONE_MATCH_V1`, a logical pre-encoding vector might be:
`[type, group, class, brand, model, storage_gb, color, condition, budget_deviation_pct, delivery_days]`.
CATEGORICAL slots must **not** be fed to Tribuo as ordinal integers (e.g. brandId 5002 must not be treated as greater than 5001). Encode them into named binary indicators such as `class.01`, `class.01.02`, `class.01.02.003`, `brand.5002`, `color.4001`; continuous values remain numeric and must be normalized consistently. The encoder may materialize a reproducible fixed-order `double[]` for interchange **only after** expanding categorical vocabularies, using a frozen ordered feature-name dictionary; Tribuo's normal public feature representation is **named numeric features** and internal IDs need not align with raw HCC digits.

Persist `schemaVersion`, `vocabularyVersion`, `classificationRevision`, `encoderVersion`, `asOf`, and `SHA-256` of canonical schema serialization with every training run/model artifact. Inference refuses incompatible input schema and falls back to deterministic eligible-deal ranking. Never infer missing values as zero/false, distinguish missing/unknown/N/A; do not train on labels or features postdating decision instant.

### Positional code examples and exclusions

- A phone's color/size/material **must not** be permanently packed into the classification path; those attributes are optional, vary by class and may evolve.
- `KATO` remains its own official code scheme (region/territory) linked in `ExternalClassificationMap` or `GeoArea`, not concatenated into a product classification ID.
- A published code should not be derived from raw names, MD5, or an opaque hash. Hashing serves version integrity (SHA-256) or optional feature hashing after measurement, never replacing semantics.
- No false numeric similarity: numerical difference between `0102003` and `0102004` cannot prove category proximity; hierarchy must be derived from explicit parent relations and slot ancestors.

### Additional acceptance tests

- UCE-13: zero-padding/segment boundaries round-trip and preserve exact string.
- UCE-14: code uniqueness and parent-child consistency; aliases/version transitions preserve historic references.
- UCE-15: `brandId=5001` and `brandId=5002` encode as distinct indicator features, **not** ordinal magnitudes.
- UCE-16: training and inference use same frozen ordered feature dictionary, normalization and missing policies; schema mismatch is rejected.
- UCE-17: size ID is qualified by SIZE_SYSTEM; numeric 42 for shoes is not equal to clothing 42 by accident.
- UCE-18: color/material values reusable in many classes but restricted by each ClassAttributeRule; invalid combinations rejected.
- UCE-19: classification recodes, migrated aliases and old model artifacts remain reproducible with exact schema revisions.
- UCE-20: encoding cannot include raw phone, addresses, GPS, credentials or buyer personal identifiers; buyer segmentation uses only purpose-authorized signals.

**Implementation gate:** first prove stable types/segment capacity through two drastically different pilots (smartphones and electrical services), then add generator/schema migrations, normalization/encoding unit tests and Testcontainers verification. Don't ship an unversioned positional format.
