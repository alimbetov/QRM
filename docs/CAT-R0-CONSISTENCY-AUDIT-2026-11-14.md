# CAT-R0 — Аудит согласованности каталогов, Search, ML и Commerce

**Дата:** 2026-11-14  
**Исходный проверенный SHA:** `0c6580e812b985f426b14e8fd2fa00a936cf1932`  
**Ветка:** `docs/adr-002-jpa-architecture-review`, PR #2 Draft, `main` не изменять.  
**Статус:** **SOURCE_REVIEW_COMPLETED / DESIGN_BASELINE_PROPOSED**; независимый S2, новая PostgreSQL RED→GREEN квалификация, браузерные и мобильные E2E **не выполнены в рамках CAT-R0**.  
**Нормативная основа:** [CAT-001](CAT-001-UNIVERSAL-CATALOG-EXPANSION-MASTER-SPEC.md), [REQUIREMENTS](REQUIREMENTS.md), [DEC-UCE-001](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md), [DEC-INTELLIGENCE-001](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md), [SEARCH-001](SEARCH-001-UNIFIED-DISCOVERY.md), [ADR-002](ADR-002-MVC-JPA.md).

## 1. Метод и ограничения доказательств

Реально прочитаны классы, SQL, контроллеры, тестовые исходники и React/Flutter клиентов из проверенного commit SHA. GitHub Actions **для исходного SHA** показал 4 завершённых `success` workflows: Java 21 Backend, Buyer Web UI, Flutter Mobile, Staging Configuration (GitHub run IDs 43559854920, 43559854942, 43559854935, 43559854982). Это **не новая проверка четырёх пилотов** и не доказательство работы новых типов/отраслей. Нет подключения к реальной business БД, нет EXPLAIN/нагрузки, не проводился независимый reviewer approval. Каждый нижеуказанный результат — **исходный аудит кода**. Последующие изменения ветки требуют обновления матрицы.

Evidence: ссылки далее ведут к конкретному исходному commit, а не плавающему HEAD.

## 2. Инвентаризация существующих контрактов

| Область | Факт в коде | Текущий рубеж |
|---|---|---|
| HCC | Category: UUID id, immutable String hccCode 20, ProductType, parent FK, `@Version`; HccPath 2/2/3/5/8 | READY_TO_REUSE, без версионной бизнес-таксономии |
| Attribute | Definition: ENUM/DECIMAL/BOOLEAN/TEXT, canonicalUnit; Rule: required/filterable по exact Category; EnumOption | минимальная SKU-схема, не отраслевой универсальный реестр |
| Canonical | CanonicalProduct category + canonicalName; SellerListing sellerId/product/status; SellerSku variant/stock/unit/price | товары: базовая модель, service flow ограничен |
| Registry | SkuAttributeValue: `canonical_value varchar(256)`, UNIQUE(sku_id,attribute_id), immutable after PUBLIC | single-value SKU, нет LOT/DATE/MULTI |
| Search | SearchTerm(locale, normalized_term), TermCategory, `pg_trgm`, FTS, HMAC cursor, PUBLIC+QR checks | словари и текстовые выдачи, не полный typed facets |
| ML | FeatureRegistry, CanonicalFeatureAdapter, ClassifiedOfferMatcher, ML-R2 event journal | source-backed deterministic match, отдельно от обученных prod models |
| Commerce | BuyerDemandLine productId/quantity/unit, SellerBid SKU, OrderLine immutable; tracked stock + InventoryReservation | точное совпадение единицы/SKU, одна строка — один source balance |
| Seller UX | React SellerWorkspace вводит Product UUID руками; backend createDraft ограничен PHYSICAL+TRACKED | не mass-category onboarding, не service catalog |
| Clients | React и Flutter ограничивают buyer request PHYSICAL | функционал услуг не имеет работающего E2E-контракта |

