# ZAKAZ Backend (first executable slice)

Java 21 / Spring Boot 3.5.16 / MVC / JPA / PostgreSQL.

- Fast tests (H2): `mvn -f backend/pom.xml test`
- PostgreSQL Testcontainers integration: `mvn -f backend/pom.xml verify` (requires Docker).
- Dev PostgreSQL: environment variables `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `mvn -f backend/pom.xml spring-boot:run`.
- Production: only preprovisioned reviewed schema (`ddl-auto=validate`). Liquibase deferred; migrations required before deployment.
- `GeoPoint` captures WGS84 latitude/longitude for a future InventoryLocation pickup/dispatch point. Private warehouse coordinates are not automatically public.
- `OrderNumberGenerator` uses random ZK-XXXX-XXXX. Persistence UNIQUE exists but collision-safe transactional retry and idempotent order creation service still need implementation.
- This is intentionally not a working checkout API. Buyer price authorization, stock reservation, identity and Outbox are not yet implemented; exposing HTTP order creation now would be unsafe.
