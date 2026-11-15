# ADR-005 — Nightly bucketed intelligence processing and temporal aggregate layers

**Date:** 2026-11-12. **Status:** ACCEPTED design direction / S2 IMPLEMENTATION SPEC PENDING. **No PostgreSQL partitions, worker scheduler, batch processing runtime, or actual model training are implemented by this document.**
Companion design: [DEC-INTELLIGENCE-002](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md), [DEC-ML-RISK-001](DEC-ML-RISK-001-RELIABLE-LEARNING.md), [ADR-004](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md).

## Decision

Separate latency-sensitive ZAKAZ e-commerce transactions from CPU/IO-heavy intelligence. Capture committed immutable events in a transactional outbox; stage append-only normalized facts, aggregate into **daily** then **closed ISO weekly** snapshots, derive independently versioned 1/4/12/52-week features, run qualification/backtesting/training off the critical path, and **atomically publish** approved reports/predictions. Use bounded asynchronous bucket work with lease/retry/checkpoint, not large synchronous transactions.

**Partition** and **bucket** are distinct: PostgreSQL temporal partitioning organizes physical storage and retention (RANGE by date); a work bucket is a deterministic logical shard of computation, initially `floorMod(stableHash(sellerId,skuId,territory),16)`, recorded with `bucket_algorithm_version`. Do not use volatile Java `hashCode` or ambiguous modulo of negative values in persisted assignment. Never use HCC category digits or sensitive buyer identifiers as bucket/routing keys. Assign each seller/SKU/city/day to exactly one bucket version; watermark and source revisions make replays idempotent.

## 1. Execution lanes

### Online (latency-sensitive)
- Read authoritative catalog/stock/verified quotes; create buyer requests, seller bids and per-seller orders in normal transactional path.
- Match hard requirements immediately, with current stock, seller ownership, delivery coverage and language-of-service checks.
- Read **last published** reports and model artifact through atomic version pointer; never train, scan raw long history, or run optimizer inside order transaction.
- Explicitly mark reporting data freshness; inventory/reservations always use fresh transactional facts, never last-night inventory as authoritative.
- Short incremental repairs for materially time-sensitive facts may run in constrained micro-batches, but not full training.

### Offline (bounded night window)
1. `OutboxDrainer` verifies at-least-once event intake and idempotent fact identities.
2. `DailyFactConsolidator`: validates/corrects sources (sale vs cancelled/refunded, active inventory exposure, delivered invites vs buyer demand), keyed by business date and dimension.
3. `ClosedWeeklyRollup`: only closes an ISO local Monday→Monday window when event-time cutoff and `source_watermark` prove closure; exposed late events trigger revision.
4. `RollingFeatureBuild`: derived 1/4/12/52 week windows from **unique** weekly snapshots, not summing pre-overlapping rolling windows.
5. `DataQuality/Sufficiency` checks every bucket + downstream grand totals, dimensions, completeness and late-event lag.
6. `Backtest/TribuoTrain`: run on explicit schedule or model drift with 5 chronological folds as smoke gate plus larger independent holdouts for promotion.
7. `ModelQualification`: no time leakage; verify train/validation windows and schema/vocabulary/model hashes, consistent candidate score vs baseline, uncertainty/drift.
8. `Publication`: after all required bucket results validated, set manifest pointer `ACTIVE` atomically; readers must never observe half-complete layers.

**Night window is a scheduling policy, not a correctness condition.** Jobs must be restartable, bounded, pausable when online load rises, and safely resume next run. If night window expires, hold previous published snapshots with an explicit stale flag; never publish partial results as complete.

## 2. Data layers and proposed tables

| Layer | Proposed table | Grain | Source of truth / qualification |
|---|---|---|---|
| L0 | `commerce_outbox`, `intelligence_event_fact` | unique event ID + source version/time | committed, deduplicated; partition event facts monthly **only if justified** |
| L1 | `intelligence_daily_metric` | seller × SKU × area × local date × revision | normalized sold/requested/impression facts and exposure days; monthly RANGE if large |
| L2 | `intelligence_weekly_metric` | seller × SKU × area × ISO week start × revision | seven expected daily days, qualified event watermark, no partial closed week |
| L3 | `intelligence_feature_snapshot` | task × subject × as_of × feature schema version × revision | 1/4/12/52w named features; indexed read-model |
| L4 | `model_training_run`, `model_artifact` | task × training window × schema × model revision | metrics, data lineage, storage hash, approval state |
| L5 | `published_prediction` / `current_publication` | task × subject × model revision × publication epoch | certified prediction/baseline and publication marker |
| CONTROL | `batch_run`, `batch_bucket_task`, `batch_publication_manifest` | run × period × layer × bucket × schema/revision | task state, lease, retry, checksums, lineage |

