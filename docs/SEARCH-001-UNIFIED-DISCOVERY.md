# SEARCH-001 — Единый поиск товаров и услуг, фотокаталог и поиск по изображению

**Статус:** DRAFT / ТЗ на развитие; SEARCH-S1 PostgreSQL Search и SEARCH-S2A медиаконтур (V22 + JPEG/PNG + minithumbnails + dev/test filesystem) добавлены в одну ветку и прошли CI; feature flags OFF, независимый S2 pending. Промышленный S3/S2B object store, React фотолента, Favorite/Push/Visual ещё НЕ реализованы. [SEARCH-S1 status](SEARCH-S1-IMPLEMENTATION-STATUS.md), [SEARCH-S2 status](SEARCH-S2-IMPLEMENTATION-STATUS.md). **Технический аудит:** CONDITIONAL S0, [SEARCH-AUD-001](SEARCH-AUD-001-TECHNICAL-REVIEW.md). **Нормативный handoff к RED-тестам:** [SEARCH-001 Implementation Contract](SEARCH-001-IMPLEMENTATION-CONTRACT.md). При противоречии технических деталей исполнения приложение Implementation Contract уточняет план реализации, не отменяя бизнес-правила этого ТЗ.
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
- **Проверка аудитом SA-01…SA-16:** базовая БД не содержит media/search/favorite/notification tables; V3 запрещает менять цену опубликованного SKU; существующий order_outbox_event имеет FK на customer_order и НЕ подходит для Catalog/Push событий; QR landing не содержит полного seller-specific checkout. Это необходимые ограничения до GREEN.

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

**SEARCH-FR-22.** Публичная страница SellerListing показывает реальные изображения/галерею, название, описание/категорию, проверенные сведения о продавце, SKU-варианты, единицу, опубликованную цену (когда есть); отсутствие данных не заполняется выдумками. Действие **«Запросить предложения»** явно выбирается покупателем после указания допустимой единицы/количества; для авторизованного сценария переиспользовать `/buy?productId={canonicalProductId}&unit={validatedMeasurementUnit}` и существующий защищённый BuyerFlow. **Это запрос рынку по CanonicalProduct, не заказ выбранного SellerListing:** текущий BuyerDemand HTTP contract не содержит listingId/sellerId. Отдельное действие «Заказать у этого продавца» нельзя показывать до реализации подтверждённого серверного SellerListing/SKU quote route. Конкретный выбранный Listing/SKU и источник перехода не должны теряться — контекст сохраняется валидированным контрактом, который проектируется отдельно и не даёт пользователю подменить seller/product. Если авторизация требуется, перенаправлять в OIDC, затем восстанавливать контекст. Никаких автоматических BuyerDemand, SellerBid acceptance или CustomerOrder на double click.

**SEARCH-FR-23.** Для SERVICE/DIGITAL вместо неприменимых товарных количеств и доставки отображать соответствующий существующему типу сценарий запроса. Недостоверная цена «от» не называется итоговой; она определяется только по опубликованным данным SellerSku. Публичная карточка по QR может по-прежнему предоставлять гостевой PurchaseLead как отдельное действие, не равное заказу.

## 5b. Памятка покупателя, категории избранного и Push-подписки (P0/P1)

**SEARCH-FR-24 (P0) — «Сохранить в памятку».** На каждой карточке фотоленты, в текстовых/фото-результатах и на странице конкретного SellerListing есть доступная кнопка с символом сердца и состояниями «Сохранить» / «Удалить из памятки». Нажатие не открывает товар и не инициирует заказ. Повторное добавление идемпотентно. Сохраняется **конкретный Listing продавца**, а не все объявления одного CanonicalProduct.

