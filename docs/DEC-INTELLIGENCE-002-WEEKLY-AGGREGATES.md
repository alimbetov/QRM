# DEC-INTELLIGENCE-002 — Weekly layer / rolling features / inventory-advice smoke

**Date:** 2026-11-12. **Status:** S1 pure-Java smoke implementation, **not** a production aggregation pipeline or trained Tribuo model.

## Accepted decisions

1. Preserve existing Order, OrderLine, BuyerDemand, SellerBid and catalog contracts. Intelligence statistics are **derived read models**, not new columns on the transaction aggregates.
2. Use **ISO Monday→Monday reporting weeks in the explicitly selected business ZoneId (Asia/Almaty for this pilot)**, while raw events retain UTC instants.
3. Append/rebuild uniquely identified weekly snapshots rather than averaging an average of averages. Persist report period, dimensions, source watermark, schema version and revision to handle corrections. Current partial week stays separate, never train it as complete.
4. Start from deduplicated **daily** fact rows keyed by (seller, SKU, city, date) with sold/demand/impression counts and stock availability known/unknown. Real pipeline must also distinguish historical inventory exposure, seller published status, demand-to-SKU attribution, order cancellation/returns and refunds. Production truth from committed transactional Outbox + verified read models, not client-provided counters.
5. **No double counting:** 4-week and 12-week features are recomputed by summing unique stored weekly rows, not summing overlapping snapshots. Weekly revisions/replays are upserts by logical unique key with auditable source watermark.
6. Build `1w`, `4w`, `12w`, `52w` closed-window statistics where history exists; expose `observedWeeks` and stock exposure missingness. Distinguish zero sales from unknown inventory. Sales velocity uses **available days**, not blind calendar denominator.
7. Forecasting label is **next completed week sales / demand at a fixed cutoff**, features strictly before that week. Historical holds outs time-split. Avoid demand vs order duplicates and censored week labels.
8. **No purchase recommendation from smoke alone**: procurement recommendations need gross margin, lead time, supplier minimum order, returns, shelf life, demand seasonality, inventory exposure and available budget. Until then: diagnostics only.
9. Feature registry `inventory_advisor/v1` has 9 ordered named numeric feature slots; a real Tribuo trainer / inference adapter is a later independent slice.

## Executable smoke and expected report

Synthetic deterministic data: 12 closed weeks × 7 days × 3 SKU = **252 complete daily SKU observations**, plus one current-week row deliberately loaded with **99,999 sales** to detect leakage. Closed-week baseline is 36 seller/SKU weekly rows.

| SKU archetype | Last closed week sold | Last 4w sold | Last 4w demand | 4w in-stock days | 4w stockout days | Readout |
|---|---:|---:|---:|---:|---:|---|
| Fast-moving | 49 | 196 | 252 | 28 | 0 | Fast sales; replenishment evaluation subject to real remaining stock/lead time |
| Low-interest | 0 | 0 | 28 | 28 | 0 | Slow demand, but cannot call dead stock without historical inbound/on-hand |
| Stock-constrained | 8 | 32 | 252 | 16 | 12 | Demand remains high, sales limited by stock exposure; do **not** classify as poor seller |

This dataset is **engineered to test invariants**, not evidence of predictive power. A zero-SKU or null-stock row must produce presence flags, not a fabricated velocity. No price/city/age language-based discriminatory recommendation.

## Tests

`WeeklyIntelligenceSmokeTest`:
- 12 weeks, 36 closed aggregates, exact 1w/4w/12w sums, partial current week excluded;
- compare fast/slow/stock-constrained segments and stock exposure velocity;
- byte-for-byte stable nine-slot numerical feature projection;
- unknown exposure is not interpreted as 0;
- reject duplicate daily rows;
- reject merging windows from different seller/SKU keys.

## S2 / implementation gates

- PostgreSQL `weekly_metric_snapshot` schema with unique (snapshot schema revision, seller_id, sku_id, city_area_id, week_start), source watermark, period-end UTC, input quality flags and `calculated_at`, with idempotent replay and revision policy.
- Transactional Outbox/stock-change timeline, inventory snapshots and delayed/canceled sales reconciliation; version product class and territorial codes as-of.
- Verified buyer-demand vs SKU allocation; one demand can generate many invitation events but must not be counted as 5 independent purchases.
- Backfill, historical changes, UTC↔local DST/time-zone calendar; explicit complete-week policy, null/missing dates and open-week reporting.
- Materialized rolling reports/indexes and bounded query windows. PostgreSQL integration tests and generated stress sample 1000 sellers × 100 SKU × 52 weeks before any production volume claims.
- No age/birthYear needed for procurement. Language may segment **aggregated requested service language** but must not reveal individual shoppers.

See [ADR-004](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md) and [task segmentation](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md).
