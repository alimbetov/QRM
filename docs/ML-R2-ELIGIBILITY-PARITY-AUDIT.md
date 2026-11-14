# ML-R2-PARITY — Invitation eligibility aligned with seller commerce

**Status:** DB_VERIFIED on GREEN commit `7f8d9ca650290c96ff86cb472db38387158e0247`; independent S2 and production release still HOLD.
**Branch:** `docs/adr-002-jpa-architecture-review`. Do not merge to `main`.

## Incident and reproducible regression

RED commit `8d4c388f9bd534c14bd9201b75fe79551c40bad1` proved:
- seller owning only one SKU of a two-product demand was marked `ELIGIBLE`;
- fully reserved/zero available tracked stock was marked `ELIGIBLE`;
- stock distributed between origins, where the actual SellerBid writer requires one origin, was marked `ELIGIBLE`.

The pre-existing autonomous scanner was stricter than the internal evaluator, while
consumer projection and seller inbox visibility were weaker. This produced inconsistent
candidate, delivery and bid-readiness results.

## Implemented contract

- `MlSellerEligibilityPolicy`: shared PostgreSQL predicate for all demand lines,
  catalog status/category, tracked SKU units excluding SERVICE/LICENSE, quantity,
  free stock and a single active usable fulfillment origin.
- The evaluator uses that contract and emits immutable `INSUFFICIENT_STOCK` reason
  when the catalog/territory match but no same-origin free inventory exists.
- The scanner, event inbox projection and consent-gated read use the same predicate.
  A formerly eligible inbox entry becomes hidden when stock disappears.
- Seller invite membership now requires `SELLER_ADMIN`, matching the real seller
  workspace authentication contract.
- Existing historical decisions are append-only; a new policy version is needed
  to reevaluate. Migration V28 extends the allowed reason enum only.
- Quote-workspace SKU options prioritize stocked variants so that LIMIT 20 cannot
  obscure the only actually quoteable SKU.

## Explicitly not implemented

- BuyerDemand has no persisted hard/soft canonical attribute criteria. This
  parity gate is for product identity + sales unit + full basket + stock + territory,
  **not** proof of detailed HCC attribute matching. Extending demand snapshots
  requires a separate versioned specification and PostgreSQL tests.
- Stock is checked at evaluation/read/submit and seller confirmation, not reserved
  by invitation. Concurrent orders may exhaust inventory after matching;
  snapshot/recheck still mandatory.
- No automatic seller push, SMS or email. Feature flags remain OFF; S2, multi-node
  UAT, notification consent and 5K qualification remain HOLD.
- `seller_response_24h` labels cannot be inferred from eligibility: an evaluated
  or internally enqueued invitation is not proof of delivery, impression or view.
  ML-R3 must implement a versioned point-in-time dataset after reliable exposure
  evidence and a mature label horizon are defined.

## Acceptance checks

Run `mvn -B -f backend/pom.xml verify` on real PostgreSQL (Testcontainers);
assert the four parity cases, missing consent, seller role, delayed ACK/replay,
live stock revocation and the end-to-end SellerBid → order reservation path.
Never convert a failing RED test into a disabled or weakened test.

## Verified RED -> GREEN evidence (2026-11-14)

- RED: `8d4c388f9bd534c14bd9201b75fe79551c40bad1`,
  [PostgreSQL run](https://github.com/alimbetov/QRM/actions/runs/43560857223):
  three actionable invitation parity assertions failed against the original
  implementation, as expected.
- GREEN: `7f8d9ca650290c96ff86cb472db38387158e0247`,
  [Java+PostgreSQL run](https://github.com/alimbetov/QRM/actions/runs/43561956909):
  Java unit 110/110 PASS, PostgreSQL 124/124 PASS, Flyway V1-V29 applied,
  MlInvitationEligibilityParityPostgresIT 4/4, SellerInvitationBidWorkspacePostgresIT
  12/12, JaCoCo generated. React, Flutter and staging configuration CI also passed
  at that exact SHA.
- No real seller-response prediction, label-ready ML dataset, Tribuo model
  uplift, Keycloak browser/device E2E or independent S2 approval is implied.
- Later commits in this shared branch require their own exact-SHA CI verification.
