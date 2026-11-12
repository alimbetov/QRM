# S2 Review Package — Seller Authentication, Managed Migrations, R2 Gate

**Baseline date:** 2026-11-12. **Review status:** READY FOR INDEPENDENT REVIEW / NOT APPROVED. This is a developer-prepared review package and self-audit, **not independent sign-off**. Target Draft PR [#2](https://github.com/alimbetov/QRM/pull/2). Any code change after the reviewed SHA requires a renewed exact-SHA qualification.

## Scope

1. OAuth2 JWT resource-server verification and issuer+audience configuration.
2. `seller_membership` server-managed authorization model; require issuer, subject, seller ID, active role.
3. Only POST `/api/v1/sellers/{sellerId}/listings/{listingId}/publish` is exposed; JWT scope `catalog:write` required, and every ownership decision resolves server-side membership and listing ownership. No endpoint accepts seller identity as a trusted body field.
4. Fail closed if `JwtDecoder` is unconfigured. OIDC provider / keys and membership provisioning are external integrations.
5. PostgreSQL Flyway V1–V4 (entire initial schema), schema `validate`, `baseline-on-migrate=false`, `clean-disabled=true`. Fast H2-only tests explicitly keep Flyway disabled and `create-drop` (never production).
6. Preserve older R1B.2 database draft-only published SKU guards and required enum vocabulary.
7. R2 scope/acceptance: demand→bid→order→seller confirmation→atomic reservation. No ML in purchase path.

## Code-quality observations (developer self-audit, NOT sign-off)

| Check | Evidence | Status |
|---|---|---|
| SEC-01 Missing bearer token rejected | `SellerPublishingSecurityIT` | Implemented/CI |
| SEC-02 JWT scope without active membership rejected | `SellerPublishingSecurityIT` | Implemented/CI |
| SEC-03 Issuer + subject + seller ID + role matched by repository | `SellerIdentityService`, `SellerMembershipRepository`, policy tests | Implemented/CI |
| SEC-04 Client path sellerId cannot grant access to another merchant | `SellerPublishingSecurityIT` | Implemented/CI |
| SEC-05 Viewer with valid JWT cannot publish | `SellerPublishingSecurityIT` | Implemented/CI |
| SEC-06 Real cryptographic issuer/JWKS, aud, expiry/key rotation tested end-to-end | No real IdP configured; JWT decoder is mocked in HTTP integration tests | **BLOCKED** |
| SEC-07 Provisioning/revocation of trusted seller memberships | No approved backoffice or seller onboarding flow | **BLOCKED** |
| SEC-08 Race: privilege revoked concurrently with request | Needs decision and real concurrent PostgreSQL tests | Open |
| MIG-01 Fresh PostgreSQL applies V1–V4 and JPA `validate` | `ManagedSchemaPostgresIT` | Implemented/CI |
| MIG-02 Existing unmanaged schema not silently auto-baselined | `ExistingSchemaFlywayGuardIT` | Implemented/CI |
| MIG-03 Existing installation migration/backfill / data compatibility | Requires environment-specific schema diff, immutable backup, migration dry run | **BLOCKED** |
| MIG-04 Roll-forward/rollback and migration ownership on release | Needs operational playbook, DB backup and controlled migration credentials | Open |
| CAT-01 Published SKU facts immutable via DB trigger | `CatalogPostgresPublishedGuardIT` | Implemented/CI |
| CAT-02 Catalog reference enum/HCC revision retains historical meaning | Dictionary versioned snapshots and approved migrations not yet implemented | Open before ML |
| R2-01 Concurrent reserve and outbox atomicity | Design/test specifications only | **NOT IMPLEMENTED** |

## Threat model checks for independent reviewer

- Identity: JWT decoded and validated by Spring Security before controller, issuer and audience match configured trusted values, subject maps to a server provisioned membership. Never authorize based on unsigned client `seller_id`, `role`, arbitrary client-provided scopes, or unverified phone alias.
- Tenant boundary: guessed listing/SKU UUID and forged URL `sellerId` must not affect another seller.
- Membership: active, correct issuer, correct subject, correct seller and catalog-edit/admin role; revocation and multi-seller membership behaviour; least privilege of membership provisioning account.
- Catalog integrity: publication and draft modifications serialize with `PESSIMISTIC_WRITE`; direct SQL trigger blocks published row changes; reference data and seller status still require governance.
- Database: migrated schema matches Hibernate, catalog FK constraints, Flyway checksum drift fails, existing DB does not auto-baseline; no `ddl-auto=update` in PostgreSQL profiles.
- API: stateless, bearer-only, no H2/dev login shortcut, unauthorized 401, forbidden 403, incomplete listing 422, no sensitive error leaks.
- Order flow: no claim of full inventory protection, no new public order endpoint until R2 authorization and transactional reservations are accepted.

## Required S2 reviewer verdict

An independent reviewer must record (a) reviewed commit SHA, (b) code/DDL/security threat findings by severity, (c) independent PostgreSQL/Testcontainers and auth-test evidence, (d) P0 closure or explicit rejected risk with owner, (e) **APPROVE / REQUEST CHANGES / BLOCKED**. A passing CI alone is not an independent architectural or security sign-off.

**Current verdict: BLOCKED FOR PRODUCTION / REVIEW PENDING.** Work on R2 Scenario Design can proceed independently; implement R2 transactional code only after its own RED tests and these P0 safeguards are closed.
