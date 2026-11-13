# ZAKAZ R2.3F — Keycloak SMTP, real registration, logout/relogin and device qualification

**Status:** configuration and application code implemented in Draft PR #2. This is NOT evidence of external SMTP delivery, real mailbox verification, browser/device E2E, S2 APPROVE or production access. No new development branches; main untouched.

## Deliverables

- **Operator-only Keycloak SMTP configuration:** `deploy/staging/keycloak_smtp_ops.py` and `smtp-ops.sh`. Admin API is accessed on the isolated `zakaz-staging_private` Docker network. Uses either a private short-lived master-admin token (`STAGING_ADMIN_TOKEN_FILE=/evidence/admin.token`) or restricted staging `admin-cli` operator credentials. Admin login is distinct from consumer OAuth; buyer/seller password grants stay disabled. No SMTP password/token is printed, checked into Git or exposed through the public Caddy proxy.
- **Fail-closed rollout:** `configure` forces `verifyEmail=true`, `registrationAllowed=false`, TLS SMTP configuration and a **real Keycloak testSMTPConnection**; only a successful result creates an ignored, short-lived `evidence/smtp-test-proof.json`. After an operator **personally confirms receipt of a test email** and approves privacy/abuse controls, `enable` additionally requires `STAGING_ALLOW_SELF_REGISTRATION=YES_AFTER_REAL_MAILBOX_PROOF`, matching settings/proof and re-reads the realm. `disable` closes signup without affecting existing users. The proof only demonstrates the test endpoint reported success; delivered-email UAT is a separate human check.
- **Keycloak realm:** S256 PKCE public clients, `email` scope, verified-email requirement, exact `https://<domain>/auth/callback` and `/auth/signed-out` Web redirects, mobile `kz.zakaz.mobile:/oauthredirect` and `kz.zakaz.mobile:/logout`. No wildcard redirect origins.
- **React:** `Create account` uses Keycloak's OIDC `prompt=create`, not internal login page URLs. `Login` and `Logout` use oidc-client-ts authorization code and RP-initiated end-session redirect; `/auth/signed-out` validates logout callback before clearing the locally cached session. Expired access tokens stop client ordering until login. A failed IdP logout does not claim logout succeeded.
- **Backend:** staging sets `zakaz.identity.require-verified-email=true`, preventing buyer order/demand operations for a still-signed JWT with `email_verified` absent/false. Existing `buyer_account_link.active=false` is checked on every sensitive business operation and blocks an otherwise valid signed JWT immediately. Unverified phone **never** establishes ownership/recovery.
- **Flutter:** AppAuth ID and access tokens held only in memory; logout uses OIDC `EndSessionRequest` with an `idTokenHint` and registered native callback. UI local state is cleared only after success; Android/iOS deep link runner configuration remains device qualification work.
- **Checks:** Java PostgreSQL tests (deactivated account, valid JWT, concurrent registration), Flutter and React unit/build, static Keycloak realm/SMTP TLS unit tests, staging Keycloak import/JWKS. Playwright includes logout→relogin→existing order and optional registration-front-door. **No actual external Playwright/device/SMTP run yet.**

## Operator procedure on a dedicated staging host

