# QRM — Security, Privacy, Deployment & Operations Specification

**Статус:** baseline security requirements, требует legal/security review до публичного запуска.

## 1. Trust boundaries

Guests: read PUBLIC/UNLISTED only; submit rate-limited callback leads, collect only required phone/callbackConsent. Buyers: own favorites, offers, orders, cancellations and reviews. Sellers: only own profile/listings/leads/orders/analytics; access to phone/address only for authorized transaction purpose. Administrators: scoped role with audit, not unrestricted default. Backend enforces ownership regardless of UI.

Implement reactive authorization filters and service-level policies; unauthorized details use 403/404 consistent with non-disclosure. Anti-IDOR for publicId, qrToken, lead, offer, inbox subject, seller media, device challenge, review.

## 2. Identity, Web QR sessions

- Phone OTP for account login with per-number/device/IP throttles, attempt limits and expiry. Web callback leads do **not** require mandatory OTP under DEC-01; separate fraud control.
- QR challenge: cryptographic random opaque value, single-use, TTL initially 90 seconds, bound to initiating browser secret, origin verification, explicit Flutter confirmation, replay/race rejection, no JWT in QR.
- Store Web session server-side or securely enforce revocation. HttpOnly Secure SameSite cookies, CSRF token on state-changing requests, session rotation on login, idle/absolute timeout, logout/all-devices revocation.
- Audit login attempts, approvals, denials, revocations, suspicious patterns; minimize captured device fingerprint.

## 3. Media and external links

- Allowlist MIME and inspect actual content; max image size and count; image re-encode, EXIF stripping, malware scanning when supported; private upload bucket until validation, signed upload URLs with limited scope and expiry.
- YouTube allowlisted hosts/video ID only; never server-fetch arbitrary user-provided URLs (SSRF). iframe via restricted CSP and privacy-aware click-to-load.
- QR redirects are resolved from stored listing/profile targets, never arbitrary open redirects. Public pages add safe metadata and bot index visibility rules.

## 4. Business integrity

- No client-provided amount trusted; BigDecimal and transactional price quotes.
- On simultaneous accept/reject/withdraw, use versioned conditional updates or row locks plus TransactionalOperator.
- Idempotency for create order/convert negotiation/cancel, Outbox event ID dedup, callback retries with backoff.
- Distinguish agreement, order and payment; no self-attested payment guarantees.
- Persistent audit of transitions, reasons, actor and revisions without excessive PII.

## 5. Data protection & Kazakhstan launch

Phone, address, review photos and complaint evidence are personal data. Protect at rest and in transit, restrict role use, support informed consent, retention/deletion workflows, breach-response procedures and data export where applicable. **Before production**, locally qualified legal review must confirm applicable Kazakhstan rules on personal data (including localization and cross-border transfer), e-commerce disclosures, consumer rights, direct phone contact consent and platform operator liabilities. No assumption that «direct payment» exempts QRM from duties.

Proposed retention until approved:
- QR analytics detailed event: 30–90 days, aggregates longer with anonymization;
- Unconverted Web lead contact: defined TTL e.g. 90 days, then purge/anonymize;
- Order/audit and claims: statutory period after legal validation;
- Push device tokens: delete after logout/revocation/invalid FCM feedback.
These are **proposals**, not legal conclusions.

## 6. R2DBC runtime

- Spring WebFlux + Spring Data R2DBC, PostgreSQL reactive driver, Reactor, reactive Spring Security.
- Liquibase via JDBC startup/migration process **outside reactive request path**; migration lock and readiness gating.
- Reactive transactions via `TransactionalOperator` and unit/integration tests for rollback and outbox atomicity.
- Nonreactive dependencies (image processing, S3/FCM SDK, external translation) need async-compatible clients or bounded dedicated scheduler/worker with queues; prohibit blocking on event-loop.
- R2DBC pool limits align with PostgreSQL max connections; apply timeouts, cancellations and retry policies only to safe idempotent operations.
- Do not assume R2DBC automatically improves response time; verify with load profile.

## 7. Deployment & observability

Initial deployment: one backend app (replicas after proven need), one Postgres, S3 API, React static hosting, Flutter Android/iOS build/release and async worker **module** (can be same deployable with leader-safe jobs). Environment configs externalized; separate test/prod and secrets; migrations in controlled job. Docker Compose for local/dev; Kubernetes optional after measurement.

Telemetry: structured JSON logs (traceId/correlationId, no raw phone/address), HTTP latency/error metrics, DB pool, outbox lag, push failures, price scheduler delay, lead abuse/rate limits, QR request anomalies. Readiness checks Postgres and critical configuration; noncritical notification/analytics outages should not take public storefront down.

Backup/restore: scheduled DB PITR-compatible strategy and tested restore; media consistency checks; RPO/RTO to be approved. Rollbacks ensure schema compatibility and no destructive migration before qualified backward compatibility.

## 8. Performance and reliability proposals

Define workload before accepting targets: number of active listings, p95 reads, concurrent writes, QR scanning burst, daily events, maximum backlog. Initial hypotheses: p95 listing API ≤300ms, p95 order write ≤800ms under documented load and excluding external provider tails; 99.5% monthly availability target. Verify with load and recovery drills, not inferred from framework.

Graceful degradation: public listing works when analytics/FCM unavailable; order write atomic with outbox; stale effective price avoided via server quote check; scheduled discount recovers activation after restart.