Start without unnecessary tables: L0–L2 and CONTROL minimal first, L3 only after feature usage is proven, L4–L5 after actual Tribuo training. Avoid combining all dimensions (SKU/category/brand/city/language/seller/user) in a giant cube: cardinality and privacy would explode. Aggregate only demanded query grains; keep private buyer profile separate from merchant analytics.

### PostgreSQL partition recommendations

- **High-volume, append-heavy facts:** monthly `RANGE(event_occurred_at)` using UTC.
- **Daily metrics:** monthly `RANGE(metric_date)`; **weekly:** consider quarterly or monthly `RANGE(week_start)` only after physical size and planner benchmarks justify it. ISO weeks may cross calendar months: partition key uses the Monday date itself, never a week number without ISO-year.
- **Task queue / registries / dictionaries:** ordinary tables until measured bottleneck.
- Respect PostgreSQL constraint rule: unique constraints on partitioned parent include all partition key columns, or enforce global event IDs via separate nonpartitioned dedup registry. Never promise a globally unique outbox event on a date-partitioned parent without compliant design.
- Every read query for partitioned data must filter the partition key for pruning. Create partitions ahead of time (e.g. 2 future, retention window), validate `EXPLAIN (ANALYZE, BUFFERS)`, indexes, vacuum, lock/DDL duration and archival.
- Retention: raw fact partitions expire only after reconciled audit/operational obligations and model reproducibility needs; archive exact snapshots or lineage before dropping.

Official references: https://www.postgresql.org/docs/current/ddl-partitioning.html ; https://www.postgresql.org/docs/current/sql-select.html .

## 3. Work bucket and scheduling contract

Proposed `batch_bucket_task` fields:
`id, batch_run_id, layer, business_period_start, business_period_end, bucket_version, bucket_id, source_watermark, schema_version, snapshot_revision, status, attempt, lease_owner, lease_until, checkpoint, rows_read, rows_written, checksum, started_at, completed_at, last_error`.

**Idempotency identity**: `(layer, business_period, bucket_version, bucket_id, source_watermark, schema_version, snapshot_revision)`. Avoid duplicate business results on retry: transactional upsert staging or versioned immutable writes; publish by unique manifest. Re-running the same source watermark must produce the same outputs/checksum. No distributed transaction across model artifact and DB: upload content-addressed artifact, verify hash, then atomically mark DB manifest published.

**Workers:** claim ready tasks in short PostgreSQL transactions using `FOR UPDATE SKIP LOCKED` (queue use only), set lease until and owner; compute outside lock; commit/checkpoint after bounded chunks; heartbeat; on crash lease expires, task may retry. Enforce attempt cap, exponential backoff, dead-letter/manual investigation, pause/resume, cancellation. SKIP LOCKED has an inconsistent view by design and must not be used for statistical source snapshots (see PostgreSQL SELECT docs).

**Initial policy** (benchmark candidates, not fixed SLA): 16 deterministic buckets, 1–2 worker threads, chunks of 500–2000 rows or byte-sized batches, concurrency `max=2`, bounded DB pool and CPU quota; seek `p95 order API` and DB transaction latency unaffected under night load. Use stable key hashing and versioned bucket count to avoid silent reshuffling. Increase only after load tests. Prefer local Spring-managed batch orchestration at first; adopt **Spring Batch** job repository/partition steps if restartability, dependencies and backfill scope justify it. No Kafka/worker microservice required initially. Official reference: https://docs.spring.io/spring-batch/reference/scalability.html .

## 4. Nightly example — Asia/Almaty

