# R2.3E — Keycloak-backed buyer onboarding, privacy and auth gate

**Status:** code implemented in Draft PR #2 on the ONE development branch.
**NOT released:** real external HTTPS OIDC self-registration, SMTP delivery, staging browser/device UAT, independent S2 Review and 5000 VU remain OPEN.

## 1. Trust model

1. Keycloak is the OIDC issuer and manages passwords, session/refresh tokens and email proof. The buyer SPA and Flutter are public clients using Authorization Code + PKCE (S256), not passwords or client secrets.
2. Spring Security Resource Server cryptographically verifies JWT signature, expiration, issuer, audience (`zakaz-api`) and required `deal:write` scope. A valid token identifies `iss + sub`, never a buyerId supplied by HTTP.
3. The server exposes two **feature-gated** routes, both requiring `SCOPE_deal:write`:
   - `GET /api/v1/buyers/me`: returns only the signed caller's existing profile; 404 when no complete profile exists; 403 when `email_verified` is not the Boolean `true`.
   - `POST /api/v1/buyers/enroll`: registers `{nickname,phone,consentToStorePhone}` only if Keycloak says `email_verified=true` and provides email, issuer and subject. Newly linked internal buyerId is generated on the server. 201 created, 200 exact replay.
4. `BuyerAccountLink` remains authoritative for checkout. Enrollment is serialized through PostgreSQL transaction-scoped advisory locking on the canonical `iss+sub` and backed by V7 UNIQUE constraint. The profile INSERT and identity link are committed atomically. An inactive existing link is forbidden, never resurrected; existing trusted buyer links may add a missing profile without changing buyerId.
5. `buyer_profile` introduced in Flyway **V19** stores nickname, AES-256-GCM encrypted E.164 phone with an independent application key, the last four digits for owner display, `phone_verified=false` and the consent timestamp. No email/password/secret are duplicated into the business DB; owner email comes from the verified JWT. An unverified phone has **no uniqueness guarantee and must not be used for SMS recovery, authentication, ownership or account merge.**
6. Buyer profiles are separate from seller roles. Merchant permissions are still granted/revoked only through approved trusted administration of `seller_membership`; no self-enrollment route permits self-granting `SELLER_ADMIN`.

## 2. Feature flags and secrets

`zakaz.buyer.enrollment.enabled` defaults OFF. Isolated staging sets
`ZAKAZ_BUYER_ENROLLMENT_ENABLED=true` with the dedicated
`ZAKAZ_IDENTITY_PHONE_KEY_BASE64` (generate `openssl rand -base64 32`; **different** from the guest lead encryption key).

If the key is absent, registration fails with HTTP 503. No hardcoded key may be shipped in a real environment. Profile phone is never logged or included in the response, only `••••NNNN`.

**Encryption lifecycle P0 issue:** ciphertext v1 is bound by AES-GCM AAD to the OIDC issuer+subject. Before production, implement audited privileged decrypt/use only where absolutely needed, key-version rotation and re-encryption/retention/deletion procedures. This first slice is intentionally *write-only contact storage* (not an operational telephone contact workflow).

## 3. Keycloak email verification and registration

The staging realm has `verifyEmail=true` but **`registrationAllowed=false` by default**. This is deliberate. Without a reviewed SMTP sender/credentials, public self-registration would create unverifiable accounts and invite identity-abuse.

To activate **end-user sign-up** after provisioning the real SMTP provider:
- Configure SMTP securely in Keycloak realm settings; verify deliverability to a controlled test mailbox, TLS and From identity.
- Enable self-registration only on the isolated staging realm after abuse/rate-limiting and privacy notice review. Set email verification as a **required** step and confirm it is enforced in actual token claims.
- Create two separate users for the UAT. Verify actual mailbox ownership for the buyer; do not simply flip `emailVerified=true` to bypass confirmation.
- Complete `/buyers/enroll` from React or Flutter and test the entire BuyerDemand→SellerBid→CustomerOrder→reserve flow.
- Do **not** use a password grant or add an SMS requirement to normal registration. Phone verification/recovery is a separate later, expressly gated workflow.

**Staging CI passes realm import and JWKS checks only.** It does not demonstrate real SMTP, account activation, browser login, mobile AppAuth or IdP logout.

## 4. Tests

- `BuyerEnrollmentPostgresIT`: first enrollment; encrypted phone and `phone_verified=false`; exact retries; no duplicate profile; verified-email enforcement; scope/anonymous rejection; invalid phone/absent consent; foreign user blocked; disabled identity cannot re-register; trusted link can be completed without changing buyerId.
- React `enrollment-api.test.ts`: only 404 means no profile, bearer required, no client-defined buyerId or email_verified, 403 is not mistaken for an available signup.
- Flutter `zakaz_api_test.dart`: required bearer, owner-profile read and enrollment, 404 vs 403.
- `deploy/staging/e2e-real-oidc.mjs` and Playwright now include buyer onboarding **before** the demand flow; execute with a real OIDC issuer after deployment. These scripts are not a PASS from a remote staging run.

## 5. Production/S2 blockers (stop-ship)

- Real SMTP/verified user sign-up + duplicate email and session recovery UX; Keycloak lifecycle revocation and safe logout/refresh semantics.
- Legally reviewed privacy notice, consent-policy version, retention/deletion handling and phone verification **only for recovery**, if needed.
- Secret manager and encryption key rotation, loss recovery, SQL backups and PII access audit.
- Browser and physical Flutter device OIDC code/PKCE redirect, key rollover, invalid/expired signature, issuer and audience, MFA and brute-force policy.
- Independent reviewer on exact commit SHA and staged regression/E2E; PR remains Draft and `main` unchanged.
- Seller Studio is still missing; the staging seller uses an authenticated CLI/API. Tribuo training and predictive ranking are not touched by this identity milestone.
