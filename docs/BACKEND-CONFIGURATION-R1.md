# QRM — Backend configuration baseline (R1)

**Status:** Approved provisional development configuration, 2026-11-12. Spring backend implementation is not yet present.

The owner requested no Liquibase during R1. Hibernate schema auto-update is **local-development only**; PostgreSQL remains authoritative. Restore version-controlled migrations before any production release.

## application.properties — common

```properties
application.time-zone=Asia/Qyzylorda

spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false

spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
spring.datasource.hikari.idle-timeout=30000
spring.datasource.hikari.connection-timeout=30000

logging.file.name=logs/qrm-backend.log
logging.logback.rollingpolicy.file-name-pattern=logs/qrm-backend.%d{yyyy-MM-dd}.%i.log.gz
logging.logback.rollingpolicy.max-file-size=200MB
logging.logback.rollingpolicy.max-history=7
logging.pattern.file=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [requestId=%X{requestId:-}] %logger{36} - %msg%n
logging.level.root=INFO
logging.level.com.netflix.eureka=OFF
logging.level.com.netflix.discovery=OFF
```

`logging.level.application=INFO` only affects a logger literally named `application`; use `logging.level.<application.package>=INFO` after the Java base package is chosen. Eureka log suppression is harmless but unnecessary unless Eureka dependencies are present.

The date pattern for file names belongs in `logging.logback.rollingpolicy.file-name-pattern`. Rolling policy requires an indexed `%i` token when size and time-based rotation are combined. The requestId MDC entry must be populated by an HTTP filter/trace integration; the pattern alone does not create it.

## application-dev.properties

```properties
spring.jpa.hibernate.ddl-auto=update
logging.level.org.zalando.logbook=TRACE
```

Set `logbook.obfuscate.headers`, `logbook.obfuscate.parameters`, `logbook.obfuscate.json-body-fields` and explicit body-exclusion policy if Logbook is installed. Never print passwords, Authorization, session tokens, OTPs, customer phone or PII in debug logs.

## application-test.properties — fast H2 tests only

```properties
spring.datasource.url=jdbc:h2:mem:qrm_test;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
logging.level.org.zalando.logbook=OFF
```

H2 is test-scoped and used for fast CI checks only. Do not force PostgreSQL compatibility mode as a substitute for actual PostgreSQL behavior. Parallel isolated tests should use distinct in-memory database names/context isolation where necessary.

## application-test-pg.properties — PostgreSQL integration

```properties
spring.jpa.hibernate.ddl-auto=create-drop
logging.level.org.zalando.logbook=OFF
```

Integration tests use a dedicated PostgreSQL Testcontainer with dynamic JDBC URL/credentials from Testcontainers (not a hardcoded localhost host). Database schema must be reproducible for each suite. For custom PostgreSQL SQL constraints, functions, indexes and locking statements, install them explicitly in test setup. Do not allow `create-drop` on shared databases. Before production, validate a reviewed SQL schema and migrations in the same PostgreSQL integration gate.

## application-prod.properties

```properties
spring.jpa.hibernate.ddl-auto=validate
logging.level.org.zalando.logbook=OFF
```

Production must have a pre-provisioned, version-reviewed schema and an operational database change process; `validate` does not create tables or indexes. If these are unavailable, deployment is blocked rather than enabling `update`.

## Timezone guidance

`application.time-zone` is an application-specific key: bind it to `ZoneId` and use it explicitly in business schedule logic. Store timestamps as UTC instants (`Instant` / timestamptz in PostgreSQL as applicable). Be careful that `Asia/Qyzylorda` (UTC+05 currently) is a chosen **business timezone**, not necessarily the account/user's preferred zone; use proper per-user or per-seller zone if needed.

## Caching roadmap

Spring Cache abstraction can be integrated later with optional Redis for public catalog/read models. No Redis requirement for R1 startup and no authoritative stock, reservation, order or accepted pricing sourced from Redis.

## Verification checklist

- `dev` with empty PostgreSQL creates Hibernate-managed tables and can restart without data loss.
- `prod` with empty schema fails fast; with vetted schema it starts and validates mappings.
- Logback rotates at 200MB and retains 7 days; validate archive/active-file semantics.
- RequestId is propagated into MDC and logs; sensitive data is redacted.
- Confirm the 5-connection JDBC pool does not become a throughput bottleneck before 5,000-VU testing.
- H2 fast lane validates unit, API/controller, simple Spring Data JPA mappings and pure service rules; target reproducibility without Docker.
- Mandatory PostgreSQL lane verifies correctness and isolation, including inventory atomic UPDATE, unique constraints, row locks, concurrency, order+outbox rollback and failure behavior. Do not ship with only H2 green.
