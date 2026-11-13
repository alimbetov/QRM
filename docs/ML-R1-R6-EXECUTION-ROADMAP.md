# ML-R1 → ML-R6 — executable release sequence for ZAKAZ

**Status:** ML-R1 and ML-R2 initial PostgreSQL implementation committed; ML-R2 external invitation dispatch/consent policy remains HOLD; ML-R3…ML-R6 are plans, NOT shipped.
**Repository:** `alimbetov/QRM`; continue only on `docs/adr-002-jpa-architecture-review` until the current Draft PR is independently reviewed and merged.
**Architecture baseline:** Java 21, Spring Boot MVC/JPA, PostgreSQL modular monolith, HCC 20-digit 2/2/3/5/8, typed AttributeRegistry, catalog/demand/bid/order/outbox. No standalone ML microservice, MongoDB or broker required at this stage.

## Non-negotiable invariants across all milestones

1. Catalog, HCC, approved ENUM values, verified seller memberships, current stock, territory, quote and buyer authorization remain authoritative server-side. ML may sort eligible results; it may not make an ineligible seller eligible or reserve stock.
2. `CustomerOrder` remains **one seller per order**. ML never automatically accepts a bid or places an order; user explicitly confirms.
3. Separate *event time*, *ingestion time* and *prediction-as-of*; no future joins, retroactive feature revision or label leakage. Training data is an immutable versioned output.
4. Any event delivery may be at least once. Observable business effects must be idempotent via PostgreSQL keys/constraints; a retry is not a new invitation.
5. ML rankings are optional. All external dependencies, unknown fields, stale model/schema, timeout, infrastructure failures and revoked consent fall back to deterministic rules with no data leak.
6. Train on privacy-minimized and lawfully obtained data. Model cannot invent seller prices, delivery fees, service coverage, travel time or availability.
7. No prod rollout from `main` until independent S2, migrations, performance and real Web/Flutter E2E gates pass. Use feature flags OFF by default.

## ML-R1 — authoritative canonical features (current candidate)

**Implemented:** `CanonicalFeatureAdapter` delegates to `CatalogSkuFactsResolver` / existing `CategoryAttributeRuleRepository`, validates real 20-digit HCC and approved ENUM/DECIMAL/BOOLEAN/TEXT; `ClassifiedOfferMatcher` gates hard requirements before `DealFeatureProjector` encodes frozen V1 names. PostgreSQL RED tests introduced in `74310f5`; GREEN verified in `8aba896` (six integration scenarios). Later QA branch CI must stay green.

**Exit gate:** ineligible hard mismatch cannot call scorer; foreign, DRAFT, inactive category, inactive ENUM, bogus attribute and forged HCC are rejected. Numerical decimals are canonicalized; identical canonical inputs produce identical vectors. No new dictionary schema.

## ML-R2 — trading events, seller invitations and deduplication

**Status:** PARTIALLY IMPLEMENTED / PostgreSQL CI PASS. Flyway `V24__ml_trade_events_and_invitation_decisions.sql` adds immutable `ml_trade_event` and `ml_seller_invitation` with unique `(demand_id,seller_id,policy_version)`. PostgreSQL triggers append `DEMAND_CREATED`, `BID_SUBMITTED`, `BID_REVISED`, `BID_ACCEPTED`, `ORDER_CONFIRMED` transactionally, and `SELLER_INVITATION_EVALUATED` on the deduplicated decision. `MlSellerInvitationService` is internal and **OFF by default**; it checks live PUBLIC+active HCC-compatible tracked SKU, seller membership, demand expiry, acceptance and fulfillment area. No actual invites, Push, notification consent workflow or event replay consumer has shipped. See [ML-R2 implementation status](ML-R2-IMPLEMENTATION-STATUS.md).

Proposed append-only domain events with explicitly versioned payloads:
- `DEMAND_CREATED` / `DEMAND_UPDATED` / `DEMAND_EXPIRED` (never email/phone in ML event payload)
- `SELLER_INVITATION_ELIGIBLE`, `INVITATION_QUEUED`, `INVITATION_DELIVERED`, `INVITATION_SUPPRESSED`, `INVITATION_FAILED`
- `SELLER_BID_SUBMITTED`, `BID_REVISED`, `BID_EXPIRED`, `BID_ACCEPTED`, `ORDER_CONFIRMED` and relevant cancellations

