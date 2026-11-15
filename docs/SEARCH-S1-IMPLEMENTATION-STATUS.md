# SEARCH-S1 — реализация словарного PostgreSQL-поиска и результаты CI

**Дата:** 2026-11-13. **Состояние:** RED → GREEN candidate verified by GitHub Actions; независимый S2 Review и deployment E2E **НЕ выполнены**.
**Ветка:** `docs/adr-002-jpa-architecture-review`. `main` без изменений, PR #2 Draft.
**Область:** SEARCH-001 / S1 Search Foundation. S2 Media, S3 PhotoFeed, S4 Favorite, S5 Watch/Push, S6 Visual и S7 Photo-to-Demand НЕ реализованы.

## 1. История реализации

| Коммит | Содержание | Доказательство |
| --- | --- | --- |
| `d5cc36d132bda13157587c93582656ec7566e4db` | Настоящие RED PostgreSQL IT | Backend CI ожидаемо FAILED до создания схемы/API |
| `70fd78da613e6f2924391d9d7124ba4e1b2b0916` | Flyway V20/V21, search/suggest APIs, HMAC cursor, публичный security allowlist | Fast PASS, PostgreSQL IT показал 400 из-за двойного кодирования q в тесте |
| `da36afdb2afbacc8d1e640df43df628241f13045` | Исправление тестового MockMvc request параметра Unicode | Backend + Web + Flutter + Staging CI PASS |
| `3f4c08eeb093bae5fe8f37461b8a13aa1ca57081` | Доп. RU/KK/EN/ZH, HCC-parent filter, cursor invalidation и trigram/index + query timeouts | Backend + Web + Flutter + Staging CI PASS. `SearchDictionaryPostgresIT` **6 tests, 0 failures, 0 errors**, `mvn verify` BUILD SUCCESS |

Точный GitHub Action run Backend: `43489732071`; Postgres job `132107386530`. CI выполнялся на GitHub Actions Java 21 + PostgreSQL 17 Testcontainers, локальная среда и публичный сайт в ходе этого шага не запускались.

## 2. Реально добавленные файлы

- `backend/src/main/resources/db/migration/V20__catalog_search_dictionary.sql`: контролируемые SearchTerm/TermCategory, связи HCC, локали ru/kk/en/zh, статус ACTIVE/INACTIVE, автоматическое увеличение ревизии словаря при DML.
- `backend/src/main/resources/db/migration/V21__catalog_search_postgres_indexes.sql`: `pg_trgm`, GIN для названий, FTS и словаря, частичные индексы PUBLIC Listing/active Category.
- `backend/src/main/java/kz/zakaz/catalog/PublicSearchReadService.java`: SQL-поиск с рекурсивным HCC, FTS, LIKE, индексируемой trigram-проверкой и точными PUBLIC+active HCC+QR-контрактами; один Listing/карточка, вариации SKU без N+1, nullable price без выдуманных цен; REPEATABLE_READ read-only с timeout 5s.
- `backend/src/main/java/kz/zakaz/catalog/PublicSearchController.java`: новый GET search + GET suggest.
- `backend/src/main/java/kz/zakaz/catalog/SearchCursorCodec.java`: HMAC-SHA256 подписанный keyset с 15 min TTL, словарной ревизией, проверкой query/filters.
- `backend/src/main/java/kz/zakaz/security/ApiSecurityConfig.java`: точечные публичные GET и fail-closed остальных операций.
- `backend/src/test/java/kz/zakaz/catalog/SearchDictionaryPostgresIT.java`: шесть реальных PostgreSQL/Testcontainers интеграционных сценариев.

Существующий `GET /api/v1/public/catalog` и React `LiveCatalog` в этой итерации **не менялись**, commerce/order и SKU immutable facts не затронуты.

## 3. HTTP-контракт текущего SEARCH-S1

```http
GET /api/v1/public/catalog/search?q=болгарка&limit=24&categoryCode=01000000000000000000
GET /api/v1/public/search/suggest?q=болг&locale=ru
```

Это иллюстрация формата URL: категория и нормализованный синоним должны **реально существовать** и быть утверждены в текущей БД, приведённый HCC не следует считать существующей категорией ZAKAZ. Search response содержит `items[]`, `nextCursor`, `hasMore`; каждый Item — `listingId`, `productId`, `sellerId`, `qrToken`, `title`, `category`, `categoryCode`, `productType`, `variants[]`.

Подсказки: термины ACTIVE, locale и HCC, без выдачи RELATED в точных совпадениях. No stock/phone/warehouse/buyer PII.

## 4. Безопасное включение

Новый API закрыт по умолчанию: `zakaz.search.http.enabled=false`. Для отдельного защищённого staging нужно явно включить флаг **и** передать приватный `zakaz.search.cursor-secret-base64` длиной не менее 32 случайных байт до Base64. Генерация вне репозитория: `openssl rand -base64 32`; секрет не коммитить и не логировать. Без секрета сервис fail-fast при включённом поиске.

Новые таблицы Flyway создаются с пустым словарём. Никакие произвольные HCC/синонимы не создаются автоматически из пользовательского ввода; для реального «болгарка → УШМ» сначала требуется редакционно утверждённая привязка с существующим `catalog_category.id` (в текущем срезе — безопасный operator seed/import; UI-admin позже). Отсутствующий словарь не блокирует текстовые title matches.

## 5. Почему нет materialized SearchDocument

Это осознанное минимальное отклонение от предложенного S0 implementation plan: работающий S1 использует индексированный **живой Catalog SQL JOIN**. Не требуется ненадёжный dual-write/дополнительный Outbox для поиска, а статусы DRAFT/UNLISTED/ARCHIVED всегда проверяются авторитетными таблицами. `catalog_search_document` не добавлен в V21; вводить при доказанном SQL profiling/нагрузочном выигрыше и транзакционном backfill.

## 6. Не закрытые условия S2 и следующая итерация

1. Независимый S2 review exact SHA: security публичных GET, Unicode/SQL normalization, false positives, HCC-кодирование, миграции и cursor secret handling.
2. Реальный curated lexicon seed RU/KK/EN/ZH с назначенным редактором, версионированный backfill и аудит изменений. Административный API/Studio пока отсутствует.
3. Проверка EXPLAIN ANALYZE на 10k/100k/1M Listing и worst-case queries, p95/p99 и max concurrent; Spring transaction timeout не заменяет бюджет SQL/нагрузочную квалификацию.
4. Дополнительные tests: полнота negative ACL, fuzzing Unicode, dictionary version race, EXPLAIN/perf и stale/publication status concurrent changes.
5. Staging OIDC/SMTP, browser E2E и Flutter real-device E2E по прежнему R2.3F, а также независимая 5k order qualification.

**Следующий engineering slice:** SEARCH-S2 ListingMedia (seller ownership ACL, S3-compatible storage, EXIF/mime, thumbnail, publication lifecycle), затем SEARCH-S3 React фотолента с подгрузкой и двойным нажатием переходом на `/q/{token}`.
