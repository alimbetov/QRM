# SEARCH-001 — контракт реализации (S0 handoff для RED-тестов)

**Статус:** PROPOSED / реализации нет. **Основа:** технический аудит [SEARCH-AUD-001](SEARCH-AUD-001-TECHNICAL-REVIEW.md); `docs/SEARCH-001-UNIFIED-DISCOVERY.md`; master-TZ v2.0, ADR-002, ADR-004, DEC-27, DEC-BUYER-WEB-001. Любая миграция/endpoint — отдельный RED→S2→GREEN. Дата: 2026-11-13.

## 1. Рекомендуемые решения (на подтверждение владельцем на S0)

- Существующие JPA Aggregate не меняются для поиска. PostgreSQL 17 + JDBC `NamedParameterJdbcTemplate` read projection допустим на основании уже реализованного PublicCatalogReadService; JPA для транзакционных сущностей Favorite/ListingMedia. Elasticsearch, Redis и новый микросервис не требуются.
- Данные: Catalog единственный source of truth; Projection/index не раскрывает private записи; в P0 обновление SearchDocument — в одной БД-транзакции при изменении/публикации + backfill/reconciliation; General Outbox только при появлении потребителя, не встраивать события в `order_outbox_event`.
- P0 группы сохранённого — вычисляемый HCC parent/child, не новая папка и не дублирующая категория. P1 CategoryWatch только по явному opt-in.
- Основной checkout в первой фазе **рыночный BuyerDemand**, который не гарантирует продавца исходного SellerListing. Публичная карточка может сохранять referral source для аналитики, но buyerId/sellerId/productId/skuId не доверять параметрам клиента. Seller-specific purchase — отдельный согласуемый контракт.
- `/q/{token}` остаётся точкой входа в SellerListing для P0, без разрушения существующего QR; no auto order on double click. Touch: «Подробнее» через single tap/Enter — обязательный fallback.

## 2. Миграционный план (не исполняемый SQL)

**Предложенные последовательные Flyway V20+** (перед первым SQL проверить фактический HEAD и checksum V1–V19; номера фиксируются только непосредственно перед реализацией):

| Slice / пример миграции | Таблицы и ограничения | Особые условия |
| --- | --- | --- |
| S1 / V20 | `catalog_search_term(id UUID PK, term, normalized_term, locale, status, revision, created_at)`; `catalog_term_category(term_id FK, category_id FK, relation_type, weight, UNIQUE(term_id,category_id,relation_type))` | Locale `ru/kk/en/zh`, status `ACTIVE/INACTIVE`, relation allowlist. Не выдумывать category links |
| S1 / V21 | `catalog_search_document(listing_id UUID PK FK, product_id FK, category_id FK, normalized_title, search_tsv tsvector, projection_version, updated_at)`; `pg_trgm`; GIN tsv/trgm | Published-only read filter в SQL; projection хранит link, но не является ACL |
| S2 / V22 | `catalog_listing_media(id PK, listing_id FK, object_key UNIQUE, sha256, status, sort_position, is_cover, mime, width, height, created_at, version)` | Partial UNIQUE cover per listing, prevent foreign ownership via service, immutable object variants; old PUBLIC without media supported |
| S4 / V23 | `buyer_favorite(id PK, buyer_id, listing_id FK, created_at, UNIQUE(buyer_id,listing_id))` | Buyer ID server resolved from active buyer_account_link; ownership index `(buyer_id,created_at DESC,id DESC)`; нет notification consent |
| S5 / V24+ | `buyer_category_watch`, `buyer_price_watch`, `user_notification`, `notification_preference`, `push_device`, `notification_delivery` и review-approved general domain event outbox | НЕ использовать `order_outbox_event` как есть, поскольку aggregate_id FK customer_order; no auto opt-in |
| S6 / later | `catalog_media_embedding(media_id, model_version, embedding)` и `pgvector` extension | Только после выбора licensed encoder/dimension, отдельная миграция и backfill |

**Не меняем Flyway V1–V19**, DRAFT-only SKU trigger, Order snapshots или InventoryReservation. Не ссылаться на старые SQL/Liquibase fixture `smartdeal-contract-tests`.

## 3. P0 Search dictionary, query и ранжирование

