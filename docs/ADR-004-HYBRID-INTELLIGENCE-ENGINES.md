# ADR-004 — Hybrid Intelligence Engines for 10 ZAKAZ differentiators

**Date:** 2026-11-12. **Status:** ACCEPTED as target product portfolio and phased architecture; **not implemented/installed**. Applies to [ZAKAZ master requirements](REQUIREMENTS.md) and [Intelligence Portfolio](INTELLIGENCE-KILLER-FEATURES-PORTFOLIO.md).

## 1. Decision

Adopt **all ten proposed killer-feature concepts as planned capabilities**, retaining Java 21 / Spring Boot MVC / JPA / PostgreSQL **modular monolith**. **Do not install all ten libraries or ship any new service now**. First build shared HCC + AttributeRegistry + FeatureRegistry, real catalog/demand/bid/stock/geo contracts and reproducible events. Gradually activate adapters with measured need and tests.

Architecture engine interfaces (contract proposals, separate from domain):
- `CandidateSearchEngine`: authoritative PostgreSQL FTS for first release; Lucene when justified by relevance/scale/typos.
- `CompatibilityEngine`: deterministic typed HCC + attribute/units + buyer hard constraints + seller SKU/availability/territory; JGraphT only for complex verified relations.
- `DealPredictionEngine`: Tribuo per-task model + frozen features + deterministic fallback.
- `DealOptimizationEngine`: exact bounded enumeration/SQL for small inputs; Timefold for NP-hard constraint planning when relevant.
- `LanguageQueryEngine`: lexicon/normalized multilingual attributes first; LangChain4j for optional LLM-assisted query structuring only, with schema validation and user confirmation.
- `LogisticsEngine`: geocoding/verified geometry, travel times from supported GIS/routing provider, then Timefold for route planning; never treat KATO code distance as travel time.

All recommendation/optimization results are **advisory and time-bound**. No engine may approve order/stock reservation, bypass ACL, invent prices or delivery fees, accept bids without buyer action, or send marketing push without permission.

## 2. Accepted ten-feature matrix

| # | Product capability | Selected engines | Hard boundary / output | Release phase |
|---|---|---|---|---|
| K10-01 | **Smart Demand** | HCC + deterministic rules + Tribuo | Typed BuyerDemand, eligible sellers and ranked invitations; no ML needed for initial matching | P0 |
| K10-02 | **Smart Multi-Seller Deal Optimizer** | HCC + Tribuo + Timefold | Optional **simulation** of multi-seller basket, per-seller totals/fees/time; explicit split into **separate single-seller orders** only after buyer agrees; no multi-seller CustomerOrder aggregate | P2 |
| K10-03 | **AI Shopping Assistant** | LangChain4j + HCC + search | Converts free text into proposed typed criteria; validate codes/units, show user a confirmation; no automatic order or implicit consent | P2 |
| K10-04 | **Demand Radar** | PostgreSQL aggregates + Tribuo forecasting if measured benefit | privacy-thresholded, minimum aggregation sizes; no raw individual demand exposure | P1 |
| K10-05 | **Smart Bundle Builder** | HCC + verified graph relations (JGraphT optional) + Timefold if complex | Compatible SKU/service kits with mandatory exclusions/safety constraints and current quotes | P2 |
| K10-06 | **Multilingual Product Search** | HCC + lexical normalization/PG FTS → Lucene; later NLP | KK/RU/EN/ZH synonyms mapped to canonical codes; preferred communication language vs required service language distinguished | P1 |
| K10-07 | **Delivery Optimizer** | GIS/routing + Timefold | Real supported routes, capacity, stop/time-window constraints; **not** inferred from KATO numeric differences | P3 |
| K10-08 | **Buyer Segmentation** | PostgreSQL + Tribuo; Smile optional if algorithm benchmark justifies | consented behavior, optional city/languages; birthYear/age cohorts default **excluded** pending law, minimization, minors review | P2 |
| K10-09 | **Smart Bargaining Advisor** | seller-visible explicit pricing rules + Tribuo | Nonbinding decision support, no hidden discriminatory per-person prices; no automatic accept, no leakage of buyer private profile | P1 |
| K10-10 | **Compatible Alternatives** | HCC + AttributeRegistry + verified product-relationship graph | Exact/compatible/substitute reason codes, explicit opt-in for nonexact alternatives | P1 |

**No claim of new deployed capability**. Existing Java pure domain `deal/` and `intelligence/` foundation remains the starting point.

## 3. Shared foundations and operational boundaries

- **Shared canonical knowledge:** versioned HCC and classification aliases, globally typed attributes and values (color/material/size system/brand), canonical Product/ServiceSpec, SKU, translation/synonym dictionary, territory (KATO code **as an opaque categorical key**), seller verified service coverage, purpose-specific buyer language choices.
- **Data domains:** Catalog immutable facts and classification revisions; BuyerDemand / SellerBid / PriceOffer revisions; InventoryBalance & fulfillment; permitted events (impressions, delivered invitations, clicks, bids, outcomes) with as-of and label horizon; selected city distinct from optional residence; consent and retention.
- **ML contract:** separate FeatureRegistry per model task, fixed named feature vocabulary + schema and artifact checksums, supervised labels only from observed eligible events, chronological holdout, model metrics and rollback. Tribuo cannot train away a bad catalog; feature encoding precedes inference.
- **Optimization contract:** deterministic hard constraints are **non-negotiable** (SKU correctness, one seller per order, verified quantity, city, delivery fee, time windows, material compatibility). Timefold scores only *feasible* plans and is bounded by configured planning time and input size. Solver runs off transaction/API critical path (async, timeouts, cancellation); user sees 'proposal', not a confirmed allocation.
- **Search contract:** index is a derived read model from PostgreSQL, not inventory/price source of truth. Lucene index updates from committed events (Outbox). Search results are revalidated against publication/availability and current quote before ordering.
- **LLM contract:** LangChain4j orchestrates a configured provider, **not** an embedded guaranteed free/local LLM; external data transfers require privacy and provider review. Output follows validated typed DTO; never interpret prompt as authorization.
- **Graph contract:** JGraphT only optimizes verified relationships (requires, incompatibleWith, alternativeTo, worksWith). Category ancestry can begin as relational recursive queries; no graph DB required.
- **Location contract:** routing ETA and delivery cost only from qualified actual route/fee provider or explicitly quoted seller values, not straight-line coordinates if road/travel claims are made.

