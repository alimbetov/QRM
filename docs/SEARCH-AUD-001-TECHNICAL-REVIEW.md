# SEARCH-AUD-001 — технический аудит интеграции SEARCH-001 в ZAKAZ

**Дата:** 2026-11-13. **Статус:** TECHNICAL REVIEW / CONDITIONAL, не независимое S2 APPROVE и не подтверждение production-ready.
**База проверки:** ветка `docs/adr-002-jpa-architecture-review`, SHA `c43022d18477fa207916c77f22bb4adcc578386c`. Технические выводы основаны на просмотренных файлах GitHub; локальная сборка, запуск PostgreSQL, SMTP, Playwright, Flutter device и benchmark в ходе этого аудита **не выполнялись**.
**Цель:** сверить SEARCH-001 с имеющимися Backend/DB/Web и исправить требования до состояния, в котором можно писать исполняемые PostgreSQL RED-тесты и вертикальный P0 slice без архитектурного долга.

## 1. Вердикт

**Согласовать направление, НЕ принимать SEARCH-001 целиком как готовое к GREEN.** Допустим переход к S0 контрактам и S1 PostgreSQL RED после закрытия явных дизайн-решений ниже. Составной проект «словарь + изображения + лента + памятка + Push + ML» разделяется на самостоятельные независимые релизные срезы; P1 Push/visual search не должны блокировать первый публичный поиск.

Одна существующая ветка; `main` не изменять, Draft PR #2 не мерджить до собственных независимых release-gates.

## 2. Проверенные архитектурные факты

1. `PublicCatalogController` реализует только `GET /api/v1/public/catalog` и `/categories`; `PublicCatalogReadService` — SQL `lower(canonical_name) LIKE`, `categoryCode`, offset page 0..1000, size 1..24, join Listing PUBLIC + active HCC Category + QR public link. Нет фасетов, фото, city, seller display name, ranking или cursor.
2. `web/src/main.tsx` выводит `LiveCatalog` на `/`; там поиск по имени/категории, кнопки «Запросить предложение» и «Открыть карточку по QR». Нет избранного, фотоленты и double-click-to-product; `App.tsx` — демо, не активная точка входа.
3. `SellerListing` имеет DRAFT → PUBLIC; `SellerSku` с optional `public_unit_price_kzt` и набором SKU после публикации неизменяем. PostgreSQL `V3` запрещает INSERT/UPDATE/DELETE SKU и их атрибутов, когда Listing не DRAFT. Поэтому P1 price-watch «снижение цены» **сейчас не имеет легального источника изменения опубликованной цены**.
4. `/q/{qrToken}` — публичное Listing отображение, сейчас без фото, выбора SKU для заказа и seller-specific checkout. `/buy?productId=&unit=` → `BuyerDemand` на canonical product, **не** на указанный SellerListing/SellerSku. Сценарий «Заказать именно у этого продавца» не подтверждён действующим HTTP контрактом.
5. `ApiSecurityConfig` построен на явном deny-by-default allowlist. Новые GET/POST/PATCH endpoints будут отклоняться, пока не изменены разрешения, scope и feature flags. `BuyerIdentityService.requireBuyer` проверяет JWT/active buyer link и опционально email_verified.
6. `order_outbox_event.aggregate_id` имеет **FK к customer_order(id)**, `payload varchar(512)`, `UNIQUE(aggregate_id,event_type)`; `JdbcOrderEventProjectionSink` — только внутренний обработчик Order. Нельзя без изменения схемы или явного другого канала отправлять через эту таблицу LISTING_PUBLISHED, CATEGORY_WATCH, PRICE_CHANGED, FAVORITE события.
7. Нет runtime таблиц `favorite`, `user_notification`, `category_watch`, `listing_media`, `search_term`; в действующей ветке Flyway заканчивается V19. Существуют **только продуктовые планы** в master-TZ, DEC-BUYER-WEB-001, DEC-27/ADR-004.
8. `Category` — неизменяемый 20-символьный HCC 2/2/3/5/8, с `parent_id` и ProductType (PHYSICAL/SERVICE/DIGITAL); `CategoryAttributeRule` содержит filterable, но текущий публичный API не фильтрует значения.
9. Настройки `zakaz.catalog.http.enabled`, `zakaz.qr.http.enabled` по умолчанию OFF; R2.3F реальные SMTP/browser/mobile/UAT и 5k load не закрыты.

