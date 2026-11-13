# QRM Smart Deal — isolated DB contract test harness

This is **not the production QRM Backend** and **not the final Liquibase migration**. It is an isolated runnable Java 21 + JUnit 5 + Testcontainers + PostgreSQL test suite for selected SD-R2 DB invariants.

## Run

Prerequisites: JDK 21, Maven 3.9+, running Docker daemon with permission to create containers.

```bash
cd smartdeal-contract-tests
mvn test
```

The test suite initializes a fresh PostgreSQL container using `src/test/resources/db/fixture.sql`. It includes a deliberately minimal schema for the Smart Deal aggregate without actual upstream tables, because the QRM application foundation and schema have **not been implemented**.

## Tests

- one current ACTIVE/SELECTED revision per seller/demand
- different sellers can bid on same demand
- replacement after SUPERSEDED
- only one SELECTED seller per demand
- selected bid must belong to same demand (deferred composite FK)
- conversion/demand + invitation unique constraints
- positive quantity, non-negative unit price/shipping

**Important limitation:** these are tests of database constraints, expected GREEN after the schema fixture is correct, not business-service RED tests. They do not yet test the Spring WebFlux/R2DBC path, transactional Order+Outbox, authenticating sellers, or actual matching. Those are the next S1-S2 gates and cannot pass without implementing the upstream application.

## Migration integration

Once baseline QRM tables are defined, convert the fixture into versioned Liquibase changeSets, replace placeholders for `buyer_user_id`, `seller_id`, `listing_id`, `order_id`, `negotiation_id` with real foreign keys and adopt the same named constraints/indexes. Do not run this fixture in prod. The DB design and qualification gates are documented in [SMART-DEAL-R2-S1-DB.md](../docs/SMART-DEAL-R2-S1-DB.md).

## IMPORTANT — obsolete architecture fork

Do **not** treat this fixture as production schema, do **not** copy its SQL into Flyway or implement R2DBC from it. The accepted QRM backend architecture is **Spring MVC + JPA/Hibernate + JDBC** per [ADR-002](https://github.com/alimbetov/QRM/blob/docs/adr-002-jpa-architecture-review/docs/ADR-002-MVC-JPA.md), with existing Flyway **V1–V11** and domain implementation in that architecture branch. This folder is a detached contract experiment from the old Smart Deal documentation branch and must be retired or compared against the **existing** JPA constraints. For real S1 work add tests to the actual backend's Maven module, using the existing Testcontainers/PostgreSQL suite; do not fork another persistence model.