Event envelope minimum: `event_id UUID`, `event_type`, `schema_version`, `aggregate_id`, `aggregate_version`, `occurred_at`, `ingested_at`, `idempotency_key`, `causation_id`, `consent/purpose policy marker` when required. `V24` is now allocated to ML-R2 event/decision storage. Seller notifications and consent are separate future work; buyer Favorites is assigned **V25 or next available version** after verifying current HEAD.

Deduplication is a **business invariant**, not a cache TTL: unique `(demand_id, seller_id, invitation_policy_version)` for one invitation decision per policy round; unique `event_id` in consumed-event ledger and unique source-aggregate revision. Repeated delivery must produce one business invitation and one training label. No emailing/pushing before explicit delivery policy and consent checks.

**Implemented PostgreSQL checks:** RED `MlTradeEventPostgresIT` intentionally failed on missing tables; GREEN tests cover immutable events, bid revisions, duplicate `(demand,seller,policy)` rejection, seller membership/category/public listing/location suppression, expiry, six competing workers, versioned reevaluation. Further required tests before external invitation delivery: real notification consent, HTTP replay, crashed dispatcher, external delivery ACK, database event consumer dedup, revocation and privacy gates.

**Exit gate:** PostgreSQL journal and candidate evaluation achieved in CI. Full ML-R2 delivery gate **OPEN**: reviewed notification policy/consent, real at-least-once consumer ledger and crash/replay tests, independently verified side effects; independent S2 on exact SHA. No sending from the current service.

## ML-R3 — PostgreSQL Dataset Builder and time-control

**Status:** PLANNED. Train read-only from retained, allowed event/projection tables; do not create a second system of record.

Build `TrainingDatasetBuilder` with task-specific features and labels: seller response after an invitation, buyer choice after verified quote, etc. Each snapshot records `dataset_id`, `task`, `schema_version`, `source_watermark`, `as_of_cutoff`, `label_horizon`, `row_count`, `checksum`, provenance and missing-value rules.

Temporal joins must satisfy **feature observation time <= decision/invitation time**, and labels are admissible only if the full outcome horizon has elapsed by the extraction cutoff. Separate original event time and corrected/ingested time. De-duplicate by primary business event IDs and avoid overlapping same-demand entities between splits when evaluation requires independence.

**RED first:** delayed bid/response inserted after cutoff must not affect historical features; immature labels excluded; a correction is not a new positive; revoked consent and private seller/buyer fields excluded; stable identical checksums for identical snapshots; intentionally future-dated fact must make leakage test fail.

**Exit gate:** deterministic PostgreSQL dataset with validated temporal train/validation/test split and independently reproducible checksum. No Tribuo training until this passes.

## ML-R4 — Tribuo training, quality and independent review

**Status:** PLANNED, not installed as an active runtime dependency until ML-R3 dataset and objective baselines exist.

Start **offline** with one narrowly defined task, e.g., seller response probability. Freeze feature names/ordering, categorical codes, training seed, model hash and data version; keep a deterministic rule baseline. Evaluate event-time holdout, class imbalance, `PR-AUC` / ROC-AUC where appropriate, Precision@K, calibration (Brier/ECE), coverage/fairness slices and latency; define thresholds from measured baseline before promotion. Avoid training on post-invitation information, unobserved/non-invited merchants or invented negative labels.

**Independent review:** the evaluator and reviewer must differ from implementation author, reproduce metrics from dataset checksum, inspect leakage and seller-segmentation issues, and record APPROVE/REQUEST_CHANGES with exact model/dataset artifact digests.

**Exit gate:** proven out-of-time improvement over rules without violating hard-eligibility or privacy constraints. If not better, keep the deterministic engine.

## ML-R5 — Model Registry, inference, rollback, fallback

