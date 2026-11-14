# DEC-ML-RISK-002 — adaptive sufficiency qualification and bounded parallel nightly buckets

**Date:** 2026-11-12. **Status:** S1 deterministic Java foundation, not autonomous model rollout nor production distributed scheduling. Complements [ML-RISK-001](DEC-ML-RISK-001-RELIABLE-LEARNING.md) and [ADR-005](ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md).

## Decision

1. **No static universal 26-week rule.** Define bounded, versioned safety floor/ceiling and choose a per-task/cohort sufficiency candidate based on independently evaluated **historical out-of-sample** errors, fold stability, representation across independent series and observed stock exposure.
2. **Do not learn the gate on the same data used for final model approval.** Historical selection window and untouched final holdout must be separate; candidate policy changes are promoted after independent evaluation and provenance hash. Keep human approval/rollback for the initial rollout. Require a demonstrably superior performance against a 4-week moving-average baseline.
3. Do not average five random row shuffles from a weekly time series; use **five expanding-origin chronological folds** and replicate evidence across *independent cohorts* (seller/area/category), with clustered confidence intervals before production qualification. A pilot with five folds/cohorts cannot establish statistical certainty or rare-event performance.
4. **Conservative fallback:** if missing evidence, failed qualification, critical drift or incompatible feature schema, use conservative fixed sufficiency floor or a deterministic baseline. Do not silently loosen minimum sample count, day coverage or stock exposure.
5. **Nightly parallelism is bounded:** initially 16 logical buckets and 2 physical platform worker threads. Bucket IDs derive from canonical seller/SKU/area serialization via SHA-256 and versioned bucket algorithm. Each submitted computation runs once within a bounded worker pool in the local smoke.
6. CPU-heavy feature calculations and Tribuo training must run on **bounded platform threads**, not unconstrained virtual threads. Distinguish 1-2 DB I/O workers, a separate CPU executor with rate limits, and max one training job while sharing hardware with online commerce. Actual scheduler pauses under online pressure.
7. Parallel buckets **cannot publish partial results**. Current in-process executor returns all results only when every bucket succeeds; production publication still needs transactional staging+manifest, lease/checkpoint/job repository with retry and crash recovery.
8. Adapt parallelism independently from data sufficiency: tune worker count only from measured load, DB pool occupancy, CPU, query p95/p99 and replication lag with hysteresis, hard min/max and manual rollback.

## Implemented in S1

- `AdaptiveSufficiencyCalibrator.select()` accepts past evaluation Evidence for candidate policies, validates hard `Guardrails`, selects best mean MAE candidate only after baseline improvement and quality gates across five cohorts. No direct persisted policy change; returns a candidate policy + reasons.
- `BoundedBucketExecutor` with stable SHA-256 bucket mapping, fixed two-worker example and all-or-error return. Does not claim distributed reliability or cancellation after uncooperative tasks.
- JUnit smoke covers safe floor fallback, out-of-sample better policy, future/duplicate evidence rejection, independent-series floor, deterministic mapping, worker parallelism cap, all-or-nothing returned result.

## S2 blockers before production

- Add **real time cutoff source** and point-in-time dataset snapshots to Evidence; current `evaluatedWeek` is metadata supplied by caller, not cryptographically verified. Validate no shared training data leakage across cohorts or period.
- Avoid **selection overfitting** when testing many threshold candidates: untouched holdout and confidence intervals / sample-size constraints / multiple-comparisons review are required.
- Cohort weighting matters: average MAE across cohorts favours small cohorts, cannot simply optimize pooled business impact. Introduce explicit transaction-count weights, worst-segment guardrails, risk-sensitive objective and seller/region fairness audit.
- Percent gain with perfect baseline is undefined; rejected in pilot. Negative MAE or invalid metrics must never influence selection.
- Gradual policy promotion: propose -> review -> canary -> accepted; new policy version immutable with audit/rollback.
- **Production queue**: PostgreSQL `batch_run`/`batch_bucket_task`, `FOR UPDATE SKIP LOCKED`, lease expiry, heartbeat, capped attempts, checkpoint, source watermark, versioned partitions, replay, atomic manifest switch.
- **Backpressure:** max in-flight submitted tasks, bounded DB connection pool quota, online p95/CPU-driven throttling. Current `execute()` submits one future per bucket (up to config limit), so it is only an S1 prototype.
- **Operational stress**: 16→64 buckets and 1→4 workers benchmark under 5K commerce virtual users; no guessing an optimal worker count.
- Do not create any procurement/price decision automatically: confidence-advisory output, fully verified SKU stock/lead-time/cost business gate.

## Qualification matrix

| Test | Expected |
|---|---|
| AD-01 | Candidate weeks/coverage never below hard policy floor |
| AD-02 | Insufficient cohort/fold/series evidence -> conservative policy |
| AD-03 | Future / duplicate evidence does not influence promotion |
| AD-04 | Better out-of-sample challenger vs MA4 qualifies at S1 only |
| AD-05 | Worst-fold error or poorer baseline comparison blocks |
| BW-01 | 16 deterministic buckets and stable assignment |
| BW-02 | 2 platform workers do not exceed configured concurrency |
| BW-03 | Any failed bucket aborts in-process result publication |
| BW-04 | Crash/retry/lease/persisted publication required at S2 |