Illustrative local operating schedule **to be load-tested, not a guaranteed fixed business SLA**:
- 00:30–01:00: outbox drain / event watermark and quality checks.
- 01:00–02:00: daily consolidation, affected historical days and inventory exposure.
- 02:00–02:45: close ready weekly periods and build rolling features.
- 02:45–03:15: completeness/sufficiency/5-fold smoke on newly ready data.
- 03:15–04:30: conditional retraining (e.g. weekly, not nightly by default) on approved snapshot.
- 04:30–05:00: artifact verification + atomic publication + monitoring.
Actual task dependencies trump times. On a non-Monday, weekly table may only incorporate corrected historical periods; Sunday/Monday closure follows watermark, not midnight assumption. All UTC source timestamps must be mapped via zone data at event time; DST/cross-year test cases required.

**Load shedding:** worker stops starting chunks if DB p95, connection occupancy, replication lag or API p95 exceed separately calibrated limits. Admission controller always prioritizes online commerce; never consume the whole shared Hikari pool or all available CPU.

## 5. Late events and immutable historical aggregates

Close periods using per-source watermark (max guaranteed event time with expected events accounted for), **not simply local wall-clock midnight**. Separate `event_time`, `ingested_at`, `processing_time`, and `reported_revision`. A late order cancellation for week W creates a new W revision, marks downstream rolling windows touching W as stale, rebuilds impacted 4/12/52w feature snapshots and relevant model datasets without mutating previous published model lineage. Support 7-day correction window as **initial candidate**, with explicit manual historical reprocessing beyond it; regulatory/financial correction still must be accepted through an audit process. Do not claim immutable metrics are uncorrectable — immutable *revisions* are.

`as_of` and source watermark must be frozen in training datasets so no future event leaks into past features. **Positive and negative labels** use completed observation windows; a missing invite response before deadline is not yet a negative.

## 6. Smoke / test plan

- NB-01: 16 bucket assignment deterministic for 100K synthetic seller/SKU/city keys; one and only one bucket per key.
- NB-02: rerun same period/bucket/watermark/revision is idempotent; two workers cannot publish duplicates after lease expiry.
- NB-03: weekly closure respects ISO Monday, time zone and watermark, including year boundary and delayed source.
- NB-04: 1/4/12/52 features have no overlap double-count, exclude partial week, preserve unknown stock exposure as unknown.
- NB-05: source watermark changes trigger revision plus only relevant window invalidation.
- NB-06: simulate worker failure halfway: checkpoint resume does not alter total or publish partial manifest.
- NB-07: atomic publication: readers observe previous complete or new complete version, never intermediate buckets.
- NB-08: eligibility and buyer order transaction never read lagged materialized available stock.
- NB-09: five temporal folds remain chronological and leakage-free; backfill does not silently revise past model features.
- NB-10: benchmark 1K sellers × 100 SKU × 52 weeks (~5.2M weekly grain rows before city breakdown), with realistic skew, EXPLAIN pruning and DB load, not synthetic averages alone.
- NB-11: no re-identification of buyers in seller-visible aggregated geography/language cohorts; consent/retention enforced.
- NB-12: peak online load aborts/pause remaining workers; verify p95/p99 budget in load test and graceful stale reporting.

**Promotion gate:** S0 design → S1 deterministic bucket and layer tests (RED first) → S2 independent schema/operability review → S3 PostgreSQL migrations/Testcontainers + restart/replay/load acceptance → S4 scheduled guarded production rollout. No auto-merge into main based on a design document alone.

## 7. Risks to explicitly avoid

- **Partition ≠ parallelism:** adding dozens of partitions does not create extra CPU/IO capacity; excessive partitions harm planner/DDL overhead.
- **One-SKU model per seller:** underdetermined without enough history; train pooled category/city models, publish per-SKU predictions.
- **Nightly retrain of every model:** expensive and often unnecessary. Reuse last accepted artifact; retrain based on schedule, drift or uplift threshold.
- **Eager Cartesian aggregates:** never create all category × city × age × language × brand combinations. Privacy and exploding sparse cardinality.
- **Double-counted demand:** one BuyerDemand invited to five sellers must count once as a demand request, not five purchases.
- **Data freshness confusion:** nightly inventory features describe history; orders use transactional stock now.
- **Hard-coded scheduler in HTTP process with unbounded @Async:** guard worker concurrency and scheduler singleton/lease; use jobs that can resume.