**Status:** PLANNED. In the existing backend module, create `ModelRegistry` and `PredictionEngine` interfaces; physical artifact storage may use a private existing object store, with metadata in PostgreSQL.

Versioned artifact contract: `model_id`, `task`, `model_version`, `feature_schema_hash`, `dataset_checksum`, `artifact_sha256`, `training_commit`, `approved_by`, `approved_at`, `state` (CANDIDATE/SHADOW/ACTIVE/RETIRED/REJECTED), `rollback_to`. Artifact checksum and frozen task/schema must be validated before any inference. Never deserialize arbitrary untrusted payload.

Inference contract: request only canonical eligible candidate features; bounded latency, timeout/circuit-breaker, deterministic fallback, sampled audit metrics (without private vectors); atomic switch by model version and immediate rollback.

**RED first:** corrupted artifact, schema mismatch, NaN, stale feature registry, model unavailable/timeout, slow JVM warmup, opt-out, wrong owner, shadow-mode candidate; all return safe deterministic ranking, never false order confirmation.

**Exit gate:** approved model activated without changing order invariants; rollback and cold restart verified using actual stored model artifact.

## ML-R6 — Backend + React + Flutter UX

**Status:** PLANNED. Backend provides ranked suggestions with eligibility reason codes and as-of timestamp, behind an OFF-by-default feature flag; UI uses the same API contract on React and Flutter.

Rollout: shadow scoring (not visible) → internal QA (fixed fixtures) → limited canary/A-B where lawful/consented → broader opt-in. UI must display *why* a recommendation matches and that verified final price/stock can change; no automatic bid acceptance or order placement. If ML is down, return safe deterministic sort without breaking photo catalog or QR route. If marketing push is part of a feature, respect explicit consent, category watch preferences, quiet hours and dedup independently of ranking.

**E2E:** same ranked products and reason codes in React/Flutter, opt-out hides personalization, stale PUBLIC/ARCHIVED seller excluded, fallback after timeout, real QR → bid → separately confirmed order → reservation. A/B measures qualified conversions, seller burden, response latency and opt-outs — not just click count.

**Exit gate:** Web/Flutter real device/browser evidence + independent S2, zero hard-rule violations, no private leakage, no regression in order creation/reservation.

## QA-02 and media reliability remain parallel, blocking release

Measured on `8aba896` using JaCoCo XML in GitHub CI: **88.74% overall lines**, **63.21% branches**. Priority branch hotspots: `media 43.33%`, `publicqr 58.89%`, `inventory 64.10%`, `security 64.29%`, `deal 64.34%`. Coverage is an indicator, not proof of security or correctness. Expand tested error/compensation and real registration/QR/order/reserve paths before introducing a hard baseline ratchet. Do not claim browser/SMTP/Keycloak E2E from mocked JWT.

Media qualification needs actual live RustFS + ClamAV, authenticated multipart → clean scan → private S3 PUT/GET → public access guard → object deletion/recovery, scanner outage and oversize negative tests. `deploy/media/compose.local.yaml` and `perf/k6/media-upload.js` are **test harnesses**, not evidence they ran. Rate-limit/Redis decisions require measured p95/p99, CPU, heap, 429, scan latency, throughput and error rates at graduated concurrency, not speculation. Avoid production rollout until real tests pass.

## Definition of ready-to-advance

| Gate | R1→R2 | R2→R3 | R3→R4 | R4→R5 | R5→R6 |
|---|---|---|---|---|---|
| Real PostgreSQL RED→GREEN | Canonical facts | Idempotent trade events | Temporal datasets | Training snapshots verified | Registry + serving |
| Negative security/privacy tests | HCC/ENUM/owner | Consent/dedup | No future leakage | No sensitive labels | Inference fail-safe |
| Deterministic fallback | Matcher | Outbox retry | Baseline snapshot | Rule competitor | Atomic rollback |
| External verification | Independent code S2 | Event review S2 | Dataset reproducibility | Model reviewer | Browser/Flutter E2E |

**Execution rule:** implement one milestone at a time, each in the existing working branch, preserving history; report exact SHA, tests, measured quality and unverified infrastructure separately.
