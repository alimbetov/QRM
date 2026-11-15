# QA-01 — Regression foundation (completed functionality)

This is an implemented test slice on the existing branch, not a claim of a production-ready release.
It protects existing backend, React, and Flutter contracts without generating fake authenticated users,
relaxing authorization, or inventing performance results.

## Executable checks

- **Backend unit (Surefire):** inventory arithmetic and failure atomicity, reservation one-way release and
  numeric(19,3) bounds, sealed order amount/status transitions, Smart Deal comparable all-in pricing,
  eligibility and deterministic ties, AES-GCM buyer-contact identity binding and fail-closed key
  configuration, JPEG/PNG sanitization and malformed bytes.
- **Backend PostgreSQL (existing Failsafe + Testcontainers):** concurrent last-item reservations,
  atomic confirm/cancel/outbox, idempotent acceptance and worker delivery, Flyway and ownership
  constraints. These real-DB tests remain in the required `mvn verify` lane.
- **React (Vitest):** order acceptance HTTP conflicts, private buyer identity, safe URL encoding,
  denied enrollment, photo-feed validation and canonical public image paths.
- **Flutter (flutter_test):** real request shape and idempotency key, consent, denied order access,
  invalid response payloads; no checkout success on failed HTTP.
- **Coverage artifacts:** JaCoCo XML/HTML from backend `verify` and Flutter LCOV from `flutter test --coverage`.
  These are diagnostics, not an invented coverage percentage or release certification.

## Run locally

```bash
mvn -B -f backend/pom.xml test
mvn -B -f backend/pom.xml verify             # Docker/Testcontainers required
cd web && npm install && npm test && npm run build
cd mobile && flutter pub get && flutter analyze && flutter test --coverage
```

## Remaining release gates

1. Inspect measured branch/line coverage; set ratcheted thresholds only after reviewing a real baseline.
2. Real signed Keycloak OIDC/JWKS and email verification on an isolated staging setup.
3. Real Playwright staging journey (CI currently only compiles/lists; it does **not** execute it).
4. Browser and Flutter UI/integration paths; load and correct database invariants at 5000 VUs.
5. Independent S2 approval for the commerce and media release flags. Never infer approval from unit tests.

No merge to `main` or branch deletion is implied by this QA change.
