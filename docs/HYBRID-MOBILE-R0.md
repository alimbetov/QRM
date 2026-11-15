# ZAKAZ Hybrid Mobile R0 — public React WebView + native Flutter commerce

**Status:** implementation candidate; independent S2, Android/iOS on-device tests, physical QR scanning, App Store/Play review and production rollout **NOT** completed.

## Scope

Reuse the existing responsive React buyer catalog/photo feed and public QR landing in a tightly restricted Flutter WebView. The React `/buy?productId=UUID&unit=PCS` link is intercepted at the Flutter navigation boundary and opens the existing **native** `BuyerScreen` (Flutter AppAuth, OIDC PKCE, buyer enrollment, BuyerDemand, bid acceptance, order read). No React login or Keycloak screen runs inside the WebView.

Changes:
- `mobile/lib/hybrid_route_policy.dart`: trusted exact-origin navigation policy, explicit allowlist of public paths, UUID and sales-unit validation.
- `mobile/lib/hybrid_catalog_screen.dart`: embedded `webview_flutter` public catalog/QR, native buyer handoff, progress/error/retry, native catalog fallback.
- `mobile/lib/main.dart`: opt-in hybrid mode; default native behavior is unchanged.
- `mobile/test/hybrid_route_policy_test.dart`: deterministic allow/block routing tests.
- `mobile/pubspec.yaml`: `webview_flutter` 4.14.1 compatible with Flutter 3.47.7 (CI).
- `mobile/README.md`: configuration, invariants, E2E gaps.

No JS channel, no bearer token injection, no cookies copied between app and WebView. The built-in web catalog remains **public/guest-only**; `/auth/*`, other authenticated React routes, external links and unknown routes are rejected. Any future seller React Studio requires a separate approved session architecture, not this public shell.

## Configuration

With the real React frontend on a reviewed HTTPS site:

```bash
cd mobile
flutter pub get
flutter analyze
flutter test
flutter run \
  --dart-define=ZAKAZ_API_BASE_URL=https://api.your-staging.example \
  --dart-define=ZAKAZ_HYBRID_WEBVIEW_ENABLED=true \
  --dart-define=ZAKAZ_WEB_BASE_URL=https://shop.your-staging.example \
  --dart-define=ZAKAZ_OIDC_ISSUER=https://keycloak.your-staging.example/realms/zakaz \
  --dart-define=ZAKAZ_OIDC_CLIENT_ID=zakaz-mobile \
  --dart-define=ZAKAZ_OIDC_REDIRECT_URI=kz.zakaz.mobile:/oauthredirect \
  --dart-define=ZAKAZ_OIDC_LOGOUT_REDIRECT_URI=kz.zakaz.mobile:/logout
```

`ZAKAZ_WEB_BASE_URL` must point to an actual **React front-end origin** (not the API-only service). The same React build must expose `/` and `/q/{token}`, and the frontend's own API base must be correctly configured. Enabling `VITE_CATALOG_FEED_ENABLED=true` and `VITE_CATALOG_MEDIA_ENABLED=true` is a separate Web/staging decision; these flags are OFF by default. HTTPS is mandatory outside localhost/127.0.0.1/10.0.2.2 for emulator debugging.

By default, or if hybrid mode is not enabled, ZAKAZ continues to launch the existing Flutter native catalog. If enabled with an invalid/missing Web origin, the app shows an explicit configuration failure instead of navigating to an untrusted address.

## Security and UX acceptance matrix

| ID | Observable acceptance |
|---|---|
| HYB-01 | Trusted HTTPS React root and /q/{43-char-token} load in WebView; React responsive CSS works on narrow viewport |
| HYB-02 | React `/buy?productId=UUID&unit=PCS` does NOT render inside WebView; Flutter BuyerScreen opens |
| HYB-03 | OIDC only in native AppAuth external system browser; JWT never placed in WebView URL/JS bridge/cookies by Flutter |
| HYB-04 | Cross-origin navigation, auth routes, non-HTTPS, URI userinfo, forged IDs, unsupported units and duplicate query keys blocked |
| HYB-05 | Browser back, refresh, failed page retry and native catalog fallback function without fake data |
| HYB-06 | Guest QR callback remains an opt-in PurchaseLead, not authenticated order |
| HYB-07 | Native checkout remains server-authorized with unchanged owner/stock/order invariants |
| HYB-08 | Flutter analyze, unit/widget regression, CI, external Keycloak real redirect, physical Android + iOS navigation UAT pass before release |

## Boundaries and next slice

This slice does **not** implement a native QR camera scanner, push, secure authenticated WebView session sharing, full Seller Studio, offline mode, completed fulfillment, APK/IPA distribution or production review. Flutter runners are not committed to this repository. Seller React Studio may later reuse responsive HTML, but accessing authenticated pages requires a separately reviewed session design. Physical device WebView/PKCE redirect and real rendered-page E2E are mandatory before enabling the flag in production.
