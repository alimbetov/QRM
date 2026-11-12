# ADR-003 — Java-native ML recommendations and personalized push

**Status: ACCEPTED (product/technology stack), 2026-11-12. Implementation is NOT authorized to send live marketing push without opt-in, measurement and operational gates.**

## Decision

Adopt **Java 21 + Spring Boot + PostgreSQL + Tribuo** inside the existing modular monolith. Do not introduce Python, a standalone training service, GPU infrastructure, Kafka, or vector DB for the first release. Tribuo is a Java ML library supporting classification, regression and evaluation; prefer minimal modules and a pinned released version rather than all-in-one or an unreleased snapshot. See [Tribuo documentation](https://tribuo.org/learn/4.3/docs/) and [upstream GitHub](https://github.com/oracle/tribuo).

The primary use case is **relevance ranking for optional recommended offers and marketing push**, not determining financial transactions or order status. Never make transactional order messages dependent on ML.

## Module boundaries

- `recommendation.events`: capture consent-eligible `BuyerInteractionEvent` (search filter, detail view, favorite, price watch, negotiation, purchase, hides, explicit feedback). Minimize identifiers, PII and retention; no continuous GPS or sensitive inference.
- `recommendation.features`: compute `BuyerInterestProfile` and per-(buyer,listing) `RecommendationFeatures` from point-in-time valid snapshots. Category/brand/attribute interest, budget, seller/city preference only where elected, recency/frequency and fatigue.
- `recommendation.training`: scheduled background training of Tribuo classifier; **no training on API request threads**. Label examples with a clear observation horizon and exclude features observed after notification/label, to prevent target leakage. Chronological train/validation/test split; negative sampling and class imbalance controls; cold-start baseline.
- `recommendation.inference`: thread-safe immutable model loaded by model version, with atomic swap after validation. Feature schema version, fallback to deterministic rules when model missing/incompatible/unavailable. No remote calls or DB training scans per candidate.
- `recommendation.notification`: explicit consent, purpose categories (transactional vs marketing), max frequency per user, time-of-day policy in configured timezone, deduplication by user/listing/campaign, stock/price/publication refresh, seller authorization and opt-out. Persist decision/reason in an audited minimal record and publish selected notifications via existing PostgreSQL Outbox.
- `recommendation.api`: ranked 'Recommended for you' Web/Flutter read endpoint with pagination and deterministic fallback. Service may not expose private profile data or use guessed location.

## Model

Start with a binary **Tribuo classification baseline** (linear/logistic) estimating probability of a defined useful engagement, such as `favorite_added` or `qualified_order_started` within a fixed observation window after an eligible recommendation. Model score is *not* probability of actual purchase unless trained/calibrated for that label. Compare with rule-based ranking; after data quality and volume support, evaluate random forests or gradient boosted trees in the same JVM. Avoid mixing labels in a way that masks actual buyer value.

Train and evaluate via reproducible job; persist serialized model artifact and metadata (version, data window, feature schema, metrics, checksum, training timestamp, provenance, evaluation criteria), not arbitrary Java object deserialization from untrusted sources. Validate model artifact integrity before promotion, and provide rollback.

**Offline gate**: PR-AUC versus baseline (especially for imbalanced outcomes), Precision@K, calibration, per-category coverage, leakage checks, evaluation on most recent data, sample size/confidence intervals. **Online gate**: A/B experiment (with a control group), qualified conversion and retention, opt-out rate, complaints, repeated/not relevant notification rate, suppression effectiveness. No optimization for clicks alone.

## Persistence candidates and Entity Registry impact

| Name | Kind | Owner | Initial phase |
|---|---|---|---|
| BuyerInteractionEvent | entity or append-only event (consent controlled) | recommendation | R-ML1 |
| BuyerInterestProfile | materialized profile/read model | recommendation | R-ML1 |
| RecommendationFeatures | Java record/value object, no dedicated table | recommendation | R-ML1 |
| MlTrainingRun | entity with data window/metrics/artifact SHA | recommendation | R-ML2 |
| MlModelVersion | entity with rollout, schema/version and checksum | recommendation | R-ML2 |
| RecommendationCandidate | computed read model, not permanent table by default | recommendation | R-ML2 |
| RecommendationFeedback | event/aggregate feedback or addition to existing event stream | recommendation | R-ML2 |
| NotificationDecision | audit record (reuse notification/outbox when sufficient) | notification | R-ML3 |

Do not add all these as JPA tables automatically. Reuse existing `Favorite`, `FavoritePriceWatch`, `ListingViewEvent`, `NotificationPreference`, `NotificationDelivery`, `OutboxEvent` if their contracts already support the new purpose. Define explicit collection purpose, retention, delete/opt-out and account separation before committing schema.

## First incremental S0→S4 slices

1. **ML-01**: typed events/feature contracts, purpose-specific opt-in, privacy/retention decisions and rule-based baseline; JUnit RED for consent, dedup, stale listing and seller/city constraints.
2. **ML-02**: PostgreSQL dataset builder from authorized historical events; leakage tests and training/validation split. Cold start handled by popularity plus category filters with hard suppression.
3. **ML-03**: Tribuo classifier training job, immutable artifacts, evaluation and deterministic fixture-based tests; pin dependency only when training code exists.
4. **ML-04**: ranking in buyer Web/Flutter by stable model version, latency/fallback tests.
5. **ML-05**: marketing Push ranking through notification policy and transactional Outbox, opt-out and caps, rate limiting and A/B gates.

## Acceptance oracles

- ML-01: no marketing push without explicit opted-in preference; transaction status push remains deterministic and separate.
- ML-02: no data from *after* the prediction instant enters features; train/test split chronological.
- ML-03: model loading rejects invalid checksum/schema and falls back safely.
- ML-04: buyer A cannot inspect buyer B's profile, activity or recommendation history.
- ML-05: identical event/campaign cannot generate duplicate push; buyer frequency cap honored.
- ML-06: recommendation excludes unpublished/foreign-private listings and stale price/stock claims.
- ML-07: users who disable personalization stop tracking for that purpose; retention/delete policy consistently applied.
- ML-08: PR-AUC and Precision@K measured against baseline and recent holdout; calibrated scores interpreted only for the measured label.
- ML-09: pushes can be independently disabled without changing order or stock transitions.
- ML-10: model promotions and rollback keep serving without training in HTTP path.

## Explicit non-goals and gates

No runtime model, user profiling, training dataset or real pushes are implemented by this ADR. Production rollout requires security/privacy review appropriate for Kazakhstan, confirmed permissions, defined retention, objective offline/online thresholds and evidence from executable tests. Keep catalog/transaction performance isolated from training tasks.
