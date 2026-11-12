# DEC-INTELLIGENCE-001 — Task and feature-space segmentation for ZAKAZ

**Date:** 2026-11-12. **Status:** approved conceptual direction / S0; ML model implementations and trained datasets NOT delivered.  
**Sources:** [master requirements](REQUIREMENTS.md), [HCC UCE-001/002/003](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md), [HCC–Tribuo review](REVIEW-TRIBUO-HCC-001.md), [Java ML ADR-003](ADR-003-JAVA-ML-RECOMMENDATIONS.md).

## Decision

Use a **shared, governed canonical dictionary** (numeric generated surrogate IDs, stable external/business codes, hierarchy and translations) plus **separate model/task-specific feature spaces**. Never require one enormous fixed dense vector or a single model to solve all tasks. A task has its own feature-schema version, label semantics, data retention/consent eligibility, evaluation metrics, inference policy and rollout gate.

### Logical namespaces (not PostgreSQL ID ranges)

| Namespace | Examples | Kind |
| --- | --- | --- |
| CLS | physical/service/digital HCC ancestors, normalized class | taxonomy |
| ATR | size-system, color, material, brand, storage, work scope | global typed attributes |
| GEO | KATO / administrative area, offer coverage, selected fulfillment city | territory |
| LNG | ui, content, notifications, contact/communication, service instruction language | BCP-47 normalized preference/capability |
| BUY | budget, voluntary preferences, permitted behavioral aggregates, optional coarse adult age range | restricted buyer-derived |
| SEL | seller response/fulfillment rates, supported areas, verified capabilities | merchant facts |
| OFR | verified product price, full shipping fee, quantity, expiry, schedule | transactional terms |
| EVT | delivered request invitation, shown offer impression, favorite, negotiation, confirmed order | temporal events |
| MAT | exact/compatible/missing/rejected requirements, same_city, language_match | verified relational match evidence |
| OUT | seller response, buyer selected offer, order placed, fulfilled, ignored impression | supervised labels; **not input features** |

All `DictionaryNode.id` can use one Postgres generator; numeric values remain opaque identifiers. Prefixes above are semantic **feature namespaces**, not reserved DB numeric ranges. `OUT` fields must never leak into predictor features at decision time. Full HCC/KATO values must not be used as continuous numeric scalars. For optional fixed-order interchange use a frozen feature-name map *after* categorical one-hot encoding.

## Machine learning task portfolio

| ID | Task family | Core signals | Target and learning strategy | First stage |
|---|---|---|---|---|
| INT-01 | Product classification/normalization | CLS/ATR/LNG | governed synonyms + rules; optional classification trained on approved catalog corrections | P0 |
| INT-02 | Demand–supply eligibility + match | CLS/ATR/GEO/LNG/MAT/OFR | hard constraints deterministic; suitable sellers ranked afterward | P0 |
| INT-03 | Seller response prediction | SEL/GEO/CLS/OFR/EVT/MAT | invitation actually delivered → response within fixed 24h horizon; binary classification after logging | P1 |
| INT-04 | Buyer offer-choice prediction | BUY/CLS/ATR/OFR/SEL/MAT/EVT | eligible offer actually displayed → qualified buyer choice or order in a declared horizon | P1 |
| INT-05 | Buyer preference segmentation | consented BUY/CLS/ATR/GEO/LNG/EVT | initially explicit rules & interpretable profiles; optional clustering of adult opt-in data | P1 |
| INT-06 | Smart Deal Finder | CLS/ATR/GEO/OFR/MAT/SEL | deterministic all-in cheaper comparison; later learned ordering | P1 |
| INT-07 | Aggregated Seller Demand Radar | CLS/ATR/GEO/LNG/OFR/EVT | thresholded deidentified demand aggregates; forecasting only after meaningful coverage | P1 |
| INT-08 | Smart Bundle Builder | CLS/ATR/MAT/OFR | rule-based mandatory compatibility and verified bundles; association model later | P2 |
| INT-09 | Notification relevance | BUY/EVT/MAT/OFR | opt-in, policy caps and cooldown are hard gates; conditional helpful-action prediction | P2 |
| INT-10 | Seller risk/fraud indicators | SEL/OFR/EVT/OUT historical only | explainable rules first, anomaly scoring later; never automated punitive decisions | P2 |
| INT-11 | Fulfillment delay prediction | GEO/CLS/SEL/OFR, historical outcomes | regression for lead time only with known ground truth, separate hard SLA | P2 |
| INT-12 | Multilingual query matching | LNG/CLS/ATR | normalized synonyms KK/RU/EN/ZH, optional text classification with moderated corrections | P1 |

**Priority meaning**: P0 foundation, P1 after actual catalog and consent-eligible event history, P2 expansion requiring proven ROI. No claim that Tribuo is necessary for every item.

## Feature schema strategy: shared core + specialized extensions

Use separate `FeatureSchemaVersion` by model: `seller_response_v1`, `buyer_choice_v1`, `buyer_interest_v1`, `notification_relevance_v1`. Keep `common_match_v1` as a reusable logical feature group (not a separate scored model).

