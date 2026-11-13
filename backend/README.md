# ZAKAZ Backend — Java 21 / Spring Boot 3.5.16

Spring MVC + JPA/Hibernate + PostgreSQL 17 + Flyway + Spring Security OAuth2 resource server.

## Test lanes

- Fast JUnit/H2 tests: `mvn -f backend/pom.xml test`
- All JUnit + PostgreSQL Testcontainers/Failsafe: `mvn -f backend/pom.xml verify` (Docker required).
- PostgreSQL `test-pg` uses **Flyway V1–V18** then Hibernate `ddl-auto=validate`. H2 `test` explicitly disables Flyway and creates an isolated test schema. Never use H2 as evidence of production SQL migration compatibility.

## Database/schema ownership

- Fresh PostgreSQL environments: Flyway applies controlled versioned `db/migration/V1__domain_and_catalog_baseline.sql` through V18 on application startup. Includes existing `customer_order`, `order_line`, `inventory_location`, catalog tables, enum governance and seller membership. JPA only validates; it never updates the PostgreSQL schema.
- Existing **pre-Flyway** environments are intentionally blocked from silent migration. `spring.flyway.baseline-on-migrate=false` and `spring.flyway.clean-disabled=true`. Before upgrading a populated DB: backup, compare physical schema and data to V1, rehearse a reviewed adoption/baseline plan and verify checksums + migration history. **Do not** simply enable `baseline-on-migrate=true` or delete old tables.
- The older `db/manual/V001..V003` scripts remain for isolated historical integration checks; `db/migration/` is authoritative for new PostgreSQL environments. Retire manual duplicates only after transition review.
- Dev PostgreSQL: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, then `mvn -f backend/pom.xml spring-boot:run`. Development also uses Flyway; old dev DBs require manual adoption.

## Seller security (fail closed)

- One currently exposed seller write route: `POST /api/v1/sellers/{sellerId}/listings/{listingId}/publish`. It requires a verified Bearer JWT with `catalog:write` scope **and** an active server-managed `seller_membership` record matching the signed token `iss + sub`, requested seller ID and `CATALOG_EDITOR`/`SELLER_ADMIN` role. Listing ownership and required SKU attributes are independently checked.
- Client-supplied `seller_id`, JWT `seller_id` claims, or phone aliases never establish membership. There is no public membership creation endpoint; membership must be provisioned via approved trusted administration process.
- Production requires `ZAKAZ_JWT_ISSUER`, optional `ZAKAZ_JWT_AUDIENCE` (default `zakaz-api`) and a real OIDC provider/JWKS. In dev without a configured trusted issuer/JwtDecoder, write routes **deny all**; do not add test tokens or developer bypasses.
- Mock-decoder HTTP tests prove business ACL. `SignedJwtCryptographicSecurityIT` additionally verifies actual RSA signature, issuer, audience, expiry, scope and membership with locally generated test-only keys. It does not replace external OIDC/JWKS integration UAT.

## Current scope / safety gates

**Not a production checkout.** R2.0B/R2.1/R2.2 internal order acceptance, seller confirmation, atomic reservations, movement audit and PostgreSQL internal Outbox exist. R2.3 includes real HTTP routes but they are OFF by default pending independent S2/real IdP, warehouse provenance, real browser/client journey, phone privacy policy and load qualification. Published listing does not imply verified stock or approved merchant. No real Tribuo training.


### V12–V14 rollout safety

V12 requires every existing customer_order to contain valid lines with matching immutable total; incomplete orders cannot be silently adopted. V13 checks that every balance/reservation references the same seller and correct SKU/order line. V14 intentionally **fails** on a DB that already has inventory reservations: an approved backfill/reconciliation strategy is required before rollout. Neither the new movement ledger nor this PR makes seller-reported stock externally verified. See [R2.1/R2.2 report](../docs/R2-1-HARDENING-AND-R2-2-INVENTORY-AUDIT.md).

### R2.2B — internal PostgreSQL Outbox worker