## 3. Проблемы и обязательные исправления

| ID | Важность | Несоответствие | Решение для SEARCH-001 | Gate |
| --- | --- | --- | --- | --- |
| SA-01 | BLOCKER | Order-only Outbox с FK на Order не поддерживает каталог/Push | P0 индекс поддерживать транзакционным обновлением или безопасным backfill; для P1 event delivery нужен отдельный **general domain outbox design** с сохранением Order outbox; не заявлять, что он существует | S5 |
| SA-02 | BLOCKER | Published price после V3 неизменяема | Price-watch откладывается до отдельного versioned repricing model с неизменными quote/order snapshots и событиями реальной смены цены | S5 |
| SA-03 | BLOCKER | /buy не фиксирует выбранный seller/listing/SKU | В P0 переименовать CTA: «Запросить предложения» (рыночный спрос на CanonicalProduct), QR guest lead — «Связаться с продавцом»; seller-specific checkout — отдельная задача с server-verified quote context | S3 |
| SA-04 | BLOCKER | Runtime нет Seller Media и storage provider | Ввести ListingMedia, DRAFT-only upload/finalize в P0, объектные ключи/EXIF/thumbnail; публичные изменения — отдельный moderated revision flow | S2 |
| SA-05 | BLOCKER | Runtime Favorites/Push отсутствует | `Favorite` — P0 отдельная таблица с owner-scope через BuyerIdentityService; CategoryWatch/Notification Center/Web Push не включать в GREEN P0 | S4/S5 |
| SA-06 | BLOCKER | Security denyAll на новых API | Явно проектировать `ApiSecurityConfig` whitelist + JWT scopes + membership/owner + secure feature flags, негативные MockMvc тесты | Все |
| SA-07 | HIGH | Курсор использует несуществующую дату публикации / score revision | Для первой ленты keyset по (`lower(canonical_name)`, `listingId`), без invented publishedAt; relevance cursor только после фиксации словарной ревизии/query snapshot | S3 |
| SA-08 | HIGH | Риск index leakage от скрытых / inactive Listing | Каждый read path (поиск, фото, миниатюра, favourites) повторно проверяет `PUBLIC + activeCategory + qrLink`; отсутствие/устаревание индекса не даёт доступ | Все |
| SA-09 | HIGH | Из ТЗ не следует, как ценовые и территориальные фильтры работают при null/неполных фактах | Цена — optional опубликованный SKU; город/доставка не добавлять до авторитетного источника seller coverage; типизированные атрибуты через уже существующий Catalog | S1/S3 |
| SA-10 | HIGH | Словарь/FTS предполагает одинаковые правила для KK/RU/EN/ZH | Нормализация Unicode NFC, locale aliases, `pg_trgm`; язык настраиваем, тестируется отдельно, китайские/казахские стеммеры не объявлять готовыми | S1 |
| SA-11 | HIGH | Требование 3–5 фото уже в master-TZ, но отсутствует storage | Transitional policy: существующие опубликованные товары без фото остаются в каталоге с placeholder; правило 3–5 вводить для новых публикаций только после media pipeline/миграции, без пересмотра мастер-ТЗ без решения owner | S2 |
| SA-12 | HIGH | Маркетинговые Push по категории трактуются как следствие Favorite | Два независимых opt-in на PriceWatch и CategoryWatch; off-by-default, плюс permission устройства; unsubscribe непосредственно перед delivery, no inferred consent | S5 |
| SA-13 | HIGH | «10 страниц без дублей» не определяет согласованность при вставках | Определить ограничение snapshot/window и поведение при depublication, signed cursor с hash filters и TTL; тесты параллельной публикации | S3 |
| SA-14 | MEDIUM | Двойной тап как основной touch UX конфликтует с браузерным zoom | Поддерживать double-click/double-tap, но гарантировать доступный single-tap «Подробнее»/Enter; взаимодействие с heart не триггерит navigation | S3 |
| SA-15 | MEDIUM | Visual Search без набора фото/quality metrics | Отложить pgvector/ONNX до media pipeline, ground-truth image dataset, model license, Recall@10 и false match audit; нельзя автоматический SKU/order | S6 |
| SA-16 | MEDIUM | S0 и 5k order gate смешаны с фичей Search | Использовать стабильный feature ID SEARCH-001; 5k search нагрузка — отдельная perf qualification; 5k order gate остаётся самостоятельным | Release |