Feature metadata must specify: `name`, `sourcePath`, `scope`, `type`, `unit`, `categoricalVocabularyRevision`, `normalization`, `missingPolicy`, `asOfPolicy`, `consentPurpose`, `sensitivity`, `applicableClasses`, `outputIndex?`. A model artifact pins a checksum of ordered encoded feature names and revisions of code/attribute dictionaries. Training and inference produce **the same named set** for the same approved fact snapshot and schema revision; derived array order is a serialization concern.

Example seller-response feature vector:
```text
CLS.ancestor.01.02.003=1
ATR.brand.SAMSUNG=1
GEO.request_city_covered=1
LNG.communication_match=1
SEL.response_rate_30d=0.78
OFR.total_price_to_budget_ratio=0.97
MAT.all_hard_conditions_satisfied=1
```
The outcome `OUT.responded_24h` must be the training target, not an inference input.

### City and language

Keep three distinct concepts:
- Buyer **residence city**: optional profile choice, not disclosed to sellers as personal data.
- Demand **fulfillment city**: explicit request field for deterministic supply eligibility.
- Seller **service area**: verified business capability; territory equality/coverage is categorical/graph logic, not HCC-digit distance.

Languages are also purpose-specific: `uiLanguage`, `notificationLanguage`, `contentLanguages[]`, `communicationLanguages[]`, and `serviceInstructionLanguage` for language-dependent services. A product can match across languages independent of listing translation. For services with a must-have instruction language, that capability becomes a **hard constraint**; otherwise soft ranking / localized UI display. Use normalized BCP 47 and translation dictionaries; language is neither nationality nor inferred ethnicity.

### Birth year and sensitive leakage

Optional `birthYear` is not a classification attribute. It may support compliant coarse adult-band analysis only with a valid purpose and consent; no personalized marketing to minors without a separate child safety/legal decision. `birthYear` alone cannot verify legal adult status. No precise location, phone numbers, street addresses, secrets, special-category inferences or raw private messages enter ML features. Models default to excluding optional age features until a separate, measured uplift and privacy gate authorizes them.

## What should be shared, and what should not

**Shared once**: canonical item identity, class parent chain, typed units, enums, synonyms/translations, KATO links, permissioned event ingestion with as-of timestamps, match evidence, feature registry and versioned encoding helpers.

**Separate per task**: positive/negative label and observation window, selection bias handling, candidate filters, exposed features/consent purposes, model version and offline evaluation, deployment/rollout, policy gates and error/fallback behavior.

ML cannot determine a stock reservation, change an accepted price, auto-accept seller bids, bypass seller/sku ownership validation, or send unsolicited marketing push without explicit opt-in.

## Suggested product sequence and concrete evidence

| Slice | Business outcome | Verification |
|---|---|---|
| INT-R0 | One classification and feature schema across smartphones + electrician services | canonical codes and parent path, unit/size/language tests |
| INT-R1 | Buyer creates real typed demand, qualified sellers are discovered | PostgreSQL/Testcontainers, no foreign listings, no incorrect matches |
| INT-R2 | Two sellers compete with verified total quotes; bargaining still buyer controlled | immutable offers and concurrent one-winner/one-order |
| INT-R3 | Snapshot and actual impression/invitation events collected | as-of provenance, event delivery/censoring, retention, consent |
| INT-R4 | Offline Tribuo SellerResponse model compared with rules | chronological holdout, PR-AUC, calibration, Precision@K, cold-start |
| INT-R5 | Offer choice ranking and transparent buyer explanation | A/B with selection/exposure bias checks, user outcomes not just CTR |
| INT-R6 | Language-specific matching and Demand Radar | accurate service language hard checks, aggregated privacy threshold |
| INT-R7 | Personalized push, product bundles, ETA intelligence | explicit consent, cap/dedup, no false delivery claims, incremental benefit |

### Proposed tests (INT-01…INT-12)

1. Global dictionary ID/code stable across translations and clients.
2. Feature namespaces do not rely on hard-coded BIGINT number ranges.
3. A smartphone 128GB remains ineligible for hard 256GB requirement despite high ML score.
4. Service instruction language constraint rejects unsupported seller capability but UI language never blocks cross-language product matches.
5. SellerResponse labels require delivered invitations and completed response window.
6. BuyerChoice negatives require actual exposure, not absent offers.
7. Feature as-of prevents using purchase outcome to predict the same purchase.
8. Different buyer residence and requested delivery cities enforce requested city.
9. Revoked marketing consent suppresses marketing profiling/features and push but not transactional communications.
10. Incompatible model/encoder vocabulary triggers deterministic fallback.
11. No personal attributes leak in seller Demand Radar, private offer or logs; aggregate audience threshold.
12. Shared taxonomy migration does not silently reorder or reinterpret old model features.

## Decisions still requiring S2

- Seller invitation/offer impression event semantics and reliable delivery receipts.
- Classification attribute version compatibility / typed PostgreSQL shape.
- Feature cardinality/vocabulary thresholds and missing value representation.
- Response/choice observation horizons and label censoring.
- Buyer consent purposes, deletion/retention, minors and Kazakhstan legal review.
- Privacy thresholds for seller demand insights.
- Target pilot categories, conversion baseline and ML promotion/rollback thresholds.

**Implementation state:** this document is a product/architecture portfolio only. No new Tribuo dependency, trained model, production ML scoring, buyer profiling or data migration delivered by this change.
