# SD-R2-S1 — PostgreSQL schema and RED test-first qualification package

**Status:** Draft schema + isolated Testcontainers schema-contract harness. Not production migration; no application backend exists in the QRM main branch. Do not merge migration into production until upstream User/Seller/Listing/Negotiation/Order FK types and naming are frozen and the independent S2 Review approves its oracles.

## Proposed schema

The standalone test fixture at `smartdeal-contract-tests/src/test/resources/db/fixture.sql` represents the **Smart Deal table constraints only**, with minimal substitute upstream IDs. It is *not* a second source of truth for user/listing/order entities.

Suggested core migration order:
1. Existing `users`, `seller_profile`, `listing`, `customer_order`, `negotiation_offer` and `outbox_event` schema.
2. `buyer_demand` FK to users, `seller_bid` FK to existing seller and listing, `match_invitation`, `smartdeal_conversion`.
3. Add composite selected-bid FK check or service transactional validation: selected bid MUST belong to same demand.
4. Partial unique on current bid `(demand_id, seller_id) WHERE status IN ('ACTIVE','SELECTED')`; unique bid revisions `(demand_id,seller_id,bid_version)`.
5. `smartdeal_conversion(demand_id)`, `accepted_bid_id`, `order_id` unique, existing `customer_order.accepted_offer_id` unique where present. No Order/inventory shadow table.
6. `match_invitation(demand_id,seller_id)` unique. Do not put notification status/read flags here; use existing Notification Center and Outbox.
7. `idempotency_request(actor_id,operation,idempotency_key,request_hash,response_ref)` shared core service if it does not already exist. Must reject reused key with changed payload.

## Critical DB constraints and caveats

- `buyer_demand.quantity > 0`, `seller_bid.quantity > 0`, `unit_price_kzt >= 0`, `shipping_kzt >= 0 OR NULL`.
- Runtime bid quantity must equal demand quantity; unit must match canonical unit code. SQL CHECK cannot compare rows across different tables; verify inside transaction, or denormalize stable request unit for composite FK.
- Demand `DRAFT/OPEN/SELECTED/ORDER_CREATED/CANCELLED/EXPIRED` with strict CAS version updates; bid `ACTIVE/SUPERSEDED/WITHDRAWN/EXPIRED/SELECTED/CONVERTED`.
- One active/selected bid **per seller+demand**, but multiple sellers may simultaneously hold ACTIVE bids. At most **one SELECTED bid per demand** is enforced by `buyer_demand.selected_bid_id` + atomic transition and optional partial index on selected bid status; the latter is recommended.
- No DB CHECK can assert listing is PUBLIC and active seller across tables. Application transaction locks/version checks and integration tests are mandatory.
- No guarantee of stock reservation; do not create InventoryReservation on Smart Deal selection.
- Currency is KZT only; BigDecimal NUMERIC(19,2) for totals/price is a **proposed** domain scale pending KZT pricing S2.
- For immutable bid revisions, update only status/version audit fields; never rewrite original unit price/quantity/terms. Bid revision insert + predecessor supersession is one transaction.
- `SELECTED` must not create accepted negotiation or Order yet. Explicit buyer create-order does so atomically in existing Order infrastructure, along with Outbox.

## Test scope in the harness

- `DB-01`: no two ACTIVE/SELECTED current bid rows for same seller+demand.
- `DB-02`: different sellers can have ACTIVE bids on same demand.
- `DB-03`: old SUPERSEDED revision allows one fresh ACTIVE bid.
- `DB-04`: demand/Order conversion unique across concurrent attempts.
- `DB-05`: duplicate invitation by seller/demand rejected even if candidate Listing differs.
- `DB-06`: invalid money and quantity rejected.
- `DB-07`: selected winner unique per demand.
- `DB-08`: transaction rollback Order+Outbox+conversion on simulated failure (**real implementation pending**).
- `DB-09`: idempotency same key+different payload 409 at API layer (**real implementation pending**).

`DB-01…07` are schema-contract tests, expected to turn GREEN for a correct fixture (they are not business RED tests). `SD-01…52` remain RED acceptance targets for the missing actual application layer. Explicitly do not label a passing schema test as feature-complete Smart Deal.

## Execution

Requires **Java 21, Maven 3.9+, Docker daemon accessible**:

```sh
cd smartdeal-contract-tests
mvn -q test
```

Testcontainers starts an ephemeral PostgreSQL. It does not need the QRM app or a cloud database. Pinned dependency versions are local harness defaults, to be aligned with repository BOM once backend exists.

## S2 blockers before actual Liquibase deployment

1. Upstream identities/table names and numeric currency/quantity scale.
2. Exact domain actor rules, seller access by invitation, full demand quantity and PUBLIC-only matching.
3. Transaction strategy for accepted negotiation snapshot + Order + Outbox in Spring WebFlux/R2DBC.
4. Partial unique/index semantics under concurrent Testcontainers workers.
5. Invitation and pricing TTL definitions, delivery cost disclosure.
6. Owner/approver review of SD-DEC-01…10 and SD-ACT-01…07.

No production code, successful test execution or full RED qualification is implied.

## Architecture correction — ADR-002 ACCEPTED (2026-11-13 review)

**Supersedes any WebFlux/R2DBC/Liquibase-first directions in this document.** The authoritative backend baseline is [ADR-002 MVC+JPA](https://github.com/alimbetov/QRM/blob/docs/adr-002-jpa-architecture-review/docs/ADR-002-MVC-JPA.md): Java 21, Spring MVC, Spring Data JPA/Hibernate, PostgreSQL JDBC, HikariCP, `@Transactional`, H2 unit tests and PostgreSQL Testcontainers. The implementation branch already contains **Flyway V1–V11**, historical demand/bid snapshots, accepted bid/order and seller confirmation with InventoryReservation/Outbox. Do **not** apply the isolated fixture or create a new Smart Deal schema until these JPA entities and Flyway migrations are reconciled at exact commit SHA. This standalone contract is only a historical experiment; the correct next S1 task is **additional JPA/Flyway integration RED tests** covering missing SD-R2 scenarios and existing inventory/Outbox transaction boundaries. Existing spec's simplistic one-line/one-seller model also requires reconciliation with implemented multi-line buyer-demand and bid snapshots before implementation. The old 'no backend exists' claims refer to the obsolete main-only view, not the implementation branch.