Ключевые файлы:
- [Category.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/Category.java), [HccPath.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/HccPath.java)
- [AttributeDefinition.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/AttributeDefinition.java), [CategoryAttributeRule.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/CategoryAttributeRule.java), [SkuAttributeValue.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/SkuAttributeValue.java)
- [CatalogPublishingService.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/CatalogPublishingService.java), [CatalogSkuFactsResolver.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/CatalogSkuFactsResolver.java)
- [SellerCatalogWorkspaceService.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/SellerCatalogWorkspaceService.java), [SellerWorkspace.tsx](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/web/src/SellerWorkspace.tsx)
- [PublicSearchReadService.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/catalog/PublicSearchReadService.java), [V20 dictionary](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/resources/db/migration/V20__catalog_search_dictionary.sql), [V21 search indexes](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/resources/db/migration/V21__catalog_search_postgres_indexes.sql)
- [CanonicalFeatureAdapter.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/intelligence/CanonicalFeatureAdapter.java), [ClassifiedOfferMatcher.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/intelligence/ClassifiedOfferMatcher.java), [IntelligenceDictionarySeed.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/intelligence/IntelligenceDictionarySeed.java)
- [BuyerDemandLineSnapshot.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/deal/BuyerDemandLineSnapshot.java), [OrderConfirmationService.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/inventory/OrderConfirmationService.java), [InventoryBalance.java](https://github.com/alimbetov/QRM/blob/0c6580e812b985f426b14e8fd2fa00a936cf1932/backend/src/main/java/kz/zakaz/inventory/InventoryBalance.java).

## 3. Матрица противоречий и рисков CAT-AUD

**Тип:** CODE_LIMIT = фактическое ограничение, DESIGN_GAP = не реализован целевой бизнес-контракт, DOC_DRIFT = требования устарели, PENDING = необходимость решения перед миграцией. Не считать DESIGN_GAP доказанным багом текущих PHYSICAL сценариев.

| ID | P | Тип | Подтверждение | Согласованное направление / обязательный gate |
|---|---|---|---|---|
| CAT-AUD-01 | P0 | CODE_LIMIT | `SellerCatalogWorkspaceService.createDraft` требует `PHYSICAL` и `TRACKED`; `OrderConfirmationService` аналогично проверяет TRACKED | SERVICE требует собственную политику исполнения; запрет делать fake tracked inventory |
| CAT-AUD-02 | P0 | CODE_LIMIT | `AttributeDefinition.validateValue` отвергает все отрицательные DECIMAL; действующий unit test ожидает reject(-1) | Перейти к per-attribute signed range: storage temp -18 разрешено, storage GB -1 запрещено; RED до GREEN |
| CAT-AUD-03 | P0 | DESIGN_GAP | `catalog_sku_attribute_value` только строка и UNIQUE(sku,attribute); `CategoryAttributeRule` без revision, scope, inherited rules | Версионированные scoped typed facts; не перетирать публичные SKU/исторические snapshots |
| CAT-AUD-04 | P0 | DESIGN_GAP | `inventory_balance` UNIQUE(seller,location,sku), `inventory_reservation` order_line/location/sku; нет lot/expiry | Отдельный Lot + allocations через ADR, не ломать existing reserve/locks; FEFO только после доказательства |
| CAT-AUD-05 | P0 | DESIGN_GAP | `MeasurementUnit`: PCS, KG, G, L, ML, M, M2, M3, HOUR, SERVICE, LICENSE; нет verified pack conversion; `OrderConfirmationService` требует точное совпадение sales unit | Разделить sellable unit и content; сравнение в базовой единице только после authoritative conversion; checkout legacy untouched |
| CAT-AUD-06 | P0 | DESIGN_GAP | `CanonicalProduct` category/name, в DDL нет uniqueness по canonical identity | Зафиксировать stable identity/approved canonical key с правилами brand/model/cultivar и dedup queue; новые ограничения only after data audit |
| CAT-AUD-07 | P0 | DESIGN_GAP | HCC строка неизменна, код `Category` не предоставляет полной rev/alias/translation/move модели; SQL не обеспечивает полный consistency tree | Сохранить существующий HCC/UUID; approve mapping, immutable revisions, no renumber; PostgreSQL integrity tests |
| CAT-AUD-08 | P1 | CODE_LIMIT | `PublicSearchController.search` имеет q/categoryCode/productType/limit/cursor; в SQL нет typed filter по SKU attribute и packaging | Query whitelisting + typed filters with PG indexes and P95 benchmark, без dynamic unsafe SQL |
| CAT-AUD-09 | P1 | DESIGN_GAP | V20 словарь содержит SYNONYM/CATEGORY_TERM/RELATED/SERVICE_INTENT; one term may have multiple category mappings; search expands categories | Поддержать polysemy с explicit intent; не записывать RELATED/INSTALLED_BY как SYNONYM |
| CAT-AUD-10 | P1 | DESIGN_GAP | `FeatureRegistry` task/version хранит in-memory names; `IntelligenceDictionarySeed` smoke-словарь классов, не постоянный catalog source | Не создавать вторую production taxonomy; record stable revisions and model feature schema hash, reprocessing; smoke namespace не authoritative |
| CAT-AUD-11 | P1 | DESIGN_GAP | `ClassifiedOfferMatcher` требует exact productId + classCode; compatible substitutes ещё не реализованы | Hard equality по каноническому предмету по умолчанию; substitute only curated compatibility+explicit opt-in |
| CAT-AUD-12 | P1 | DESIGN_GAP | `SellerWorkspace.tsx` требует ручной Product UUID; нет operator taxonomy management UI | Admin review/import и seller typed category picker, не direct create of canonical terms |
| CAT-AUD-13 | P1 | DESIGN_GAP | `catalog_category.label` одиночный язык, V20 SearchTerm поддерживает ru/kk/en/zh | Approved translation records+locale fallback without changing identity |
| CAT-AUD-14 | P1 | CODE_LIMIT | Данные сырого продукта/партии: нет DATE/REFERENCE/MULTI_ENUM, подтверждённых claims и packaging scope | Scope-aware attributes+evidence; compliance policies applied before publication |
| CAT-AUD-15 | P1 | DOC_DRIFT | `REQUIREMENTS.md` содержит исторические утверждения «BuyerDemand только Java records», но HTTP и persistence присутствуют | Зафиксировать source-of-truth HEAD; корректировать устаревшие status-параграфы отдельным doc revision, не подменять код документацией |
| CAT-AUD-16 | P1 | PENDING | `DEC-UCE-001` проектирует BIGINT DictionaryNode, а реальные `Category/AttributeDefinition` используют UUID | **Решение для CAT-R1:** UUID остаётся authoritative persistence identity; отдельный bigint только если ADR докажет необходимость; никаких массовых FK migrations |

## 4. Review vs existing tests: что уже доказано в тестовых исходниках

| ID | Existing automated evidence in repo | CAT gap |
|---|---|---|
| HCC parent consistency | `CatalogR1B2HccParentTest`, `CatalogFoundationTest` | DB parent graph/cycle/reclassification after published data |
| Publish fact gate | `CatalogR1B2PublishedImmutabilityTest`, `CatalogPostgresPublishedGuardIT`, `CatalogSkuFactsResolverTest` | schema revision, inherited rules, lot/food validations |
| Search | `SearchDictionaryPostgresIT` alias/locale/cursor/public gates | polysemy, 4-pilot facets, category related-vs-same, 100k+ perf |
| ML projection | `CanonicalFeatureAdapterPostgresIT` hard mismatch, enum revocation, repeatable vector | cross-revision train/infer, unknown vs zero, lot/food scope, event asOf provenance |
| Commerce | `OrderConfirmationPostgresIT`, inventory Postgres IT, OrderLine integrity tests | package conversion, lot allocation, service availability (not yet implemented) |

Это **кодовые тестовые источники**. В CAT-R0 не проводился локальный повторный запуск JUnit/Testcontainers, не утверждалась полнота покрытия для новых предметных областей. Исторические CI PASS подтверждают прежние сценарии, но не CAT-R1.

## 5. Результат архитектурного согласования: минимальные решения

**TECHNICAL_BASELINE_ACCEPTED_FOR_TEST_DESIGN** (не S2 и не production approval):

1. Один `Category/HccPath` и UUID Category/Attribute/Product; HCC не ordinal и не автоматически выделяемый user text.
2. `AttributeDefinition` глобален; `CategoryAttributeRule` задаёт applicability и версию. Каждому атрибуту выделить single authoritative `FACT_SCOPE`: PRODUCT, SKU, LOT, SERVICE_SPEC или SERVICE_OFFER. Не создавать одновременно «те же» значения в нескольких местах.
3. Типизированная модель обязана уметь отрицательные значения при индивидуальных bounds, DATE, controlled multi-values, verified reference, null/missing semantics. Эти расширения пока design-only.
4. Package и продажная единица разные. No implicit BOX→KG or PACK→L. Для текущего checkout оставить exact unit; новые пересчёты потребуют нового ADR.
5. Срок годности, происхождение партии, даты производства/урожая и claims — в LOT/evidence либо product-specific claim scope. Ни один флаг `organic=true` не равен сертификации.
6. PHYSICAL продолжает существующий order/reserve, SERVICE реализуется отдельным domain flow; SellerBid может переиспользовать коммерческую семантику лишь после отдельного HTTP+DB contract.
7. Search — PostgreSQL + current safety gates, typed facets как расширение; semantic RELATED/service-intent отдельно от identity/synonym.
8. ML — только canonical approved facts → deterministic eligibility → task-scoped feature projection с revision/hash и fallback. Сохранить существующие adapter/registry.
9. Seller/admin import — proposal→validate→approve→publish с audit и двухсторонними проверками; никаких auto import/auto-approve.
10. Четыре пилота фиксируются в [CAT-R0-PILOT-ATTRIBUTE-CONTRACT.md](CAT-R0-PILOT-ATTRIBUTE-CONTRACT.md). Имена, типы, scopes и негативные oracle используются в RED tests; production seeds запрещены до CAT-R1 S2.

## 6. Решения CAT-DEC-01…12 и статус

| Decision | Техническое направление | Финальный gate |
|---|---|---|
| 01 Identity | UUID primary identity; HCC stable path | S2 approved before schema |
| 02 HCC | preserve 2/2/3/5/8, no fabricated codes | capacity/check/cycle Postgres |
| 03 Attribute scopes | PRODUCT/SKU/LOT/SERVICE_SPEC/SERVICE_OFFER | SQL typed FK + revision S2 |
| 04 Units | explicit dimensional conversion; package quantity not inferred | financial/DB test |
| 05 Lot | future lot + FEFO with audit, no overwrite stock | concurrency S2 |
| 06 Service | scoped work spec + agreement & capacity, no inventory substitute | domain/API S2 |
| 07 Restricted | compliance gates and verified claims, fail closed | legal/owner review |
| 08 External maps | versioned mapping no internal ID replacement | licensing review |
| 09 Search | PG indexed facets/intent, no eager new search stack | EXPLAIN + load |
| 10 Language | multi-locale approved synonyms + ambiguity | language golden query |
| 11 ML | task-specific frozen feature schemas and asOf | temporal/replay tests |
| 12 Mixed basket | do not change one-order-one-seller, goods+service co-display only | separate order ADR |

**Статусы:** эти решения служат утверждённой **внутренней технической базой CAT-R1 RED-тестов**, но миграции и включение новых сценариев удерживаются в HOLD до S2, проверки данных/регуляторики и подтверждения PRODUCT_OWNER.

## 7. Поэтапный execution contract: не нарушать текущее поведение

**CAT-R1A (schema test-first):** attribute metadata revisions, canonical identity dedup, signed bounds, robust HCC; PostgreSQL RED → S2 → minimal GREEN. Исключить переписывание V1–V27.

**CAT-R1B (пилоты):** конкретные типизированные правила из Pilot Contract; no fake official HCC, load only operator-approved seeds into staging.

**CAT-R1C (Search):** typed facets и class intent с golden queries, published/listing ACL, cursor revisions. Подтвердить EXPLAIN на 10k/100k/1m и p95/p99 с настоящими параметрами.

**CAT-R1D (ML):** frozen feature registry per task, missing-aware features and cross-version replay, "eligibility before ranking"; модели только при доказанном улучшении baseline.

**CAT-R2+:** lot-backed availability/expiry and ServiceOrder: отдельные ADR, real PostgreSQL concurrence and UI E2E. Не подменять эти результаты seed-файлами.

## 8. RED Test Backlog: обязательные ошибки до нового GREEN

| ID | Expected behavior | Layer |
|---|---|---|
| CAT-RED-01 | STORAGE_GB=-1 reject, STORAGE_TEMP_C=-18 permitted under approved attribute bounds | JUnit+Postgres |
| CAT-RED-02 | HCC parent/cycle/type recode cannot break published references | Postgres |
| CAT-RED-03 | DATE 2026-02-30 reject; expired LOT cannot pass freshness policy | JUnit+Postgres |
| CAT-RED-04 | Apples 12 KG ≠ 1 BOX when net weight unknown; quantity comparison no invention | Domain+HTTP |
| CAT-RED-05 | Milk 1L and 900mL: unit conversion correct and product category/composition hard gates | Domain+Postgres |
| CAT-RED-06 | 'масло' food vs engine oil not silently exact synonyms | Search Postgres |
| CAT-RED-07 | SERVICE_SOCKET_INSTALL cannot reserve tracked physical stock or pretend it is SOCKET product | Domain/API/Postgres |
| CAT-RED-08 | Reviewer blocks false ORGANIC/ORIGIN verification without provenance | DB/HTTP security |
| CAT-RED-09 | Rule revision changes do not rewrite old bid/order facts or training feature vectors | Postgres/replay |
| CAT-RED-10 | Seller A cannot publish/edit Seller B's classified item | API security |
| CAT-RED-11 | DRAFT/UNLISTED/ARCHIVED not public when facets/relevance/relations are active | Search Postgres |
| CAT-RED-12 | Repeated approved import is idempotent; rejected batch has no published partial changes | Postgres |
| CAT-RED-13 | ML schema mismatch ⇒ deterministic fallback, unknown≠0, no post-asOf features | ML tests |
| CAT-RED-14 | Old QR/order/reservation/search regression remains green | Existing integration/E2E |

No claim these tests were executed in this phase. Their absence is intentional and tracked.

## 9. CAT-R0 Exit Assessment

**Выполнено:** repository contract inventory; подтверждённые кодовые разрывы; сопоставление с CAT-001/SEARCH/ML; выбран baseline решений; оформлены пилоты и RED backlog; SHA и CI-source evidence зафиксированы.

**Не выполнено / HOLD:** independent S2; catalog migration/seed; PostgreSQL tests for signed decimals/lot/service; real production legal check; multilingual semantic review; browser/device E2E; 5k and search load.

**Следующая разрешённая работа:** CAT-R1A — написать настоящие PostgreSQL RED-тесты под frozen pilot schema; получить независимый S2 Test Review, потом реализовать минимальный GREEN. PR #2 Draft и единая ветка сохраняются.
