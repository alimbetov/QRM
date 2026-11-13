# SEARCH-S2 — ListingMedia: первый работающий Backend-срез

**Дата:** 2026-11-14. **Статус:** PostgreSQL RED → dev/test GREEN verified, независимый S2 Review **НЕ пройден**, не production-ready.
**Ветка:** docs/adr-002-jpa-architecture-review, PR #2 Draft, main untouched.
**RED:** `dd65b6bdde4121ef8229886e3c51a4c87902de39` — `ListingMediaSchemaPostgresIT` против отсутствующей V22.
**GREEN candidate:** `91a9dbc3d7f0a1b3a5f353f7c09e2cd6623f32a5`.
**GitHub Actions:** run `43492712630`, postgres job `132117801761`: Maven `verify` BUILD SUCCESS; 65 integration tests, 0 failures; media-schema 3 PASS; media HTTP 2 PASS; fast java, Buyer Web, Flutter, Staging Configuration PASS.

## Реализовано и проверено

- Flyway `V22__catalog_listing_media.sql`: metadata only; `listing_id` FK, media object key UNIQUE, SHA256 content hash, JPEG mime, size/pixel constraints, bounded sorting/status, partial unique READY cover; DB trigger serializes with Listing publication row lock and prevents late mutation after PUBLIC.
- `MediaImageSanitizer`: accepts only signature-checked JPEG/PNG, ≤5 MB and ≤16MP/8192px; decode and reencode to fresh JPEG RGB; outputs full and 480px thumbnail, without copying source EXIF/GPS. ImageIO fuzz/antivirus remains open.
- `MediaUploadService`: generates server-only object key and UUID; inserts metadata only after storing sanitized bytes; same SellerListing lock as publication. Compensates synchronous DB failures by deleting blobs; crash-orphan repair job is NOT present.
- `ListingMediaController`: authenticated merchant upload `POST /api/v1/sellers/{sellerId}/listings/{listingId}/media`; public `GET /api/v1/public/catalog/{listingId}/media`, `GET /api/v1/public/media/{mediaId}?size=thumb|full`; no direct object keys exposed. Exact seller JWT `SCOPE_catalog:write` + trusted DB SellerMembership + listing ownership.
- `PublicMediaReadService`: reads only `media.READY`, `listing.PUBLIC`, `category.active`, trusted `qr_public_link`. Existing PUBLIC items with zero photos still have empty galleries; UNLISTED/ARCHIVED inaccessible.
- `ListingMediaBlobStore` interface + `LocalDevMediaBlobStore`: **only profiles dev/test/test-pg** with explicit provider=filesystem and explicit storage root. Not suitable for multi-instance production, staging or deployment as an image CDN.
- `ApiSecurityConfig`: exact public GET allowlist and private seller POST scope. Upload API/default config gated OFF: `zakaz.catalog.media.enabled=false`.

## What is NOT implemented

- AWS S3/RustFS/MinIO-compatible storage adapter, signed/safe object access, multi-node deployment; production/staging media must remain OFF until validated.
- Asynchronous malware scanner, decoder fuzzing/zip-bomb safety proofs, presigned multipart direct upload, retries/reconciliation/crash-orphan garbage collection, public image cache invalidation and detailed moderation/revocation audit.
- Seller UI for uploading media, React/Flutter display, responsive grid, infinite feed and double-click navigation: SEARCH-S3 still outstanding.
- Enforcing 3–5 images for NEW public listings. Legacy no-image publication remains permitted; policy needs product-owner decision.
- Independent reviewer APPROVE and live browser/device UAT.

## API example for **isolated dev/test only**

```properties
zakaz.catalog.media.enabled=true
zakaz.media.storage.provider=filesystem
zakaz.media.storage.root=/absolute/path/to/private/media-objects
```
Requires verified JWT with `catalog:write` and active SellerMembership. To upload use multipart field `image`; optional `cover=true`. No anonymous upload. In non-dev profiles there is no filesystem provider: enabling the feature without a reviewed provider should fail startup rather than silently store files in an unsafe local directory.

## Next gates

1. Independent S2 Review SEARCH-S1/S2: exact SHA, SQL constraints, idempotency, authorization, image sanitizer fuzz, transactional filesystem writes, orphan handling, cache visibility.
2. Approved HCC dictionary seeds: [candidates](SEARCH-S1-DICTIONARY-CANDIDATES.csv), [operator import](SEARCH-S1-DICTIONARY-IMPORT-PLAN.md); the candidate CSV has blank HCC codes and is **not runnable as production data**.
3. S2B: production-grade S3-compatible adapter + object lifecycle, TTL, public proxy/CDN policy + full security tests.
4. S3: React/Flutter photo catalog, cursor page, one Listing per card, open /q/{token} on double-click with single-tap fallback.
5. Real staged SMTP/OIDC UAT and independent 5000-VU load/order qualification remain separate release gates.

**No merge to main or claim of S2 APPROVE.**
