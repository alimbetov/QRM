# QA-02 — Flutter UX/API defect audit

Scope: working branch `docs/adr-002-jpa-architecture-review`, Flutter source and its Spring HTTP contracts. No physical-device UAT was performed.

## Findings and implemented protection

| Priority | Finding | Change | Evidence |
|---|---|---|---|
| P0 | HTTP response could wait indefinitely, permanently leaving busy/loading UI | Set a configurable end-to-end client request timeout | `zakaz_api_hardening_test.dart` stalled request |
| P0 | Incomplete success responses looked like empty catalogs, bids or orders | Validate required server response fields and reject invalid shapes | API hardening tests (missing items/bids/orderId) |
| P0 | API base could include embedded user credentials, path, query or fragment | Require an HTTPS root URI (loopback HTTP only for emulator) | API URL guard test |
| P1 | QR and catalog loads had no retry after network failures | Explicit retry state with no demo fallback | `mobile_screens_test.dart` |
| P1 | First catalog SKU might be a non-buyable service unit even when a physical SKU exists | Expose available supported request units, clarify canonical-product demand | Multi-unit widget test |
| P1 | Buyer OIDC succeeded but profile fetch failed, forcing another sign-in | Keep in-memory signed-in session and offer profile retry | Auth gateway widget test |
| P1 | Buyer and seller ID rendering used unguarded `substring(0,8)` | Safe short ID presentation | Validation and buyer UI tests |
| P1 | Fractional PCS quantities passed mobile UI but failed on backend | Shared quantity validation for discrete units | Unit tests |
| P1 | QR guest consent/phone validation silently ignored invalid submissions | User-facing validation errors and success state | QR widget test |
| P0 | Order HTTP DTO serialized BigDecimal money as JSON numbers (lossy in Dart/JavaScript) | Monetary values and quantities are immutable exact decimal strings in buyer order DTO | PostgreSQL HTTP + Flutter contract tests |
| P1 | Unknown public price was displayed as arbitrary backend text | Exact KZT string formatter; no float-derived money | Validation tests |

## Server trust boundaries

- Mobile does not establish buyerId, phone verification, payment status, merchant eligibility or stock.
- Seller confirmation, not buyer acceptance, reserves inventory.
- QR callback creates only a PurchaseLead, not an account/order.
- An acceptance key must be reused on retries. Client-side validation does not replace PostgreSQL invariants or signed JWT authorization.
- Unknown/malformed data is rejected and never replaced with fabricated catalog, quotes or orders.

## Test commands

```bash
cd mobile
flutter pub get
flutter analyze
flutter test --coverage
```

## Remaining release blockers

- Native Android/iOS runners, verified AppAuth redirect schemes, real Keycloak flows and actual device E2E are still absent.
- Automated UX accessibility, localization, screen-reader navigation, text-scale behavior and offline transition tests need further coverage.
- Release flags, S2 security review, media scanner operations, PostgreSQL load qualification and 5000 VU checks remain separate blockers.
- JaCoCo/Flutter LCOV should be monitored against a measured baseline; no unsupported 100% quality claim is made.

Do not merge or delete branches as part of this audit.