- **Нормализация:** Unicode NFC, whitespace collapse, безопасная Unicode casefold/locale-aware mapping; не менять SKU/брендовые коды произвольно; ограничить длину до 80..120 Unicode code points, размер body/результатов/rate.
- Для `ru/en` использовать `to_tsvector` с проверенной конфигурацией; для `kk/zh` сначала dictionary aliases и `simple` tokenization/целевые n-grams при тестах. `pg_trgm` добавляет typo fuzzy; PostgreSQL locale/collation и расширение проверять на реальном Testcontainers PG, а не H2.
- **Term → Category:** `SYNONYM` означает синонимы внутри утверждённого class concept, не автоматическую совместимость SKU; `CATEGORY_TERM` расширяет HCC-потомков; `SERVICE_INTENT` отдельно от товара; `RELATED` отдельная секция рекомендаций только после exact.
- Поиск должен возвращать конкретные `Listing`, один Listing = одна карточка. SQL join guards `l.status='PUBLIC' AND c.active=true AND q.listing_id=l.id`; ни одна projection/embedding row не может их обойти. Bounded limit 24 (макс.48) и timeout; селект полей DTO whitelist.
- Индекс может быть stale: fallback plain `lower(canonical_name)`/bounded search на этапе rollout; online visibility всегда проверяется из authoritative таблиц. Справочник по синонимам — статический seed fixture + интерфейс модерации позже, не self-learning из произвольных запросов.
- **P0 сортировка:** `lower(p.canonical_name) ASC, l.id ASC` как воспроизводимая первая реализация с keyset (аналог текущей read projection), затем словарная релевантность `relevanceBucket DESC, lower(title), listingId` только после RED и стабильного version/snapshot. `publishedAt` отсутствует в исходном SellerListing — запрещено ссылаться на него до миграции.
- **Cursor:** opaque signed/HMAC, хранит lastKeys, normalized query, filterHash, vocabulary/index revision, expiry и sort ID. Несовпадение фильтров → 400 invalid cursor, устаревшая revision → 409 restart search. При запросе страницы всегда проверять live visibility. 1 курсор не должен зависеть от page/offset в старом API.
- **Price facets:** допускаются только из `catalog_sku.public_unit_price_kzt` non-null, цена «от» лишь минимальное опубликованное значение; null → «Цена по запросу». Нельзя сравнивать all-in без delivery quote. City/coverage, рейтинги и seller displayName пока отсутствуют в PublicCatalogReadService — соответствующий UI/фильтр выключен до verified source.
- Данные кратных SKU группировать на уровне listingId, чтобы не было дублей результатов. SQL index plan/EXPLAIN на 10k/100k/1M синтетических Listing, но целевые SLO утвердить из измерений.

## 4. API v1 (проектное точное назначение)

| Endpoint | Auth | Semantics |
| --- | --- | --- |
| `GET /api/v1/public/catalog` | guest | Существующий контракт без breaking changes; старый page/size сохраняется |
| `GET /api/v1/public/search/suggest?q=&locale=` | guest, feature flag | максимум 10 нормализованных предложений, без раскрытия черновых терминов |
| `GET /api/v1/public/catalog/search?q=&categoryCode=&productType=&limit=&cursor=` | guest, feature flag | index + authoritative visibility; `items[]`, `nextCursor`, `hasMore`; no precise total on each page |
| `GET /api/v1/public/catalog/feed?limit=&cursor=` | guest, feature flag | те же URL/DTO/visibility; один Listing одна карточка |
| `GET /api/v1/public/catalog/{listingId}/media` | guest | только PUBLIC+active+QR, только sanitized derivatives, no objectKey |
| `POST /api/v1/sellers/{sellerId}/listings/{listingId}/media` | seller JWT `catalog:write` | seller membership + ownership; DRAFT-only upload initiation; separate upload/finalize, no binary body in JPA tx |
| `GET /api/v1/me/favorites?categoryCode=&limit=&cursor=` | buyer JWT | active buyer account, own listing IDs, HCC hierarchy, public projection or unavailable placeholder |
| `POST /api/v1/me/favorites` | buyer JWT | `{listingId}`, idempotent 201/200; no sellerId/buyerId client authority |
| `DELETE /api/v1/me/favorites/{listingId}` | buyer JWT | idempotent 204; does not unsubscribe category globally |
| `PUT /api/v1/me/category-watches/{hccCode}` | buyer JWT P1 | independent opt-in and disable, absent/default OFF |
| `PUT /api/v1/me/favorites/{listingId}/price-watch` | buyer JWT P1 | reject/disable until verified price events + versioned repricing are real |
| `POST /api/v1/public/search/by-image` | guest, heavily rate-limited P1 | upload→encoder→candidate IDs→authoritative search; no auto order |

**API fail-closed:** `ApiSecurityConfig` allowlist обновлять совместно с `@ConditionalOnProperty` флагами `zakaz.search.http.enabled`, `zakaz.catalog.media.enabled`, `zakaz.catalog.feed.enabled`, `zakaz.favorites.http.enabled`, `zakaz.visual.search.enabled` default false; owner-scoped JWT never `permitAll`. Новый эндпоинт не становится доступен только за счёт аннотации/конфигурационного ключа.

## 5. Media/PhotoFeed & navigation

