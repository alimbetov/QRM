# SEARCH-S2B / SEARCH-S3 — интеграция RustFS/S3 и фотоленты

**Дата:** 2026-11-14. **Статус:** кандидат реализации в одной рабочей ветке, независимый S2 REVIEW / внешний staging UAT не выполнены. **PR #2 остаётся Draft, main не изменён**.

## Реализовано в коде

**SEARCH-S2B:**
- `S3MediaBlobStore` implements `ListingMediaBlobStore` with AWS SDK for Java v2.55.11; explicit private bucket, region, HTTPS endpoint; path-style for RustFS. AWS credentials are obtained from default credentials chain (not hardcoded). All PUT/GET/DELETE go through strict server-generated keys; no public bucket links or presigned uploads.
- `ClamAvMediaScanner` implements INSTREAM protocol, fail closed on timeout/error/infected input; `DevOnlyMediaScanner` is available exclusively in dev/test/test-pg profiles and must never be used in production. `MediaUploadService` checks listing ownership early, validates DRAFT both before object IO and under the listing lock before DB insert.
- `MediaOrphanReconciler` is opt-in and scans objects older than 48 hours; deletes only references absent from DB; this worker **requires single-leader lease/operational policy before deployment**. Failed object cleanup and crash recovery remain qualified risks.
- Flyway `V23__catalog_media_audit.sql`: append-only media events on upload/revoke, historical V22 backfill as MIGRATED; separate append-only audit of removed orphan objects. Audit captures seller ownership and technical objects, not independently authenticated uploader identity; this requires a later identity audit extension.
- Tests: `S3MediaBlobStoreTest` mocks S3 boundaries, `ClamAvMediaScannerTest` exercises TCP protocol; PostgreSQL schema/HTTP tests validate cover and protected media, and updated image gallery projection. **Mock unit tests do not confirm compatibility with a running RustFS**.

**SEARCH-S3:**
- `GET /api/v1/public/catalog/feed?query=&categoryCode=&productType=&limit=24&cursor=` uses the live indexed SEARCH-S1 read path, PUBLIC+HCC+QR checks, HMAC-signed keyset cursor and a **single batched cover query**; returns one SellerListing per card, `coverUrl`, `imageCount`, `nextCursor` and `hasMore`. No publication private leaks intended; required independent review remains.
- React `LivePhotoFeed` is opted into by `VITE_CATALOG_FEED_ENABLED=true`, with responsive photo grid, text/HCC filters, lazy thumbnail display, IntersectionObserver pagination, cursor append dedup by listingId, explicit «Подробнее» for mobile and Enter, mouse double-click navigation to `/q/{token}`, separate CTA «Запросить предложения» preserving existing market BuyerDemand semantics. There is no direct seller-specific order from the image.
- `QrLanding` optionally shows seller-specific photos with `VITE_CATALOG_MEDIA_ENABLED=true`; if media feature is OFF or unready, the existing text-only public QR page continues working.
- APIs and components have Vitest/PG contract tests, but full Playwright browser E2E and real Flutter visual feed are **not yet proven**.

## Initial proof

- Commit `46f015c043786fbfa99dfcfdaeb5d2f0768f1080`: Java 21 Backend, Buyer Web, Flutter Mobile, Staging Configuration — **all CI PASS**.
- Subsequent commit `f1e9b9ccf747d136e4d520b4ebfd48869bc685c9`: V23 audit, upload preflight, QR gallery and extended tests; **CI proof must be checked on this exact commit before GREEN declaration**.
- Real RustFS and ClamAV staged service E2E, 10+ pages in a real browser, model accuracy, 5k load tests and an independent GitHub APPROVE remain open.

## Proposed isolated staging configuration (NOT automatic rollout)

```properties
zakaz.search.http.enabled=true
zakaz.search.cursor-secret-base64=<secret generated outside git: openssl rand -base64 32>
zakaz.catalog.feed.enabled=true
zakaz.catalog.media.enabled=true
zakaz.media.storage.provider=s3
zakaz.media.s3.endpoint=https://private-rustfs.internal.example
zakaz.media.s3.bucket=zakaz-listing-images-private
zakaz.media.s3.region=us-east-1
zakaz.media.scanner.provider=clamav
zakaz.media.scanner.host=private-clamav.internal.example
zakaz.media.scanner.port=3310
zakaz.media.gc.enabled=false
```

**Illustrative hostnames only.** Real bucket, endpoint and credentials need infra approval; use `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` through a secret manager, never commit them. Non-HTTPS local test requires explicit `allow-local-http` and only localhost; never use production HTTP.

```env
VITE_CATALOG_FEED_ENABLED=true
VITE_CATALOG_MEDIA_ENABLED=true
VITE_API_BASE_URL=https://private-api.example
```

Both Web flags are false by default. `VITE_API_BASE_URL` must point to the same trust domain/API gateway serving catalog/feed, public QR, and public media. No automatic fallback to fake demo catalog on HTTP error.

## Manual acceptance checklist

1. Run existing Flyway V1–V23 on isolated PostgreSQL 17; confirm no checksum alteration of V1–V22.
2. Create private RustFS bucket and restricted IAM policy to one prefix; verify it is NOT anonymous-readable.
3. Start ClamAV behind internal network; upload PNG/JPEG as verified seller to own DRAFT; verify thumbnail/main objects exist, audit UPLOADED, no source EXIF; reject SVG, malformed image, EICAR, inaccessible scanner, oversized/decompression-bomb images.
4. Reject forged seller/listing/upload, reject POST without JWT, reject media on PUBLIC, reject disclosure of DRAFT/UNLISTED media and archive; rotate public listing state and check GET image returns 404/no cache.
5. Check archive/revoke, duplicate cover, media audit append-only, interrupted upload/orphan retention/worker leader lease and object deletion audit.
6. Enable the feed in Web; verify 10+ pages without duplicates, correct query, HCC and 4 locales, photo fallback, double-click/open details and mobile «Подробнее» and that purchase CTA is market demand, not seller checkout.
7. Run SQL EXPLAIN (ANALYZE, BUFFERS), Web Playwright E2E, Flutter device tests, ClamAV/RustFS outage tests and 5k benchmarks separately.
8. Independent S2 reviewer checks exact SHA and raises APPROVE/REQUEST_CHANGES. No production rollout or merge before required gates.

## Blocking gaps before S2B/S3 approval

- A real running RustFS/S3 and ClamAV integration/UAT proof, malware/evasion and decoder fuzz testing; current scanner is synchronous and no async quarantine.
- Safe multi-instance GC leader lease, crash-compensation for DB/object storage mismatch, metrics/alerts, retention, cleanup and monitoring.
- Public image bandwidth/rate limit, cache purge/CDN revocation design, private bucket policy and secret rotation.
- React Playwright Browser E2E, Flutter visual feed parity, accessibility/touch, pagination stability under concurrent publish/hide.
- Real HCC approved dictionary and independent S2; SOURCE HCC candidates CSV must not be imported before approval.

**All media/search/feed flags remain OFF by default in repository.**
