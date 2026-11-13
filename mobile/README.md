# ZAKAZ Flutter R2.3C (Android/iOS)

Minimal **source-level** mobile client of the real Spring MVC/JPA API:
- Published PostgreSQL listings and QR-linked detail pages.
- Guest opt-in callback -> encrypted server PurchaseLead, **not an order**.
- Authenticated buyer OIDC Authorization Code + PKCE (AppAuth).
- BuyerDemand -> read seller bids -> explicit acceptance -> read CustomerOrder. Reservation occurs only on seller confirmation.

**Not yet a built APK/IPA, deployed mobile UAT or S2 approval.** The repository intentionally does not carry generated Android/iOS runner files; create them using the installed Flutter SDK before device testing:

```bash
cd mobile
flutter create --org kz.zakaz --project-name zakaz_mobile --platforms=android,ios .
flutter pub get
flutter analyze
flutter test
```

## Private staging configuration

Register two separate PUBLIC OAuth clients with your trusted identity provider: one browser SPA and one mobile public client. Backend JWT issuer/audience and buyer_account_link provisioning must pass S2. Mobile redirect scheme must be registered with provider and native runner, e.g. `kz.zakaz.mobile:/oauthredirect` (**example only**, not a real configured application).

```bash
flutter run \
  --dart-define=ZAKAZ_API_BASE_URL=https://your-reviewed-staging-domain.example \
  --dart-define=ZAKAZ_OIDC_ISSUER=https://your-real-issuer.example \
  --dart-define=ZAKAZ_OIDC_CLIENT_ID=your-registered-public-client-id \
  --dart-define=ZAKAZ_OIDC_REDIRECT_URI=kz.zakaz.mobile:/oauthredirect
```

Android: configure AppAuth manifest placeholder `appAuthRedirectScheme` with `kz.zakaz.mobile`, verify internet permission and Android App Links/deep link callback; iOS: add matching URL scheme in Info.plist. Do NOT request a client secret or embed signing keys in mobile binaries. For emulator-only HTTP, API allows localhost/127.0.0.1/10.0.2.2, but Android cleartext policy must be configured only in a private DEBUG environment; production is HTTPS. Real hosts must use HTTPS. Tokens are kept only in memory until exit; no refresh-token storage or background login yet.

For running UI, backend flags `zakaz.catalog.http.enabled=true`, `zakaz.qr.http.enabled=true`, `zakaz.commerce.http.enabled=true` are required on isolated test environment. The published catalog can have zero items; no demo fallback. Seller bid creation/confirmation is currently via authenticated backend API, **not yet a seller mobile interface**. The phone callback never creates an account, SMS identity or purchase.

Security/release blockers: independent S2 (#3), real OIDC/JWKS and provisioning, Android/iOS deep-link verification, privacy and rate-limit proxy configuration, real browser/device E2E and 5000-user performance qualification. Do not merge PR #2 to main on Flutter scaffolding alone.

### R2.3E verified buyer profile onboarding

After AppAuth PKCE, Flutter reads `GET /api/v1/buyers/me`; a genuinely unregistered (404) buyer is offered nickname, E.164 unverified phone and explicit consent, then submits `POST /api/v1/buyers/enroll`. A 403 or other failure does not silently create an account. Checkout requires the resulting server-created buyer profile. Device tests and the native AppAuth redirect configuration are still outstanding; no APK/IPA acceptance is implied.


### R2.3F — Keycloak AppAuth logout and real-device UAT

Flutter now retains the OIDC ID token **in memory only** for RP-initiated logout through `FlutterAppAuth.endSession()`. Set `--dart-define=ZAKAZ_OIDC_LOGOUT_REDIRECT_URI=kz.zakaz.mobile:/logout` with a Keycloak `zakaz-mobile` public client's **exact** post-logout redirect. Android `appAuthRedirectScheme` and iOS URL schemes must handle both `kz.zakaz.mobile:/oauthredirect` and `kz.zakaz.mobile:/logout` through the AppAuth plugin. Clearing the local buyer session occurs only after successful identity-provider logout. User sign-up must complete real Keycloak email verification before buyer enrollment; no SMS is required for basic sign-up. No physical-device UAT or APK release has been run. See [R2.3F](../docs/R2-3F-KEYCLOAK-SMTP-SESSION-E2E.md).
