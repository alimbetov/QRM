# ZAKAZ Backend — Java 21 / Spring Boot 3.5.16

Spring MVC + JPA/Hibernate + PostgreSQL 17 + Flyway + Spring Security OAuth2 resource server.

## Test lanes

- Fast JUnit/H2 tests: `mvn -f backend/pom.xml test`
- All JUnit + PostgreSQL Testcontainers/Failsafe: `mvn -f backend/pom.xml verify` (Docker required).
- PostgreSQL `test-pg` uses **Flyway V1–V14** then Hibernate `ddl-auto=validate`. H2 `test` explicitly disables Flyway and creates an isolated test schema. Never use H2 as evidence of production SQL migration compatibility.

## Database/schema ownership

- Fresh PostgreSQL environments: Flyway applies controlled versioned `db/migration/V1__domain_and_catalog_baseline.sql` through V14 on application startup. Includes existing `customer_order`, `order_line`, `inventory_location`, catalog tables, enum governance and seller membership. JPA only validates; it never updates the PostgreSQL schema.
- Existing **pre-Flyway** environments are intentionally blocked from silent migration. `spring.flyway.baseline-on-migrate=false` and `spring.flyway.clean-disabled=true`. Before upgrading a populated DB: backup, compare physical schema and data to V1, rehearse a reviewed adoption/baseline plan and verify checksums + migration history. **Do not** simply enable `baseline-on-migrate=true` or delete old tables.
- The older `db/manual/V001..V003` scripts remain for isolated historical integration checks; `db/migration/` is authoritative for new PostgreSQL environments. Retire manual duplicates only after transition review.
- Dev PostgreSQL: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, then `mvn -f backend/pom.xml spring-boot:run`. Development also uses Flyway; old dev DBs require manual adoption.

## Seller security (fail closed)

- One currently exposed seller write route: `POST /api/v1/sellers/{sellerId}/listings/{listingId}/publish`. It requires a verified Bearer JWT with `catalog:write` scope **and** an active server-managed `seller_membership` record matching the signed token `iss + sub`, requested seller ID and `CATALOG_EDITOR`/`SELLER_ADMIN` role. Listing ownership and required SKU attributes are independently checked.
- Client-supplied `seller_id`, JWT `seller_id` claims, or phone aliases never establish membership. There is no public membership creation endpoint; membership must be provisioned via approved trusted administration process.
- Production requires `ZAKAZ_JWT_ISSUER`, optional `ZAKAZ_JWT_AUDIENCE` (default `zakaz-api`) and a real OIDC provider/JWKS. In dev without a configured trusted issuer/JwtDecoder, write routes **deny all**; do not add test tokens or developer bypasses.
- Mock-decoder HTTP tests prove business ACL. `SignedJwtCryptographicSecurityIT` additionally verifies actual RSA signature, issuer, audience, expiry, scope and membership with locally generated test-only keys. It does not replace external OIDC/JWKS integration UAT.

## Current scope / safety gates

**Not yet a production checkout.** The order factory exists but authenticated buyer acceptance, seller confirmation, transactional stock reservation, idempotency and outbox remain R2 work. There is no live inventory validation in catalog publication; published listing does not imply stock or an approved merchant. No real ML training or autonomous procurement.


### V12–V14 rollout safety

V12 requires every existing customer_order to contain valid lines with matching immutable total; incomplete orders cannot be silently adopted. V13 checks that every balance/reservation references the same seller and correct SKU/order line. V14 intentionally **fails** on a DB that already has inventory reservations: an approved backfill/reconciliation strategy is required before rollout. Neither the new movement ledger nor this PR makes seller-reported stock externally verified. See [R2.1/R2.2 report](../docs/R2-1-HARDENING-AND-R2-2-INVENTORY-AUDIT.md).

### R2.2B — internal PostgreSQL Outbox worker

Flyway V15 adds dedicated queue coordination fields (`event_sequence`, status, attempts, claim generation, lease UUID/owner, next retry) and a durable idempotent **internal** inbox. Set `SPRING_PROFILES_ACTIVE=dev,outbox-worker` in a **separate** worker process to enable polling; API-only processes do not run this worker. Local projection is not an external notification delivery system. Config: `zakaz.outbox.batch-size` (default 10), `zakaz.outbox.max-attempts` (default 5), `zakaz.outbox.lease-seconds` (default 60), `zakaz.outbox.poll-delay-ms` (default 1000). Enforce slow sink timeout and lease heartbeat before replacing the internal sink. `JdbcOutboxQueue.health()` reports pending/in-flight/dead-letter/published counts and oldest undelivered seconds for future metrics integration. **V15 refuses to adopt nonempty pre-V15 outbox**, requiring explicit ordered historical migration. [Outbox qualification](../docs/R2-2B-OUTBOX-QUALIFICATION.md).
