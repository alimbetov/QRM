# R2.2B — PostgreSQL Fenced Outbox / Internal Idempotent Sink

**Date:** 2026-11-13. **Status:** code and PostgreSQL tests implemented in Draft PR #2; **production delivery NOT approved**. Independent S2 Review issue #3 remains open; dedicated Outbox contract issue #7.

## Decisions

- **PostgreSQL, not Redis**, owns claim coordination, retries, lease and event order. One dedicated Spring `outbox-worker` profile; API pods do not poll.
- Keep the order/stock transaction unchanged: it writes a durable outbox row with the order status and reserved stock, then commits. The dispatcher obtains a small batch with `FOR UPDATE SKIP LOCKED` in a separate short transaction and **never holds a database transaction across sink I/O**.
- Event delivery is **at least once**. `JdbcOrderEventProjectionSink` uses durable `(consumer_id,event_id)` uniqueness to ensure a single *internal projection effect* when the dispatcher repeats delivery. A generic broker or webhook adapter must implement this same idempotency contract before external integration is claimed.
- Use a database-assigned monotonically increasing `event_sequence`, not client timestamps, to preserve causal order among events of one order. Later events remain blocked while an earlier event is `PENDING`, `IN_FLIGHT` or `DEAD_LETTER`. Dead letters need explicit approved replay or intervention.
- Each claim increments `claim_generation` and gets a UUID `lease_token`. `ack`, `fail`, and `extend` require both plus an unexpired lease. Expired-claim stale workers cannot mark messages published. Clock comparisons are performed in PostgreSQL.
- Bound batch size (1..100), lease (100ms..5min) and attempts. Short bounded polls; default batch size 10, max attempts 5 and lease 60s; exponential retry 250ms up to 60s. The worker is single-threaded per instance; multiple pods parallelize through PostgreSQL.
- A local sink is extremely fast and does not need an automatic periodic heartbeat. `JdbcOutboxQueue.extend` supports an explicit heartbeat, but **a long-running external sink must implement cancellation/timeouts and periodic renewal** before production use. Returning `PUBLISHED` with the local sink means **projected internally**, not externally delivered.

## Implementation

| File / component | Role |
|---|---|
| Flyway `V15__fenced_outbox_delivery.sql` | status, attempt_count, generation, owner/token/lease, retry time, event sequence, indexes and internal deduped projection |
| `JdbcOutboxQueue` | bounded claim, ACK fencing, failed retry/backoff, explicit lease extension, expired-last-attempt dead-letter |
| `OutboxClaim` | immutable claim snapshot (event UUID, aggregate, event type, payload, sequence, generation, token, attempts) |
| `OutboxDeliverySink` | pluggable handler contract |
| `JdbcOrderEventProjectionSink` | durable idempotent internal projection only |
| `OutboxDispatcher` | no long transaction over delivery; exception goes to retry/DEAD_LETTER |
| `OutboxWorkerConfiguration` | polling only with explicit `outbox-worker` Spring profile |
| `OutboxWorkerPostgresIT` | two competing workers, token/reclaim, crash/replay dedupe, poison retry and causal order, dispatcher recovery, queue health counters |

### Deployment

For **development with PostgreSQL** only:
```bash
SPRING_PROFILES_ACTIVE=dev,outbox-worker \
DB_URL=jdbc:postgresql://localhost:5432/zakaz \
DB_USERNAME=zakaz DB_PASSWORD='***' \
java -jar backend/target/zakaz-backend-0.1.0-SNAPSHOT.jar
```
The `outbox-worker` profile enables the **internal DB projection sink**, not a production notification transport. In a real cluster isolate workers in a separate Deployment from API workloads, use distinct connection limits and CPU/memory quotas, set request/timeouts shorter than the lease, and do not enable this profile without approved monitoring and DB access policy.

**Migration refusal is deliberate:** V15 refuses to auto-apply to a database containing old outbox events because no authoritative aggregate sequence can be reconstructed from timestamps alone. A DBA must reconcile and baseline historical events, document their order, rehearse on a production-like backup and approve an adoption migration. Never remove this guard just to make CI or deployment pass.

### Test oracles

| ID | Oracle | Covered |
|---|---|---|
| OX-01 | 2 concurrent claimers never own same event | Yes, 2 threads / separate Postgres transactions |
| OX-02 | lease expiry and generation fence | Yes |
| OX-03 | crash after sink before ACK; one projection effect | Yes |
| OX-04 | bounded retry and poison event -> DEAD_LETTER | Yes |
| OX-05 | no deliverable event on rolled-back stock transaction | Prior R2.1 tests |
| OX-06 | one-order confirmed before cancelled | Yes |
| OX-07 | migration from a populated pre-V15 DB | **Fail-closed guard tested**; approved adoption plan still open |
| OX-08 | multi-process/pod restarts and production metrics | **Open** |

### Observability and migration tests

`JdbcOutboxQueue.health()` reports persisted PENDING/IN_FLIGHT/DEAD_LETTER/PUBLISHED counts and age of oldest undelivered event; there is no HTTP Actuator/Micrometer export yet. `OutboxLegacyMigrationGuardIT` verifies Flyway V15 rejects pre-existing unsequenced outbox history rather than assigning arbitrary causal order.

## Remaining S2 / production blockers

1. Real external sink (webhook, message broker or notification), subscription ACL, secret management, payload schema version and PII minimization.
2. Consumer-level idempotency and out-of-order protection **in every external system**, not just local DB projection.
3. Operationally controlled dead-letter replay, heartbeats for slow sinks, graceful shutdown/draining, distributed staging test with independently running processes/pods, alerting (oldest pending age, dead-letter count, attempts, lease expiry).
4. Existing-event reconciliation and database migration dry run, independent review, user/merchant identity onboarding.
5. Buyer-facing QR-first E2E transaction and 5000 concurrent confirmed orders: **not run**. See [R2.3/E2E and load gates](R2-3-QR-FIRST-E2E-5K-GATES.md).

**Release verdict:** R2.2B foundation implemented and Testcontainers-qualified. Multi-pod safety is founded on PostgreSQL claims but not yet load/operationally proven across separate deployed pods. No independent S2 approval; PR stays Draft.