**SEARCH-FR-25 (P0) — автоматическая группировка.** Страница «Моя памятка» выводит собственные сохранённые объявления, сгруппированные **по действующей Category/HCC** связанной CanonicalProduct: «Электроника», «Инструменты», «Дом», «Услуги» и т. д. Ярлыки локализуются через HCC, а ключ группы — стабильный categoryCode. Иерархия допускает просмотр родительской категории с её потомками; одно сохранённое объявление не дублируется в общей выдаче. Порядок внутри категории — по времени добавления (с tie-breaker favorite ID); обязательны фильтры, cursor-пагинация и счётчики с учётом только разрешённых покупателю записей. Пользовательские папки/теги — отдельный этап, чтобы не дублировать HCC.

**SEARCH-FR-26 (P0) — идентичность и синхронизация.** Для авторизованного BUYER целевая сущность Favorite UNIQUE(buyerId,listingId), определяемый сервером buyerId (issuer+subject/active buyer link), работает одинаково в React и Flutter. Анонимный пользователь вправе временно сохранять объявления локально в браузере с честной маркировкой «только на этом устройстве»; перенос на аккаунт после входа — исключительно отдельным подтверждённым действием. Гость не получает персональных Push; недопустим доступ к чужой памятке (IDOR). Снимавшееся с публикации объявление сохраняется в истории личной памятки с безопасным статусом «Недоступно» до удаления владельцем, но приватное содержимое/изображения не раскрываются.

**SEARCH-FR-27 (P1) — отдельные интересы и слежение за ценой.** Сохранение в памятку **не подписывает автоматически** на рекламные/категорийные Push. Покупатель отдельно включает (а) слежение за конкретным сохранённым Listing/его SKU и проверенной публичной ценой, (б) «Новые предложения в этой HCC-категории», при необходимости с категорией-потомками и установленными фильтрами. Подписка на категорию явная, не выводится из просмотров или кликов; отключение категории не удаляет Favorites. **Функция price-watch заблокирована до появления версионированного repricing для PUBLIC SKU:** V3/V18 запрещают изменение цены опубликованного SKU. Изменение цены сообщается только при достоверном изменении опубликованной сопоставимой цены; не обещать итоговую экономию без проверенной доставки/сборов. «Снова в наличии» разрешено только при достоверном TRACKED stock и видимости его для покупателя.

**SEARCH-FR-28 (P1) — Push и Notification Center.** Целевой поток из DEC-27: domain event → утверждённый Generic Transactional Outbox → Notification projector → PostgreSQL user_notification → optional FCM/Web Push/Flutter transport. **Сейчас это целевая архитектура, а не существующий runtime.** Действующая таблица `order_outbox_event.aggregate_id` ссылается FK на `customer_order`, поэтому использовать её для Listing/Price/Favorite событий нельзя; требуется отдельный review общего доменного event contract, сохраняя Order Outbox. Внутренний Notification Center является источником истины, отсутствие/отказ Push не влияет на заказ и сохранение избранного. Разделить 3 независимые категории предпочтений: операционные заказы/предложения; price-watch сохранённых товаров; категория/рекомендации. Последние две по умолчанию **OFF** и требуют отдельного user opt-in; ОС/браузер дополнительно запрашивает permission для Web Push только по явному действию пользователя. Если permission запрещён/не поддерживается, доступны in-app уведомления, если разрешены пользовательскими настройками. Доставка push best-effort и не гарантируется на выключенных/запрещённых устройствах.

**SEARCH-FR-29 (P1) — контроль частоты и безопасности.** Для категорийных новинок по умолчанию предлагается ежедневный digest вместо Push на каждое объявление; частота и лимиты конфигурируемы, поддерживаются quiet hours, дедупликация eventId+recipient+kind и объединение пересекающихся Listing-watch и Category-watch событий. Доступны отказ от категории, отдельного товара, маркетинговых push целиком, отключение устройства/сессии. Push payload не содержит PII, JWT, телефон, адрес или приватные данные; ссылка открывает карточку через повторную авторизационную проверку/visibility-check и не создаёт Order. Отписка применяется до следующей доставки, включая уже поставленные в очередь события.