Flyway V15 adds dedicated queue coordination fields (`event_sequence`, status, attempts, claim generation, lease UUID/owner, next retry) and a durable idempotent **internal** inbox. Set `SPRING_PROFILES_ACTIVE=dev,outbox-worker` in a **separate** worker process to enable polling; API-only processes do not run this worker. Local projection is not an external notification delivery system. Config: `zakaz.outbox.batch-size` (default 10), `zakaz.outbox.max-attempts` (default 5), `zakaz.outbox.lease-seconds` (default 60), `zakaz.outbox.poll-delay-ms` (default 1000). Enforce slow sink timeout and lease heartbeat before replacing the internal sink. `JdbcOutboxQueue.health()` reports pending/in-flight/dead-letter/published counts and oldest undelivered seconds for future metrics integration. **V15 refuses to adopt nonempty pre-V15 outbox**, requiring explicit ordered historical migration. [Outbox qualification](../docs/R2-2B-OUTBOX-QUALIFICATION.md).


### R2.3 public QR and authorized commerce HTTP — default OFF

Enable only for an independently approved isolated staging environment. `zakaz.qr.http.enabled=true` enables public QR GET, guest contact POST, seller QR PNG and seller lead inbox; `zakaz.commerce.http.enabled=true` enables BuyerDemand/SellerBid/Order HTTP. Both default false. `zakaz.qr.public-base-url` must be explicitly configured to canonical HTTPS root for seller PNG; no request Host spoofing. Lead POST/decrypt requires `zakaz.leads.phone-key-base64` from secret manager (32 raw bytes base64), missing key returns 503. Never store keys or phone in Git/logs. Lead read requires a verified seller with `leads:read` scope and `SELLER_ADMIN` server role; all contact disclosures are audited. Buyer demand/accept requires `deal:write`, buyer order read `order:read`, seller bid `deal:bid`, seller confirm/cancel `order:confirm`, plus seller membership and buyer account links. Flyway V16–V17 add QR links/encrypted callbacks and PII-read audit. Tests validate PostgreSQL HTTP flows but no production OIDC/ingress/mobile browser qualification. [R2.3 contract and gates](../docs/R2-3-HTTP-API-QR-JOURNEY.md).

## R2.3B — public draft SKU unit price (KZT)

Flyway V18 adds nullable `catalog_sku.public_unit_price_kzt numeric(19,2)`. An authenticated catalog editor with server-side membership can set a strictly positive, two-decimal price while the listing is `DRAFT`:

`PUT /api/v1/sellers/{sellerId}/listings/{listingId}/skus/{skuId}/price`

Body: `{"priceKzt":125000.50}`; response: 204. Requires `SCOPE_catalog:write`, correct seller membership and `zakaz.qr.http.enabled=true` (default false). Ownership and draft state are checked under a listing row lock; V3 protects the SKU price from direct SQL modification after publishing. No silent rounding is permitted. The public QR response contains `variants[].priceKzt` as a decimal **string** or null for legacy listings without a configured price. React displays the string losslessly or "Цена уточняется". It is an indicative unit catalog price, **not** the immutable accepted bid with shipping/discounts/tax; live repricing after publication requires a separate revisioned-price design. Do not imply any checkout is completed by showing the price.

## R2.3C — public catalog and owner-scoped bid-read API

Optional anonymous `GET /api/v1/public/catalog?query=&categoryCode=&page=0&size=12` and `GET /api/v1/public/catalog/categories` require `zakaz.catalog.http.enabled=true` (default false). Only PUBLIC listings with QR links appear; owner/stock data is never disclosed. Canonical productId allows a later authenticated BuyerDemand, but indicative public price is not an all-in quote.

Protected `GET /api/v1/buyers/demands/{demandId}/bids` requires `zakaz.commerce.http.enabled=true`, valid JWT with scope `deal:write` and a server-provisioned buyer-account link matching demand ownership. Returns current nonexpired seller quote revisions capped at 50; no seller warehouse PII. Acceptance reuses transactional `BidAcceptanceService`: one PLACED order, no reserve until seller confirmation. See `docs/R2-3C-REAL-CATALOG-BUYER-CLIENTS.md` and S2 issue #3.
