# DEC-ML-RISK-001 — Reliable Learning Qualification & Statistical Fallback

**Date:** 2026-11-12. **Status:** S1 Java smoke, not a trained model nor production-approved inference. Existing domain and persistence entities remain untouched.

## Concept

Protect intelligent commerce recommendations with independent gates:
- **Data Sufficiency Gate**: consecutive full closed ISO weeks, daily completeness, verified stock-exposure coverage, configurable thresholds. Weeks with unknown stock exposure do not count as complete.
- **Temporal Validation**: five expanding-origin one-week-ahead folds. Training must end before target week. No shuffling of historical weeks; no random row-splitting. All challengers compete on identical folds and target semantics.
- **Baseline**: last-four-week mean of observed demand. Next-week observed demand is a proxy in the synthetic smoke, not proven latent market demand. Explicitly handle stockout censoring, source errors, and duplicate demand in production.
- **Stability**: report fold MAE, mean challenger MAE, baseline MAE, mean improvement, worst-fold error, population standard deviation. Mean of five forecasts alone is not an accuracy guarantee.
- **Policy & fallback**: candidate status only if sufficient data, five folds, benchmark uplift, bounded worst-period error, no critical drift, compatible model feature schema; otherwise simple 4w moving average or HCC-level baseline. This policy **does not** authorize automatic purchases or label a candidate production-ready.

## Implemented S1 code

- `DataSufficiencyEvaluator` with immutable `Policy` and `Report` for last N consecutive weeks, explicit days-of-exposure coverage.
- `TemporalBacktester` with model-agnostic `ForecastModel`; five fixed chronological origins and 4-week-average comparator. No Tribuo dependency added.
- `PredictionPolicyEngine` with reject reason codes and conservative fallback.
- `ReliableLearningSmokeTest` for 35 synthetic weeks, expanding-fold leakage guard, sparse/unknown data, grossly unstable challenger, schema mismatch, drift, temporal gaps, partial weeks.

## Important limitations and next S2 gates

1. **No evidence from real market**: synthetic monotonic weekly demand and a perfect toy challenger only prove mechanics. Do not claim measured model uplift.
2. **Five folds are smoke coverage, not sufficient inference confidence**. Before deployment add more historical origins / independent series, block bootstrap by periods/groups, stratification by cities/HCC families, confidence calibration, error intervals, and appropriate multiple-testing controls.
3. **Sample sufficiency != number of weeks alone**: effective number of independent sellers/SKUs and events, class balance for classification, cluster dependence, label confidence and exposure bias must be evaluated.
4. **Train/validation label windows**: correctly account for closed response windows, delayed orders, returned goods, missing events and data as-of. No information from after the decision instant.
5. **Inventory censored demand**: estimate observed actual sales separately from attempted unmet demand; don't fabricate demand from sales. Maintain snapshots of availability and price visibility.
6. **Drift Detector** is a placeholder policy input, not an implemented distribution monitor. Likewise schema compatibility is a supplied signal to be verified against an immutable artifact manifest before promotion.
7. **Safe fallback** must be computed from actual matching series; current policy chooses an algorithm and reason, not a numerical forecast.
8. **Thresholds** `26 weeks`, `0.95` completeness, `10%` error improvement, and worst MAE limit are **illustrative pilot policy**, not universally reliable defaults.
9. **No supplier purchase instruction** without lead time, gross margin, actual stock, inbound supply, cost, returns, and authority/approval.
10. Build optional category-level baseline only after HCC hierarchy coverage is governed. Keep current order and deal entities unchanged.

## S2 acceptance

- MR-01: dataset sufficiency on contiguous closed periods; missing, duplicate or partial observations produce auditable rejection.
- MR-02: five chronological folds; train max date strictly less than target min date; late labels excluded.
- MR-03: stable MAE and worst-period outputs on deterministic toy series.
- MR-04: no baseline uplift, critical drift, schema mismatch or high worst-fold error blocks use of ML.
- MR-05: insufficient sample size leads to deterministic fallback with reason.
- MR-06: lock training target/feature schema checksum and model artifact identity; no schema drift at inference.
- MR-07: real model outperforms baseline on untouched chronological holdout and meaningful business outcome.
- MR-08: no retroactive revision of closed-week features without version and audit trail.

See [DEC-INTELLIGENCE-002](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md) and [ADR-004](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md).
