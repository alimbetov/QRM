# SEARCH-001 — Единый поиск товаров и услуг, фотокаталог и поиск по изображению

**Статус:** DRAFT / техническое задание на доработку, НЕ реализовано и НЕ означает S2 APPROVE.
**Дата:** 2026-11-13.
**Репозиторий:** alimbetov/QRM; единая рабочая ветка docs/adr-002-jpa-architecture-review.
**Приоритет источников:** docs/REQUIREMENTS.md v2.0; ADR-002 MVC+JPA; ADR-004 Hybrid Intelligence; DEC-BUYER-WEB-001; действующие Flyway/Java-контракты. Это дополнение, а не замена.
**Нумерация:** SEARCH-001 — постоянный feature ID. В прежних обсуждениях назывался R2.4 Search; этот номер уже использован в master-TZ для 5K load gate, поэтому релизное обозначение требует отдельного решения.

## 1. Цель и бизнес-результат

Покупатель на Web и впоследствии Flutter находит опубликованные товары и услуги через: (а) обычный текст; (б) словарь синонимов, разговорных названий и переводов; (в) HCC-категории и типизированные характеристики; (г) фото; видит единую визуальную ленту объявлений, перелистывает фотографии, загружает следующие карточки без ручного переключения страниц. Результаты поиска — рекомендации/кандидаты, а не факт совместимости, наличия, цены доставки или согласованного заказа.

**Запрещено:** Elasticsearch как обязательная зависимость; новый отдельный поисковый микросервис в MVP; переписывание CustomerOrder/InventoryReservation/BuyerDemand; автоматическое создание продукта или заказа из AI-результата; выдуманные цены, отзывы, город, складские остатки и доставку.

## 2. Фактическая исходная реализация

- Java 21, Spring MVC, JPA, Flyway, PostgreSQL; Category/HccPath (20-значный код), AttributeDefinition, CanonicalProduct, SellerListing, SellerSku.
- PublicCatalogController /api/v1/public/catalog; PublicCatalogReadService: параметризованный lower(canonical_name) LIKE, categoryCode, page 0..1000, size 1..24; только Listing PUBLIC + активная Category + QR PublicLink; QR и каталог закрыты feature flags по умолчанию.
- React web/src/main.tsx выводит LiveCatalog на /; нет реального photo feed/media search в проверенной ветке; отдельный App.tsx содержит устаревшее локальное демо с ненастоящими карточками.
- BuyerFlow требует действительной OIDC/Buyer identity и существующий productId/unit; серверный BuyerDemand/SellerBid/Order остаётся транзакционным source of truth.
- R2.3F: внешние SMTP/браузер/device E2E, независимый S2, 5K нагрузка не подтверждены; SEARCH-001 не снимает эти ограничения.

## 3. Функциональные требования P0 — словарь и категории

**SEARCH-FR-01.** Пользователь ищет без авторизации только PUBLIC-объявления по названию, частично введённому слову и синонимам, например «болгарка» → «угловая шлифмашина»; существующие ограничения публикации обязательны на SQL-уровне. UNLISTED доступен только по прямой ссылке, DRAFT и ARCHIVED — никогда в общей выдаче.

**SEARCH-FR-02.** Управляемый SearchTerm {id, term, normalizedTerm, languageTag, status, revision} и SearchTermCategory {termId, categoryId, relationType, weight}. languageTag допускает kk, ru, en, zh; нормы языка и транслитерации/морфологии должны тестироваться отдельно, нельзя заявлять одинаковое stemming-качество для всех четырёх языков.

**SEARCH-FR-03.** Смысл отношений строго разделён: SYNONYM (эквивалентное название), CATEGORY_TERM (название категории), RELATED (связанные товары, но не замена), SERVICE_INTENT (вероятная услуга), ALTERNATIVE (только при подтверждённой совместимости и explicit opt-in). RELATED не расширяет результаты точных совпадений без видимой маркировки/отдельной группы.

**SEARCH-FR-04.** Система использует существующий HCC (Category.id/hccCode/parent/type), не создаёт конкурирующий справочник категорий. Слова «плитка», «укладка плитки», «клей для плитки» должны вести к различным релевантным категориям/типам, без ложного равенства товаров и услуг.

