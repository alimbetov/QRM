# ML-R3.1 — exactly-attributed SellerBid and provable 24h negatives

**Repository:** alimbetov/QRM. **Branch:** `docs/adr-002-jpa-architecture-review`.
**Release:** HOLD. **Data/model:** RESEARCH_ONLY. **PR:** Draft; no merge to main.

## Scope and safety invariants

ML-R3.0 correctly distinguished an in-app invitation from an authenticated
seller view, but still counted any later valid seller bid against the same
demand/seller as an attributed response and censored all nonresponses.

ML-R3.1 adds an immutable link `ml_invitation_bid_attribution` between the
trusted invitation URI, its first authenticated view, the first bid revision,
the immutable bid snapshot and verified owner IDs. It is written within the
**same transaction** as the first real SellerBid and quote; no application
message, model score or client-provided invitation relationship may create it.
Earlier or legacy bids remain un-attributed. Later revisions are not counted as
new response events. Wrong demand/seller associations are rejected by composite
foreign keys and a BEFORE INSERT validation trigger.

Response label `POSITIVE` requires:
- exact `invitation_id` (not inferred by seller/demand/time proximity);
- matching acknowledged view, immutable first SellerBid and a real quote;
- committed `ml_trade_event` with `ingested_at` no later than the caller's cutoff;
- first bid after the view and within 24h and the demand's immutable expiry;
- no preceding bid by the seller and no intervening consent revocation.

## Source-based continuous-eligibility evidence

Flyway V31 creates a strict, append-only `ml_r31_eligibility_change` source
journal. PostgreSQL DML triggers capture seller-specific mutations to:

`seller_membership`, `seller_delivery_area`, `inventory_location`,
`inventory_balance`, `inventory_reservation`, `catalog_listing`,
`catalog_sku`, `catalog_sku_attribute_value`, `catalog_product`,
`catalog_category` and `catalog_category_attribute_rule`.

Global schema changes to attribute definitions/revisions and canonical units
are logged with `seller_id IS NULL` and conservatively censor **all**
potentially affected in-flight seller observations.

Seller-specific advisory transaction locks and a shared/exclusive global
catalog lock serialize eligibility changes against view acknowledgements.
The monitor epoch is written only after all source triggers are installed.
**No V30/pre-V31 view is ever retroactively certified as continuously eligible.**
Event history is not backfilled and historical consent is not inferred.

An explicit `NEGATIVE / VERIFIED_NO_RESPONSE_24H` requires *all*:
1. Actual acknowledged, quoteable view and monitor epoch preceding it.
2. Full 24-hour observation window elapsed before the exact UTC cutoff.
3. Immutable demand still valid for that complete observation interval.
4. Neither buyer nor seller revoked consent during the interval.
5. No other viewed invitation for the same seller/demand contaminating the cohort.
6. No prior bid, no attributed response, no un-attributed seller trade activity
   during the interval, and no buyer acceptance making the demand unavailable.
7. **No seller-scoped or global eligibility mutations** in that full interval.

Any gap is `CENSORED`; no absence-of-data heuristic can override the rules.
Each demand/seller pair contributes at most one observation even when policy
versions generate repeat invitations.

## Tests and reproducibility

Real PostgreSQL 17 Testcontainers tests assert server-authenticated HTTP
attribution, full-basket business flows, wrong/missing attribution, one
observation per demand/seller, concurrent replay, proof-carrying negative
labels, inventory/membership/territory/global source audit and mutation
immutability. The completed 24h window test uses a **deliberately synthetic
clock-shifted database fixture**, NOT evidence of a real 24h production cohort.

`MlSellerResponseDatasetBuilder` uses `REPEATABLE_READ`, the same caller's
cutoff for all joins, an explicitly versioned feature/label schema
`seller_response_24h.v2`, canonical ordering and SHA-256. Multiple builds
from an unchanged temporal source snapshot must match byte-for-byte.

**Important remaining limitation:** the SHA is calculated on a read-only
snapshot but no immutable persisted dataset manifest plus committed source
watermark exists yet. Transactions that commit *after* a cutoff with earlier
source timestamps must be handled by a later finalization/snapshot gate.
Until then a historical cutoff alone is not proof of globally frozen data.

## Explicit HOLDs

- A backend `POST /view` proves authenticated acknowledgement, not a human
  visual impression. React/Flutter UI must call it only after actual display;
  real browser/device + Keycloak UAT has not yet established exposure quality.
- Only ordinary writes through the authoritative PostgreSQL schema are
  monitored; privileged schema/trigger bypasses and unobserved external
  inventory state cannot be certified. Monitor lag/health and governance must
  be independently reviewed.
- Trigger overhead and advisory-lock contention require load qualification,
  including the 5,000-user trade scenario, before any production enablement.
- Dataset rows carry no buyer phone, email, address or IP.
- The builder **always returns `trainable=false`**. Positive + negative
  synthetic coverage tests are not evidence of a real representative cohort.
- Real 24h mature data, immutable dataset manifests, calibrated temporal
  train/validation/holdout with confidence intervals, baseline comparison,
  S2 independent review, a valid Tribuo model and ML lift are still pending.
- Never merge to `main` or activate ML based solely on passing these tests.

## Evidence tracking

Initial R3.1 RED: commit `5397d4b3f374910512eec00e8e7ba402b92c1b39`,
[PostgreSQL Actions](https://github.com/alimbetov/QRM/actions/runs/43592747458)
reproduced missing exact bid attribution. Implementation was added in
`a03d840c3a41fb4ba507902b84fb3a4d34674d56`. Real PostgreSQL
R3.1-specific tests passed 10/10 in
[Actions 43593744391](https://github.com/alimbetov/QRM/actions/runs/43593744391);
overall CI was blocked by *independent catalog R1B RED tests*, not by
R3.1 tests. A final exact-SHA full CI PASS is required before ML-R3.1 is
marked DB_VERIFIED.
