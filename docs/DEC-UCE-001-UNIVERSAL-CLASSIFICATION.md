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
