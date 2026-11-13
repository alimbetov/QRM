# R2 performance harness — no 5000-VU result yet

The single-use script `r2-confirm-reserve.js` is a **scenario harness**, not a benchmark result. It cannot run a meaningful checkout test against current QRM: seller-confirm REST is not implemented and the React UI is a static demo. No generated fake tokens are shipped.

For an S2-approved private environment, seed **at least 5000 distinct PLACED orders**, each bound to the appropriate signed seller administrator. Provide an ephemeral JSON fixture (outside Git, mode 0600) with:
```json
[
  {
    "orderId": "actual-order-uuid",
    "sellerId": "actual-merchant-uuid",
    "sellerJwt": "short-lived-signed-seller-admin-jwt"
  }
]
```

Run one request per VU:
```sh
k6 run -e BASE_URL=https://isolated-test.example \
       -e FIXTURES_PATH=./private-orders.json \
       -e VUS=5000 perf/k6/r2-confirm-reserve.js
```
Do not check tokens or customer PII into Git, and do not run against production. Collect k6 output, PostgreSQL locks/connections, Hikari, JVM CPU/heap/GC, Outbox age and error metrics. Run `post-run-integrity.sql` against the isolated DB to assert order and stock invariants. Thresholds shown are initial acceptance **candidates** (p95<1.5s, p99<3s), not measured or stakeholder-approved SLOs. Use separate normal-stock and high-contention datasets; a legitimate stock-conflict 409 is not a confirmed order.

Before running, implement and independently review the authenticated order-confirmation HTTP endpoint, real JWT provisioned seller memberships, test data seeding and an isolated staging cluster. See [R2.3 gates](../../docs/R2-3-QR-FIRST-E2E-5K-GATES.md).
