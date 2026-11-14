# ZAKAZ Intelligence Product Portfolio — 30 product capabilities

**Date:** 2026-11-12. **Status:** design opportunities, NOT shipped capabilities and NOT proven ROI.  
Architecture baseline: [DEC-INTELLIGENCE-001](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md), [UCE HCC](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md), [ADR-003](ADR-003-JAVA-ML-RECOMMENDATIONS.md), [TRIBUO-HCC review](REVIEW-TRIBUO-HCC-001.md).

**Execution roadmap:** [ML-R1 through ML-R6 (events → datasets → Tribuo → registry → React/Flutter)](ML-R1-R6-EXECUTION-ROADMAP.md). This is the ordered implementation and evidence contract; ML-R2…R6 are not claimed shipped.

## Product thesis and gates

A single stable HCC + governed universal attribute dictionary + KATO/territory + distinct language capabilities + consented buyer preferences powers multiple independent ML tasks. **Deterministic eligibility first, optional Tribuo ranking second, buyer/seller action third.** Product differentiation comes from better completed trades, not from having 30 predictive models.

Task levels:
- **RULES:** no supervised ML required. Deliver now using authoritative normalized facts.
- **HYBRID:** rules create eligible candidates; train Tribuo once outcomes exist.
- **TRIBUO:** only after valid labels, model evaluation and rollout gates.

## A. Buyer acquisition, matching and negotiation — differentiating P0/P1

| ID | Capability | Observable outcome | Approach | Phase |
|---|---|---|---|---|
| KF-01 | **Smart Demand** — one typed request, competing merchants | demand→eligible bid ratio, order rate | HYBRID | P0 |
| KF-02 | **Deal Compatibility Passport** — explain every attribute match/rejection | incorrect matches / complaints | RULES | P0 |
| KF-03 | **Verified All-in Best Deal** — price + quoted fees/time | verified savings, order rate | RULES→HYBRID | P0 |
| KF-04 | **Smart Bargaining Advisor** — propose responsible counteroffer range to seller, never autoaccept | seller reply, completed agreements | HYBRID | P1 |
| KF-05 | **Intelligent Seller Invitations** — invite eligible sellers likely to respond | median first response, response/notification burden | TRIBUO + hard eligibility | P1 |
| KF-06 | **Compatible Alternative Finder** — controlled substitutes with explainable deltas | buyer acceptance of alternatives | RULES→HYBRID | P1 |
| KF-07 | **Demand Subscription** — notify on newly verified matches to open demand | qualified responses, opt-outs | HYBRID, notification policy | P1 |
| KF-08 | **Basket/Kit Bargaining** — one bid for several compatible SKUs | basket completion and seller margin | RULES→HYBRID | P1 |
| KF-09 | **Language-Aware Service Match** — exact teaching/servicing language where essential | service booking success | RULES→HYBRID | P1 |
| KF-10 | **Negotiation Momentum** — predict non-response/expiry and suggest follow-up once | stale-session recovery without spam | TRIBUO | P2 |

## B. Buyer experience and personalization

| ID | Capability | Observable outcome | Approach | Phase |
|---|---|---|---|---|
| KF-11 | **Personal Shopping Radar** — monitor useful products and price drops | useful action per alert, disable rate | HYBRID | P1 |
| KF-12 | **Attribute Affinity** — preferences for sizes/materials/colors and needs | qualified click/order lift | HYBRID | P1 |
| KF-13 | **Cross-Language Discovery** — search KK/RU/EN/ZH with same canonical codes | no-result query rate | RULES→NLP | P1 |
| KF-14 | **Preference-Aware Catalog Ranking** — explain ranking, show price alternatives | actual qualified order uplift | TRIBUO | P2 |
| KF-15 | **Privacy-first Buyer Segments** — permitted aggregate interests, no person leakage | opt-in share and segment usefulness | SQL→optional clustering | P2 |
| KF-16 | **Push Fatigue Shield** — suppress over-messaging and duplicates | opt-outs, complaints per push | RULES→TRIBUO | P1 |
| KF-17 | **Replenishment Helper** — reminders for legitimately recurring items only | useful repeat orders, dismissals | HYBRID | P2 |
| KF-18 | **Language-Specific Content Quality** — detect missing/weak translation for desired locale | cross-language engagement, corrections | RULES→optional classifier | P2 |

## C. Seller intelligence and commercial tools

| ID | Capability | Observable outcome | Approach | Phase |
|---|---|---|---|---|
| KF-19 | **Demand Radar by territory** — privacy-thresholded unmet demand | seller listing additions, conversion | SQL→forecast | P1 |
| KF-20 | **Missing Assortment Radar** — demand attributes with no eligible stock | incremental matches, availability | RULES/aggregates | P1 |
| KF-21 | **Price Positioning** — compare equal product, qty, delivery; avoid individual discriminatory pricing | seller adoption and orders | RULES→HYBRID | P1 |
| KF-22 | **Stock Replenishment Planning** — forecast SKU demand with uncertainty | stockouts and waste | TRIBUO regression | P2 |
| KF-23 | **Merchant Response Coach** — recommend service windows from proven response metrics | response SLA, rejection | RULES→TRIBUO | P2 |
| KF-24 | **Listing Data Quality Assistant** — warn missing mandatory attrs / unit conflicts | correct listings, moderation load | RULES→classifier | P1 |
| KF-25 | **Storefront Localization Advisor** — highlight requests in languages not served | better verified language coverage | RULES/aggregates | P2 |