**SEARCH-FR-05.** Предложения автодополнения возвращают термин, категорию, язык и тип намерения, с контролируемой модерацией и версионированием. Администрирование словаря — под полномочиями PLATFORM_ADMIN/CONTENT_ADMIN только после утверждения ролевой модели; создание слов по пользовательскому тексту без модерации запрещено.

**SEARCH-FR-06.** Поиск сочетает PostgreSQL Full Text Search, индексированную нормализованную строку и pg_trgm (опечатки). Для kk/zh использовать проверенные словарные сопоставления, fallback n-grams/alias при подтверждённых тестах. Search document строится из публичных канонических фактов и атрибутов, не подменяет Catalog.

**SEARCH-FR-07.** Приоритет ранжирования: точная каноническая идентичность и явные атрибуты → утверждённый синоним → HCC-категория → опечатка → RELATED как отдельный блок. Ранжирование синонимов/текста не ослабляет hard-атрибуты и проверку публикации.

**SEARCH-FR-08.** Сервер поддерживает productType, categoryCode, разрешённые типизированные SKU-фильтры, диапазон опубликованной цены, продавца и проверенный город/зону обслуживания только при наличии достоверного источника; отсутствующие факты не выводить как реальные. Имена SQL-полей и sort — только allowlist.

## 4. Функциональные требования P0 — медиа и фотолента

**SEARCH-FR-09.** ListingMedia привязан к SellerListing (не к CanonicalProduct без основания). Метаданные: mediaId, listingId, objectKey, contentType, checksum, position, cover, status, width, height, createdAt. Изображения в S3-compatible storage; PostgreSQL хранит только метаданные. Реальное фото не может ссылаться на чужое объявление.

**SEARCH-FR-10.** Загрузка фото через seller-only авторизованный контракт, с проверкой membership/ownership и публикационного состояния; лимиты размера/числа/разрешения конфигурируемы, исходное MIME проверять по bytes, запрещать активный контент, снимать EXIF/геометаданные, делать безопасные WebP/AVIF/JPEG thumbnails. Сначала определить политику для ранее опубликованных карточек без фото; нельзя сломать существующий каталог внезапным NOT NULL/mandatory-media.

**SEARCH-FR-11.** В общей ленте один Listing = одна карточка; обложка 1:1 или 4:5, горизонтальная галерея остальных фото при открытии; товар/услуга, продавец и цена отображаются только при наличии опубликованных проверенных данных. Если цена не известна — «уточняется», никакого фиктивного рейтинга/доставки.

**SEARCH-FR-12.** Серверная cursor/keyset-пагинация, по умолчанию 24 карточки (максимум 48) и стабильный tie-breaker listingId. Cursor включает версию/хеш критериев поиска, порядок сортировки, score/ключ последнего объявления, expiry; подписывается сервером и отклоняется при подмене/смене фильтров. Для сравнимости страниц использовать фиксированный read-window/revision или ограниченный snapshot; определить семантику при изменениях публикаций и индекса.

**SEARCH-FR-13.** React: desktop 3–4 колонки, mobile Web 2; IntersectionObserver с отменой устаревших запросов, lazy image loading, skeleton/error/empty, retry, fallback при отсутствии фото; state URL + scroll restoration. Без дубликатов при скролле/возврате, ограничения глубины пагинации и backpressure.

**SEARCH-FR-14.** Обложки выдавать с ограниченными сроками доступа через доверенный image proxy или публичные неизменяемые производные только для PUBLIC; приватные originals недоступны. При ARCHIVED/UNLISTED/отзыве изображения перестают попадать в публичную выдачу и публичную доставку согласно security-policy, CDN invalidation — отдельно.

## 5. Функциональные требования P1 — поиск по фотографии

**SEARCH-FR-15.** Веб-камера (при поддержке браузером) либо загрузка файла → серверная проверка размера/формата → временное безопасное хранение/обработка → image encoder → embedding → pgvector similarity → HCC/category filters → единая фотолента. Все inference результаты имеют modelVersion, similarity и статус «визуально похоже», а не «тот же SKU».