1. Provision a real `STAGING_DOMAIN` with HTTPS (Caddy ACME) and an isolated Docker host. Use only generated synthetic test accounts and warehouse data.
2. Append the **field names** from `deploy/staging/private-smtp.env.example` to the existing ignored `deploy/staging/private.env`; provide real SMTP hostname, sender, username, password and TLS port. Never commit the real `private.env`. Keycloak 26.8.2 enforces successful STARTTLS when configured; the script rejects plaintext or mismatched ports.
3. Configure SMTP via `bash deploy/staging/smtp-ops.sh configure`. This can fail if the provider blocks SMTP or the staging Keycloak admin user lacks a test-mailbox address. **Registration remains disabled on failure.** Check the recipient test mailbox; do not accept only the script exit status as proof of delivered email.
4. After proving delivery and approving registration policies, set `STAGING_ALLOW_SELF_REGISTRATION=YES_AFTER_REAL_MAILBOX_PROOF` in the private env and run `bash deploy/staging/smtp-ops.sh enable`. Recheck with `bash deploy/staging/smtp-ops.sh status`. You can close it using `disable`.
5. In React `/buy?productId=<published-product-id>&unit=PCS`, select **Создать аккаунт через Keycloak**, submit Keycloak's real registration form, receive the real email, click its verification link, return through the OIDC PKCE callback, supply nickname/contact/consent and verify `GET /api/v1/buyers/me`. Phone remains explicitly unverified.
6. On a **fresh synthetic fixture**, sign in as the seller with its separate PKCE client, publish the SKU, submit an all-in SellerBid, accept it through React, confirm reserve through seller-authenticated API, and verify one order/reservation/Outbox record via `deploy/staging/check-order.sql`. Seller UI does not exist yet; do not claim a merchant UI walkthrough.
7. Click **Выйти через Keycloak** on Web and verify the signed-out callback; sign in again and confirm the same buyer sees the same order. Confirm a deactivated `buyer_account_link` is rejected with the old signed token, and a different buyer gets 403; this is **business-level authorization revocation**, not immediate JWT cryptographic invalidation.
8. Regenerate Flutter Android/iOS runner files if absent and configure native URI schemes (AppAuth redirects `kz.zakaz.mobile:/oauthredirect` and `kz.zakaz.mobile:/logout`). Run the same buyer flow on a physical device; verify email on real mailbox, login, registration, demand, acceptance, order read, logout/relogin and expiry. Record device/OS, server SHA and redacted evidence.

## Automated commands and evidence

```bash
python3 -m unittest discover -s deploy/staging -p 'test_keycloak_*.py'
docker compose --env-file deploy/staging/private.env -f deploy/staging/compose.yaml up --build -d
bash deploy/staging/smtp-ops.sh configure
# Only after test-mailbox delivery confirmation and secure signup policy:
bash deploy/staging/smtp-ops.sh enable
STAGING_DOMAIN=<staging-host> python3 deploy/staging/preflight.py
# Run with REAL private buyer/seller tokens and synthetic test fixture:
node deploy/staging/e2e-real-oidc.mjs
# From web/ with dedicated test identities and real staging URL:
npm run e2e:staging
```

Playwright's separate registration-front-door test is opt-in (`STAGING_SIGNUP_ENABLED=true`) and only asserts Keycloak's real registration form opens with `prompt=create`; **it does not fake mailbox receipt**. Normal GitHub Actions validates compilation/configuration only. Tests requiring external services or physical devices must be executed by an operator and archived as redacted evidence before S2.

## Security caveats and release gate

- RP-initiated logout terminates the browser's IdP session but does **not retroactively invalidate an already minted self-contained JWT** at the API; its configured 15-minute lifetime still applies. Immediate in-app revocation requires disabling server-managed buyer/seller links (or a separately approved token-revocation/introspection architecture). Do not promise global 0-second JWT revocation.
- Keycloak SMTP credentials are sensitive, Keycloak configuration and database backups need access control and rotation. The staging operator procedure must migrate to a secret manager / service account and managed TLS before production.
- Registration must remain closed until deliverability, anti-abuse throttling, privacy notice/consent retention, guest phone policy, helpdesk recovery and email-verified token claims are independently reviewed. SMS is **not** required for every login; any phone-based recovery needs its own possession proof.
- Production remains blocked by external staging E2E, physical Flutter UAT, independently approved S2 on exact SHA and 5000-VU load tests.

Official references: [Keycloak Admin SMTP and email verification](https://www.keycloak.org/docs/26.8.0/server_admin/), [Keycloak 26.8.2 STARTTLS](https://www.keycloak.org/docs/26.8.2/upgrading/), [Keycloak registration via prompt=create](https://www.keycloak.org/docs/latest/server_admin/), [Flutter AppAuth EndSessionRequest](https://pub.dev/documentation/flutter_appauth/12.1.0/flutter_appauth/EndSessionRequest-class.html).