**SEARCH-FR-30 (P0/P1) — модель, API и права.** Переиспользовать запроектированные Favorite/FavoritePriceWatch, user_notification_preference, push_device, Notification Center; не создавать конкурирующий «закладочный» сервис. Предлагаемые owner-scoped API: `GET /api/v1/me/favorites?categoryCode=&cursor=&limit=`; `POST /api/v1/me/favorites` (listingId в body); `DELETE /api/v1/me/favorites/{listingId}`; `PUT /api/v1/me/favorites/{listingId}/price-watch`; `PUT /api/v1/me/category-watches/{hccCode}`; `GET/PUT /api/v1/me/notification-preferences`; существующие проектные `POST /api/v1/me/push-devices` и `DELETE /api/v1/me/push-devices/{id}`. Должен быть активный buyer-link и проверка owner; при отсутствии аутентификации на сервере запись запрещена. Для CategoryWatch отдельная запись (buyerId,hccCode,filters,enabled) с UNIQUE и подтверждением актуального HCC; подписка на неактивные/непубличные категории запрещена.

**SEARCH-FR-31 (P0) — интерфейс.** Навигация «Каталог / Моя памятка / Уведомления». На вкладке памятки категории представлены HCC-чипами со счётчиками; для каждого сохранённого объявления — обложка, проверенная опубликованная цена (если есть), продавец (если подтверждён), статус и управление слежением. Для категории — переключатель «Уведомлять о новых товарах» (выкл. по умолчанию), для карточки — «Сообщать о снижении цены» (выкл. по умолчанию). Кнопки независимы от double-click перехода в карточку; пользователю доступно понятное управление на Web и Flutter.

**Граница релиза:** SERVER favorites и группировка — самостоятельный P0/клиентский MVP, зависит от проверки Buyer Identity R2.3F; Push подписки/доставка — отдельный P1 с независимой квалификацией permission, согласий, Notification Center и Outbox. Реализация этих функций в текущем PR пока отсутствует.

## 6. Архитектурные решения и ответственность

- Модуль catalog/search: SearchTerm, SearchDictionaryService, SearchDocumentProjector, SearchQueryService, SearchSuggestionController; опциональные VisualSearchAdapter, MediaEmbeddingIndex. Не создавать другой Product, Listing, SKU, HCC или Order.
- PostgreSQL: Flyway V20+ **только после подтверждения последней миграции на HEAD**; pg_trgm и FTS в P0; pgvector в отдельной отложенной миграции P1. Существующие Flyway V1–V19 менять нельзя.
- Projected read model синхронизировать через committed events/outbox + идемпотентный worker и периодическую сверку; не делать сетевые image/ML вызовы внутри DB-транзакций заказа. Полный reindex — управляемая операция с метриками и возможностью rollback.
- REST: существующий GET /api/v1/public/catalog остаётся совместимым. NEW GET /api/v1/public/search/suggest?q=; GET /api/v1/public/catalog/search?q=&categoryCode=&...; GET /api/v1/public/catalog/feed?cursor=&limit=; GET /api/v1/public/catalog/{listingId}/media; POST /api/v1/public/search/by-image (P1, rate-limited); seller-only media upload/finalize/delete (P0). JSON контракты, статус-коды и авторизация фиксируются OpenAPI до GREEN.
- Типизация DTO: result listingId, canonical productId, sellerId только если публично допустим, coverUrl, imageCount, productType, publishedUnitPriceFromKzt? (не all-in price), QR token; cursor — opaque. Любая идентификация/конвертация в Demand — только через существующий защищённый /buy flow.
- Security: published-only SQL gate, IDOR тесты, presigned URL TTL, anti-CSRF where cookie-based, virus/file parser limits, signed cursors, max query length, rate limits, correct 4xx/5xx without details leakage.
- Auth flags: `zakaz.search.http.enabled`, `zakaz.catalog.media.enabled`, `zakaz.catalog.feed.enabled`, `zakaz.favorites.http.enabled`, `zakaz.visual.search.enabled` — default OFF до отдельной квалификации. Для каждого нового public endpoint расширить точечный GET allowlist `ApiSecurityConfig`, а для owner-scoped seller/buyer API — обязательные JWT scope + SellerIdentityService/BuyerIdentityService. Текущая denyAll без этого блокирует новые URL. Публичная загрузка изображений невозможна.
- Первоначальная сортировка cursor feed строится по `lower(p.canonical_name), listingId`; `publishedAt` сейчас отсутствует в `SellerListing`, сортировка по нему запрещена до отдельной миграции. Цена после V3/V18 неизменна пока Listing PUBLIC; `price-watch` нельзя включать без законного versioned repricing.
- Первый индекс SearchDocument допускается обновлять в PostgreSQL-транзакции публикации/изменения словаря с idempotent backfill: существующий Order Outbox нельзя переиспользовать для каталога без общего outbox ADR.