**SEARCH-FR-16.** pgvector и ONNX inference подключать только после измерения качества/ресурсов модели на собственном фото-наборе. Размерность vector должна совпадать с конкретной modelVersion; перепостроение индекса версионированно, без смешения embeddings разных моделей. SQL поиск ограничен publishable media и Listing visibility.

**SEARCH-FR-17.** Изображение с несколькими предметами — пользователь подтверждает объект/кроп или категорию. Поиск услуги по изображению формирует предположение о виде работы; диагноз, стоимость и нужные материалы из фотографии не гарантируются и требуют подтверждения.

**SEARCH-FR-18.** Режим без результатов предлагает изменить категорию/поиск или после авторизации вручную создать BuyerDemand только для валидированного CanonicalProduct. Новый «photo-to-demand без productId» — отдельное будущее изменение схемы с S2/security review, не часть MVP.

**SEARCH-FR-19.** Фото покупателя не использовать для обучения, сохранения или повторной публикации без явного, отдельного согласия. Временные объекты очищаются по TTL; не логировать image bytes/EXIF/PII. Rate limit, bot protection и квоты на публичный inference.

## 5a. Переход из поисковой фотоленты в карточку товара и оформление (P0)

**SEARCH-FR-20.** На Web **двойной щелчок мышью** по изображению или поверхности найденного товара в ленте открывает страницу **конкретного SellerListing**. На сенсорном экране **двойное касание** выполняет аналогичный переход; поскольку double tap может конфликтовать с системным масштабированием и не всегда обнаруживается браузером, обязательна видимая кнопка/ссылка «Подробнее», открывающая ту же страницу одним нажатием. Клавиатурный Enter на сфокусированной ссылке также открывает карточку. Поведение идентично для текстовых, категорийных и фото-результатов. Любые контролы «Избранное», «Сравнить», перелистывание фото и выбор SKU не должны случайно запускать навигацию двойным щелчком; взаимодействия доступны для touch, keyboard и screen reader.

**SEARCH-FR-21.** Для P0 переиспользовать существующую публичную страницу `/q/{qrToken}` как landing карточки продавца по валидному token из результата (без изменения идентичности/атрибуции QR). Отдельный человекочитаемый `/listing/{listingId}` допускается только после решения о публичном canonical route и безопасной резолюции Listing; нельзя пытаться открыть `/buy` по двойному нажатию и нельзя смешивать одноимённые товары разных продавцов. Ссылка на карточку доступна без авторизации только для разрешённого PUBLIC; снятые с публикации/заблокированные возвращают корректный недоступный статус. При возврате сохраняются запрос, фильтры, позиция скролла и уже загруженные страницы фотоленты.

**SEARCH-FR-22.** Публичная страница SellerListing показывает реальные изображения/галерею, название, описание/категорию, проверенные сведения о продавце, SKU-варианты, единицу, опубликованную цену (когда есть); отсутствие данных не заполняется выдумками. Действие **«Заказать / Запросить предложение»** явно выбирается покупателем после указания SKU/количества; для авторизованного сценария переиспользовать `/buy?productId={canonicalProductId}&unit={validatedMeasurementUnit}` и существующий защищённый BuyerFlow. Конкретный выбранный Listing/SKU и источник перехода не должны теряться — контекст сохраняется валидированным контрактом, который проектируется отдельно и не даёт пользователю подменить seller/product. Если авторизация требуется, перенаправлять в OIDC, затем восстанавливать контекст. Никаких автоматических BuyerDemand, SellerBid acceptance или CustomerOrder на double click.

**SEARCH-FR-23.** Для SERVICE/DIGITAL вместо неприменимых товарных количеств и доставки отображать соответствующий существующему типу сценарий запроса. Недостоверная цена «от» не называется итоговой; она определяется только по опубликованным данным SellerSku. Публичная карточка по QR может по-прежнему предоставлять гостевой PurchaseLead как отдельное действие, не равное заказу.

## 6. Архитектурные решения и ответственность