- Seller upload `DRAFT` → staged object → MIME sniff/size/pixel/decompression/exif checks + malware control → resized immutable derivatives → READY; only READY eligible for public cover. Avoid stale public copies on unpublish/ARCHIVE; serve via visibility checking image proxy or design-approved short TTL.
- Master-TZ targets 3–5 photos when published; do not enforce before storage readiness. Existing PUBLIC with 0 photo remain in catalog with safe placeholder; new publication policy to be ratified (3–5 via feature flag only for new Listings).
- React `LiveCatalog` remains route root, accepts new API response with cursor; `IntersectionObserver` guarded by inFlight/ref, abort stale fetch, dedup by listingId, restore URL/query/scroll. Responsive grid 3–4/2, lazy loading. Images from catalog only, no fake ratings/prices/sellers.
- `/q/{token}` presents seller's Listing and media; `/buy` is a **market request** requiring valid productId/unit and buyer login. CTA text must reflect that; QR guest PurchaseLead remains contact-only. Seller-specific checkout cannot be claimed until verified listing-bound bid flow exists.
- Double-click desktop / optional double-tap mobile (do not disable zoom); `a href` / single-tap «Подробнее» / Enter always work. Heart, gallery and quantity controls stop accidental double-click navigation; keyboard/screen readers covered.

## 6. Favorites, HCC and notifications

- P0 `buyer_favorite` source of truth; server buyerId resolved `iss+sub → active buyer_account_link`, no ID in body; on removal/unlist show safe placeholder without exposing private photos, prevent cross-account IDOR; Web/Flutter sync after auth.
- Guest favorites local-only, explicit merge after login; no false persistence claims. Parent HCC grouping uses `catalog_product.category_id` (do not store redundant favorite.category_id); 0 duplicate listings across parent/child filter results.
- P1 CategoryWatch/PriceWatch settings separate from save action and separate per notification channel/transport. No Push by default; browser and OS permissions requested only via user action.
- **Block PriceWatch until repricing model exists**: Published SellerSku price is immutable in V3/V18; quotes/orders immutable. Design new seller-approved price revisions/versioned public snapshot and after-commit price-changed events only before enabling alert feature.
- **Block notification fanout until generic event contract exists**: OrderOutboxEvent has FK to customer_order and cannot process catalog events; design/review generic `domain_event_outbox` and subscriber dedup/claim/lease while preserving current order flow. Existing `OutboxDispatcher` pattern may be reused after test qualification, but current implementation is not generalized.
- Internal Notification Center is source of truth; Push transport optional, best-effort, fails without blocking catalog/order; send-time opt-in, quiet hours, per-device revocation, digest, consent audit, push token encryption, PII masking.

## 7. RED oracle mapping and build order

| Slice | RED test names | What must fail on baseline | GREEN gate |
| --- | --- | --- | --- |
| S1 Search | `SearchDictionaryPostgresIT`, `SearchVisibilityPostgresIT`, `SearchTermSecurityMockMvcTest` | aliases, HCC, private listings, query handling | Flyway V20+ + search API + CI |
| S2 Media | `SellerMediaOwnershipIT`, `ListingMediaLifecycleIT`, `PublicMediaVisibilityIT` | seller IDOR, MIME/EXIF, published image revocation | managed storage, no SQL mutation after PUBLIC |
| S3 Feed UX | `CatalogFeedKeysetPostgresIT`, `CatalogNavigationPlaywright` | cursor duplicates/restarts, double click exact Listing, seller-specific CTA claims | Web build + browser E2E + same API Flutter |
| S4 Favorite | `BuyerFavoritePostgresIT`, `FavoriteOwnerMockMvcTest`, `FavoriteCrossClientIT` | unique/owner isolation, category grouping, guest opt-in import | Buyer identity staging + DB/tests |
| S5 Notification | `PriceRevisionIT`, `CategoryWatchConsentIT`, `NotificationOutboxReplayIT` | no repricing, consent, dedup on current baseline | separate S2 + durable worker |
| S6 Visual | `VisualSearchRecallSuite`, `VisualSearchVisibilityIT` | no encoder/image dataset/pgvector | signed model/artifact + quality benchmark |

**Minimum S1 acceptance:** "болгарка" = "УШМ" for approved fixture; category child matches; unrelated service excluded; typo known match; PUBLIC-only; 4 locale test fixtures; duplicate SKU rows collapse into 1 Listing; missing null price not invented; bounded query + cancel; historical `GET /catalog` unchanged. Any missing runtime tests must be explicitly noted, not marked PASS.

## 8. Decision register / NO-GO list

- DEC-S01: 3–5 required photos for newly published Listing and legacy grandfather rule — needs product-owner sign-off.
- DEC-S02: S3 feed initial sort `title + listingId` vs later `relevance + listingId` — propose deterministic title first.
- DEC-S03: Seller-specific checkout: separate API milestone; current CTA market demand only.
- DEC-S04: price changes on PUBLIC require versioned pricing before watch.
- DEC-S05: catalog event outbox design separately reviewed, no misuse of order FK.
- DEC-S06: approved S3 media storage provider/size/retention/CSP/CDN and public visibility TTL.
- DEC-S07: query language morph/normalization acceptance for KK/RU/EN/ZH and curated lexicon data owner.
- DEC-S08: low-risk read-only Search may be developed in Draft branch without releasing other flags, but production still blocked by R2.3F and independent S2.

**Definition of Ready S1:** DDL/DTO decisions above accepted, exact baseline SHA pinned, approved PostgreSQL RED tests, migration roll-forward/dry-run, no branch proliferation. **Not** implementation complete on delivery of this document.