## 7. Порядок реализации

| Slice | Выход | Зависимости | Gate |
| --- | --- | --- | --- |
| SEARCH-S0 | согласованный SQL/REST/ACL contract, RED oracles, [audit](SEARCH-AUD-001-TECHNICAL-REVIEW.md) | действующая Catalog/Buyer/QR/Outbox реализация | независимый S2 review RED-тестов |
| SEARCH-S1 | словарь SearchTerm + HCC relations, PG FTS/pg_trgm, поиск и подсказки | PostgreSQL/Flyway V20+ | PostgreSQL RED→GREEN, RU/KK/EN/ZH, PRIVATE не раскрываются |
| SEARCH-S2 | seller ListingMedia, storage adapter, thumbnail, публикационная политика | SellerIdentityService, S3-compatible provider | MIME/EXIF, ownership, media visibility |
| SEARCH-S3 | React PhotoFeed/QR card, cursor, double click + single-tap fallback | S1/S2, PublicCatalog | Playwright/Flutter API E2E 10+ страниц, корректный рыночный BuyerDemand |
| SEARCH-S4 | серверные BuyerFavorites, «Моя памятка» по HCC, Web/Flutter sync | BuyerIdentityService, R2.3F staging | PostgreSQL owner/unique, guest merge consent, no push by default |
| SEARCH-S5 | revisioned price watch + CategoryWatch + Notification Center + general outbox | отдельный versioned pricing design, S4, notification S2 | consent/dedup/quiet-hours, replay, real price event |
| SEARCH-S6 | VisualSearch + pgvector + modelVersion | S2/S3, quality-labelled images | Recall@10, precision, latency, licensed model |
| SEARCH-S7 | Photo-to-Service/Smart Demand extensions | S6, Product-to-Demand contract review | явное подтверждение типа услуги/товара, no auto-order |

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

| SEARCH-T21 | Авторизованный BUYER сохраняет объявление и повторяет запрос | Одна запись Favorite, конкретный SellerListing, без создания заказа |
| SEARCH-T22 | Сохранены товары из 3 HCC-категорий и одна услуга | Автогруппировка по HCC/потомкам, одинаковая выборка на Web/Flutter, без дублирования |
| SEARCH-T23 | Гость сохраняет и затем входит в аккаунт | До входа только локально, импорт после явного согласия, без чужих избранных |
| SEARCH-T24 | Покупатель A запрашивает памятку/подписки B | 403/404, нет IDOR и PII |
| SEARCH-T25 | Товар добавлен в памятку, разрешения Push нет | Ни одного маркетингового Push; операция сохранения успешна |
| SEARCH-T26 | Включён watch цены, опубликованная цена действительно снижена | Одно внутреннее уведомление и не более одного разрешённого Push на событие; источник цены проверен |
| SEARCH-T27 | Подписка на HCC-категорию включена; появляются PUBLIC и UNLISTED | Digest только по разрешённым PUBLIC, отсутствуют утечки и дубли |
| SEARCH-T28 | Пользователь отключает Push/Watch перед отправкой или отзывает устройство | Очередь проверяет актуальные preferences, доставка отменяется, транзакции заказов не блокируются |
| SEARCH-T29 | Архивирование сохранённого товара и переход из памятки | Статус «Недоступно», приватный content/media не раскрывается и заказ не создаётся |
| SEARCH-T30 | Карточка открывается double-click при наличии кнопки «Сохранить» | Только double-click карточки ведёт в SellerListing, сердечко работает отдельно |
| SEARCH-T31 | Сервис FCM/WebPush недоступен и Outbox переигран 3 раза | Favorites/заказы работают; 1 in-app notification, retry ограничен, нет fan-out дублей |