## 4. Prioritized dependency roadmap with evidence gates

| Slice | Prerequisite / deliverable | Tests and promotion gate |
|---|---|---|
| **P0-F1** | HCC + AttributeRegistry, classification/size/units/language, FeatureRegistry, deterministic match evidence and baseline BuyerDemand/SellerBid | JUnit + Testcontainers for FK/typed values; SKU mismatch must reject before ML; repeatable encoded features |
| **P0-F2** | Real public catalog, buyer/seller identity, seller eligibility and transactional offers / per-seller order | AuthZ/IDOR, published visibility, concurrent one-winner accept, one-order idempotency, no premature reserve |
| **P1-F3** | Multilingual matching, compatible alternatives, verified all-in comparison | Known synonym coverage, exact/soft requirements, no invented fees or incorrect substitutes |
| **P1-F4** | Smart Bargaining Advisor and privacy-safe Demand Radar (rule/SQL baselines) | Immutable offer versions, seller set prices voluntarily, aggregation privacy floor |
| **P1-F5** | Data instrumentation + first Tribuo SellerResponse & BuyerChoice offline baselines | completed-window labels, negative sampling, leakage testing, PR-AUC, Precision@K, calibration and A/B promotion |
| **P2-F6** | Multi-seller simulation optimizer and Bundle Builder | Timefold/equivalent benchmark vs bounded enumeration, feasibility zero hard violations, explicit buyer consent for multiple orders |
| **P2-F7** | AI Assistant (typed text parser), Buyer Segmentation and optional model serving alternatives | hallucinated class/price prevented; consent + retention/underage restrictions; LLM data-exfiltration tests |
| **P3-F8** | Delivery Optimizer with actual GIS/routing and dispatch support | validated road matrix, ETA, vehicle capacities, cancellation/replan, route feasibility, failover |

**Dependency acquisition policy:** no Spring Boot starters or Maven dependencies for Timefold, Lucene, JGraphT, LangChain4j, ONNX Runtime, DJL, Smile, ojAlgo or Kafka Streams until a vertical slice has objective acceptance tests and need. Validate exact compatibility with current Java/Spring dependency set, licensing, CVEs and licensing of external data/models **at time of adoption**.

## 5. Multi-seller basket legal/business distinction

A **BuyerDemand** may collect competing bids from several merchants. An **optimization preview** may display a composite basket across merchants as a convenience. However initial order rule is **one seller per CustomerOrder**. A multi-merchant preview must produce separately confirmed quotes and separately idempotent orders by merchant on explicit buyer approval. It must show split fulfillment, seller policies, individual delivery fees, total and possible partial failure; do not promise a single atomic multi-merchant checkout. Cross-merchant atomic checkout/payment is a **new S2 design decision** and is not silently authorized by this ADR.

## 6. Ten-feature acceptance matrix

| ID | Test oracle |
|---|---|
| K10-A01 | Incorrect hard HCC / attribute / service-language match never reaches ML scoring or invites seller |
| K10-A02 | Timefold plans cannot violate SKU ownership, quantity, warehouse inventory, hard budget or service constraints |
| K10-A03 | Composite preview is **not** a multi-seller CustomerOrder; buyer explicitly authorizes each seller order |
| K10-A04 | Unknown delivery fee never appears as zero in a 'verified all-in cheapest' claim |
| K10-A05 | Search result and LLM-derived suggestions are revalidated against server catalog and authorization |
| K10-A06 | LLM cannot create order, negotiate, expose PII or dispatch notifications without verified commands |
| K10-A07 | Search on KK/RU/EN/ZH preserves category IDs; mandatory service language evaluated separately |
| K10-A08 | Graph alternative requires buyer substitution permission and transparent attribute differences |
| K10-A09 | Seller analytics satisfy minimum threshold and cannot reveal any individual's private demand |
| K10-A10 | Buyer profiling disabled or consent withdrawn: marketing feature calculation and pushes suppressed |
| K10-A11 | Model/feature schema mismatch or inference timeout falls back to safe deterministic sort |
| K10-A12 | Route matrix/solver has trusted time/distance inputs, bounded execution and no invented ETA |
| K10-A13 | Price advisor cannot autochange price or use undisclosed sensitive buyer characteristics |
| K10-A14 | Full acceptance tests compare solver quality against baseline and guard against hard-constraint violations |

## 7. Focused killer-feature thesis

At launch showcase **Smart Demand + transparent compatibility passport + verified total cost + language-aware matching**, which work before an ML training corpus exists. Add **Smart Bargaining Advisor**, **Demand Radar** and **Tribuo ranking** only after data/evidence. Optimize multi-seller baskets and routing only after the first complete single-seller order/fulfillment path is stable.

## 8. Explicit non-goals

Not a commitment to 10 running AI frameworks or 10 deployed ML models. No split payments, escrow, multi-seller atomic checkout, unverified ETA, automated financial settlement or unconstrained autonomous agents. The only implemented intelligent Java layer currently is the deterministic foundation/feature encoding; production matching, real ML inference and multi-merchant optimization remain future work.