- Модуль catalog/search: SearchTerm, SearchDictionaryService, SearchDocumentProjector, SearchQueryService, SearchSuggestionController; опциональные VisualSearchAdapter, MediaEmbeddingIndex. Не создавать другой Product, Listing, SKU, HCC или Order.
- PostgreSQL: Flyway V20+ **только после подтверждения последней миграции на HEAD**; pg_trgm и FTS в P0; pgvector в отдельной отложенной миграции P1. Существующие Flyway V1–V19 менять нельзя.
- Projected read model синхронизировать через committed events/outbox + идемпотентный worker и периодическую сверку; не делать сетевые image/ML вызовы внутри DB-транзакций заказа. Полный reindex — управляемая операция с метриками и возможностью rollback.
- REST: существующий GET /api/v1/public/catalog остаётся совместимым. NEW GET /api/v1/public/search/suggest?q=; GET /api/v1/public/catalog/search?q=&categoryCode=&...; GET /api/v1/public/catalog/feed?cursor=&limit=; GET /api/v1/public/catalog/{listingId}/media; POST /api/v1/public/search/by-image (P1, rate-limited); seller-only media upload/finalize/delete (P0). JSON контракты, статус-коды и авторизация фиксируются OpenAPI до GREEN.
- Типизация DTO: result listingId, canonical productId, sellerId только если публично допустим, coverUrl, imageCount, productType, publishedUnitPriceFromKzt? (не all-in price), QR token; cursor — opaque. Любая идентификация/конвертация в Demand — только через существующий защищённый /buy flow.
- Security: published-only SQL gate, IDOR тесты, presigned URL TTL, anti-CSRF where cookie-based, virus/file parser limits, signed cursors, max query length, rate limits, correct 4xx/5xx without details leakage.
- Auth flags: search.http.enabled, catalog.media.enabled, catalog.feed.enabled, visual.search.enabled — default OFF до отдельной квалификации. Публичная загрузка изображений невозможна.

## 7. Порядок реализации

| Slice | Выход | Зависимости | Gate |
| --- | --- | --- | --- |
| SEARCH-S0 | контракт/ERD/OpenAPI + RED oracles | существующие HCC и R2.3C | независимое S2 review проекта |
| SEARCH-S1 | SearchTerm/relations, FTS/pg_trgm, словарные подсказки | PostgreSQL/Flyway | PostgreSQL RED→GREEN, RU/KK/EN/ZH выборка |
| SEARCH-S2 | Seller ListingMedia, thumbnails, безопасная публикация | Seller JWT ACL, S3 provider | ownership, media abuse, lifecycle |
| SEARCH-S3 | React PhotoFeed, media gallery, cursor pagination | S1/S2 | браузер E2E 10+ страниц без дублей |
| SEARCH-S4 | VisualSearch + pgvector + modelVersion | S2/S3, качественный датасет | Recall@10, precision, latency, cost |
| SEARCH-S5 | Photo-to-Service/Smart Demand integration | S4, Buyer E2E | пользователь подтверждает вид услуги/товар |

R2.3F SMTP/реальная регистрация/стейджинг, независимый S2 и нагрузочный gate 5K сохраняют отдельные release blockers. Часть публичного read-only поиска допускается разрабатывать параллельно, но не объявлять продакшен-готовность.

## 8. Исполняемые критерии приёмки (RED)