## 4. Зафиксированное соответствие проекту

| Домен/файл | Existing authoritative contract | Новый контракт |
| --- | --- | --- |
| Category/HccPath | 20-digit HCC, ProductType и parent | SearchTerm связывается с `category_id`, не вводит новый Category |
| AttributeDefinition/CategoryAttributeRule/SkuAttributeValue | Canonical validated typed facts | Только опубликованные SKU attributes и только whitelist filterable |
| SellerListing/PublicCatalogReadService | status PUBLIC, QR link, active category | Те же SQL gates у Search/Feed/Media и guest-side discovery |
| SellerSku/V3/V18 | immutable after PUBLIC; optional published KZT price | PriceFrom только при present; repricing до P1 запрещён |
| /q/{token} / BuyerFlow | guest lead или authenticated market demand | Нет seller-specific purchase обещания до отдельного подтверждённого API |
| BuyerIdentityService/ApiSecurityConfig | JWT+server buyer link, fail closed | Favorite owner-scoped, no client buyerId, explicit scopes |
| order_outbox_event / JdbcOutboxQueue | order-only FK, idempotent internal sink | Не использовать для незаказных событий без нового ADR |
| docs/ADR-004 | CandidateSearchEngine PG FTS/lexicon, ML позже | PostgreSQL FTS + dictionary, Visual Search отложен |

## 5. Жёсткие критерии входа в реализацию

- **START S0/S1 RED:** этот аудит, дополненный SEARCH-001 и нормативный implementation contract зафиксированы в одной ветке; утверждены HCC search semantics, request/response DTO и безопасность; первый schema plan V20 не переписывает V1–V19.
- **START S1 GREEN:** настоящий PostgreSQL RED для visibility, HCC synonym, cross-locale, multi-SKU, sql injection, cursor contract; независимый S2 review test oracles требуется по существующей дисциплине проекта.
- **START S2 GREEN:** утверждённый storage adapter, lifecycle, owner ACL, публикационная политика для исторических карточек, media scanning, migration rehearsal.
- **START S3/S4 GREEN:** API/React/Flutter acceptance E2E, auth owner-scope, click/double-click/tab keyboard/scroll; действующий Keycloak stage, не fake identity.
- **START S5:** введены отдельный notification center и согласованный domain event outbox; для PriceWatch — ещё и версионирование реальных изменений публичной цены.
- **START S6:** фото-корпус и метрики качества/лицензия ML.
- **Release/merge в main:** независимый S2 на exact SHA, внешний Keycloak SMTP, Browser/Flutter E2E, операционные/миграционные требования и 5k order/load gate — отдельно; не путать CI с UAT.

## 6. Результат аудита и границы

**Вердикт: CONDITIONAL S0 — направление согласовано с архитектурой на уровне технической рекомендации, но независимое утверждение и продакшен-приёмка отсутствуют.** Реализовывать сначала S1 textual discovery; S2 Media; S3 PhotoFeed/Navigation; S4 Favorites; S5 Notifications/Repricing; S6 Visual Search. Не добавлять отдельный микросервис или Elasticsearch, не переписывать заказ. Обязательные оставшиеся решения и точные DB/API contracts — в `SEARCH-001-IMPLEMENTATION-CONTRACT.md`.