**Качество и производительность:** определить пилотный набор запросов и фото до выбора численных SLO. Обязательные метрики Search Success@10, Recall@10 (visual), zero-results rate, CTR, p95/p99 по search/feed, media bandwidth, query plan, false positives, no stale private results. Не утверждать точность или скорость без замеров.

## 8a. Уточнения аудита, обязательные до GREEN

- **SA-01 Outbox:** в `V10` `order_outbox_event.aggregate_id REFERENCES customer_order(id)`, а sink обслуживает лишь Order; это не универсальный event broker. Для S1 SearchDocument допускается transactional same-DB update, для Push нужно отдельно утвердить общий доменный event contract. Не использовать Order Outbox для Catalog events.
- **SA-02 price-watch:** `V3` DB trigger запрещает менять `catalog_sku` не-DRAFT. Не обещать «уведомление о снижении цены» до S5 с аудируемым versioned repricing; `SellerBid` / `CustomerOrder` snapshots не менять.
- **SA-03 search→order:** `/q/{token}` отображает конкретный SellerListing, а `/buy?productId=&unit=` создаёт рыночный `BuyerDemand` без закрепления продавца. UI не должен называть такой переход «заказом у этого продавца». Seller-specific route — отдельное решение с проверкой listingId/skuId/quote на сервере.
- **SA-04 данность:** нет SellerMedia/upload pipeline, Favorite/Notification endpoints, seller names/city/rating и `publishedAt` в текущем catalog DTO. Любой UI без проверенных полей использует честную маркировку; существующие PUBLIC without photo должны продолжить работать. Новое правило 3–5 фото для новых объявлений требует phased product sign-off.
- **SA-05 Auth:** `ApiSecurityConfig` denyAll и отсутствующие permissions/feature flags должны быть исправлены вместе с каждым endpoint; buyerId/sellerId нельзя брать из body. `GET /catalog` и `/q` должны остаться совместимы; существующие release gates независимы.
- **SA-06 Development contract:** [SEARCH-001 Implementation Contract](SEARCH-001-IMPLEMENTATION-CONTRACT.md) фиксирует предлагаемую схему V20+, API, entity ownership, keyset и тесты. Приоритет: S1 Search → S2 Media → S3 PhotoFeed → S4 Favorites → S5 notifications → S6 Visual Search. Переход к GREEN — только после настоящих RED и проверки независимым S2.

## 9. Definition of Done и изменения документации

- RED PostgreSQL Testcontainers, MockMvc security, React component tests, Playwright Web E2E, Flutter API/UX tests для соответствующих slices; полная трассировка SEARCH-FR ↔ SEARCH-T.
- SQL EXPLAIN ANALYZE на синтетических и реалистичных объёмах, bounded SQL и keyset, запрет N+1, миграции/rollback репетиция; тестирование обновления/удаления media.
- Backward compatible существующие public QR и Catalog API; no duplicate payment/order/reservation engine; независимый S2 review exact SHA перед promotion.
- Проверка owner-scoped Favorite UNIQUE, кросс-клиентской синхронизации по HCC, запрета push без отдельного consent, корректности notify delivery/dedup и отписки в момент отправки.
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
SEARCH-DEC-09: включать ли наблюдение за конкретным SKU или всем Listing — выбрать безопасную точную модель цены и валюты.
SEARCH-DEC-10: политика daily digest, quiet hours, настройки per-device Web/Flutter push и пороги снижения цены.
SEARCH-DEC-11: политика отображения сохранённых приватных/архивированных карточек без утечки информации.