## D. Trust, fulfillment and operating efficiency

| ID | Capability | Observable outcome | Approach | Phase |
|---|---|---|---|---|
| KF-26 | **Fulfillment Promise Confidence** — estimate lead-time risk, avoid fabricated guarantees | late orders, ETA error | TRIBUO regression | P2 |
| KF-27 | **Trust by Verified Delivery** — fair seller quality score, new-seller uncertainty | verified outcomes, dispute rate | RULES→HYBRID | P2 |
| KF-28 | **Anomalous Listings Warning** — suspicious contradictions; manual review only | verified fraud catches, false positives | RULES→anomaly model | P2 |
| KF-29 | **Smart Pickup Matching** — find approved pickup points with supported fulfillment rules | pickup success and distance | RULES; later ETA prediction | P2 |
| KF-30 | **Service Scope Negotiator** — explain required materials, labor and exclusions | fewer service disputes | RULES→HYBRID | P1 |

## The five showcase features (focused commercial thesis)

1. **Smart Demand** (KF-01): user describes outcome/criteria once; eligible sellers compete.
2. **Deal Compatibility Passport** (KF-02): transparent exact/soft/failed characteristics per seller, with buyer-controlled substitutions.
3. **Verified Best Deal** (KF-03): compare true full cost; no fictitious delivery or stock.
4. **Language-Aware Service Market** (KF-09, KF-13): KZ/RU discovery independent of listings' language; language of service verified and honored as hard constraint if requested.
5. **Demand Radar** (KF-19 + KF-20): aggregate unmet demand instructs sellers what to stock/list without exposing individual profiles.

## Minimum viable experiment

Pilot smartphones + electrician installation in one city. Collect: published seller SKU facts, typed buyer requirements, verified service area and language, invitation delivery receipts, actual offer impressions, completed negotiations/order attempts, refusal reasons. Start with deterministic compatibility and verified all-in quotes; invite at least two merchants where available. Compare with catalog-only experience. Use business metrics: qualified-response ratio, time to first qualified offer, orders per demand, verified all-in savings, mismatch complaints, seller interruption rate.

**Rollout constraint:** withhold age/birthYear from marketing features by default; no targeted profiling of minors. Distinguish buyer request city vs residence. Language preference is **not** an inference of ethnicity. Push requires explicit purpose-scoped consent, unsubscribe, caps, dedup and freshness check.

## Foundation implementation delivered separately

Java-only `intelligence.FeatureRegistry` (frozen ordered names), `FeatureVectorEncoder` (deterministic sparse feature projection), `ClassifiedOfferMatcher` (canonical product + typed hard/soft attributes) and `DealFeatureProjector` (task-specific buyer-choice v1 vector). Existing `BuyerDemand`, `SellerBid`, `DealQualification` are used for full-basket structural validation.

**This is NOT actual Tribuo training/inference.** There is no Tribuo Maven dependency yet, no JPA for HCC/fact schemas, no vetted SKU/seller/availability source, no model registry, no HTTP endpoints, and no real consent-based event ingestion. Scoring eligible offers with a trained model is a separate gated slice.

## S1 tests and S2 requirements

- KF-T01: hard storage mismatch rejects before feature projection.
- KF-T02: verified matched facts → stable ordered vector with frozen feature names.
- KF-T03: unknown vs numeric zero distinguishable using knownness flags.
- KF-T04: duplicate/unregistered/nonfinite features rejected.
- KF-T05: full basket matches previously validated Demand/Bid line counts and quantities.
- KF-T06: seller/publication/coverage rejection cannot be overruled by ML.
- KF-T07 (S2): authenticate seller; retrieve live SKU→listing→seller chain from PostgreSQL; client snapshots are not sufficient.
- KF-T08 (S2): canonical attribute rules, conversions, compatible alternates, versioned attr schema.
- KF-T09 (S2): schema manifest SHA, vocabulary stability, replay older version and version mismatch.
- KF-T10 (S2): privacy, consent, language and minor-policy coverage.

One concern in this deliberately small prototype: seller location/language and attribute codes are provided as verified booleans/maps by the caller — the **production application must derive them from authoritative catalog and territory tables**, not trust HTTP JSON.

**Next slices:** (A) versioned dictionary + attribute applicability rules and immutable snapshots; (B) audited PostgreSQL seller eligibility; (C) Tribuo dependency + actual model/output contract when labeled history exists; (D) pilot instrumentation and measured model lift.

## Ten-feature adopted shortlist / ADR-004

Из 30 гипотез выделено десять целевых гибридных функций, закреплённых в [ADR-004-HYBRID-INTELLIGENCE-ENGINES.md](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md). Они не считаются реализованными. Отдельно зафиксированы границы Tribuo, Timefold, Lucene, LangChain4j, JGraphT, GIS, PostgreSQL и правил HCC; это не утверждение о необходимости всех зависимостей на старте. Первая демонстрационная связка — Smart Demand + Compatibility Passport + Verified Best Deal + Language-Aware Matching, с постепенным развитием десяти направлений.