| ID | Проверка | Ожидаемый результат |
| --- | --- | --- |
| SEARCH-T01 | «болгарка», «УШМ» и соответствующие локализованные aliases | Возвращаются объявления одной утверждённой HCC-категории |
| SEARCH-T02 | RELATED термин «плиточный клей» для «плитка» | Не показывается как точное совпадение плитки |
| SEARCH-T03 | Запрос с опечаткой и 4 локалями | Предсказуемые подтверждённые совпадения, без гарантий универсального stemming |
| SEARCH-T04 | PUBLIC/UNLISTED/DRAFT/ARCHIVED в смешанной выборке | Только PUBLIC; UNLISTED — лишь по прямому URL |
| SEARCH-T05 | Seller загружает медиа в чужой Listing | 403/404, объект не раскрывается |
| SEARCH-T06 | SVG/polyglot/decompression bomb/EXIF/GPS | Unsafe input rejected, EXIF removed in derivatives |
| SEARCH-T07 | 10+ страниц неизменной выборки и равный sort score | Нет пропуска/дубликатов Listing |
| SEARCH-T08 | Изменение фильтра при наличии cursor | Cursor отклонён, начинается новая сессия |
| SEARCH-T09 | Удаление или архивирование объявления | Фото и объявление исчезают из публичных каналов по политике |
| SEARCH-T10 | Много SKU с разными published prices | Кладём корректную цену «от», не приписываем доставку |
| SEARCH-T11 | Фото визуально похоже, но другой SKU | Никакой auto-accept или ложной совместимости |
| SEARCH-T12 | Image model unavailable | Текстовый поиск работает, деградация управляемая |
| SEARCH-T13 | Доступ к чужому demand/order через поиск | Нет обхода scope/ownership |
| SEARCH-T14 | Reindex/outbox retry и duplicate events | Идемпотентные индексные обновления |
| SEARCH-T15 | 5000 concurrent synthetic search/scroll users | p95/p99, RPS, PostgreSQL CPU/locks, memory, errors измерены и задокументированы отдельно от order benchmark |

| SEARCH-T16 | Desktop: двойной щелчок по результату text/HCC/photo feed | Открывается точный SellerListing через публичную карточку; никакого заказа в БД |
| SEARCH-T17 | Mobile: двойное касание, обычный тап «Подробнее»; клавиатура Enter | Одна и та же корректная карточка; нет случайной навигации из избранного/галереи |
| SEARCH-T18 | В карточке выбор SKU и явное нажатие «Заказать», OIDC и возврат | Контекст продукта/единицы валидирован; BuyerFlow работает только после явного действия |
| SEARCH-T19 | Возврат из карточки после 5+ подгруженных страниц | Текст, фильтры, cursor и scroll восстановлены, нет двойных результатов |
| SEARCH-T20 | Товар скрыт/архивирован между поиском и двойным нажатием | Нельзя открыть приватные данные или сформировать заказ; корректная страница недоступности |

**Качество и производительность:** определить пилотный набор запросов и фото до выбора численных SLO. Обязательные метрики Search Success@10, Recall@10 (visual), zero-results rate, CTR, p95/p99 по search/feed, media bandwidth, query plan, false positives, no stale private results. Не утверждать точность или скорость без замеров.

## 9. Definition of Done и изменения документации

- RED PostgreSQL Testcontainers, MockMvc security, React component tests, Playwright Web E2E, Flutter API/UX tests для соответствующих slices; полная трассировка SEARCH-FR ↔ SEARCH-T.
- SQL EXPLAIN ANALYZE на синтетических и реалистичных объёмах, bounded SQL и keyset, запрет N+1, миграции/rollback репетиция; тестирование обновления/удаления media.
- Backward compatible существующие public QR и Catalog API; no duplicate payment/order/reservation engine; независимый S2 review exact SHA перед promotion.
- Search-to-Listing-to-BuyerFlow: Playwright desktop double-click, touch double-tap и single-click fallback, keyboard accessibility, visibility/ownership и восстановление search/feed state; Product/SellerListing/SKU контекст проверяется на сервере.
- Обновить мастер-ТЗ ссылкой на этот DRAFT; записать решения об upload limits, publication min images, media deletion/retention, synonym curation, sort/cursor semantics и выбор модели до GREEN.

## 10. Открытые решения

SEARCH-DEC-01: количество обязательных фото для NEW PUBLIC (не ломать старые карточки).
SEARCH-DEC-02: S3 provider и правила public derivatives/CDN invalidation.
SEARCH-DEC-03: exact limits, allowed formats, storage retention, image privacy.
SEARCH-DEC-04: рейтинг и реклама/продвижение в ленте — отдельно от органической релевантности.
SEARCH-DEC-05: язык токенизации/построение словаря KK/ZH, ручная модерация.
SEARCH-DEC-06: стабильная модель курсора при concurrent publication/depublication.
SEARCH-DEC-07: модель/лицензия/inference cost и quality threshold для Visual Search.
SEARCH-DEC-08: релизная нумерация: feature SEARCH-001 vs историческое «R2.4» нагрузочного gate.
