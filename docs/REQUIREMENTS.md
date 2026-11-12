# ZAKAZ / QRM — мастер-ТЗ v2.0: QR-first marketplace + buyer-led commerce

**Дата консолидации:** 2026-11-12. **Статус:** согласованная продуктовая концепция и инженерный baseline, **не акт готовности продукта**. Настоящая редакция заменяет противоречащие положения прежнего v1.4 и является основным источником требований. Исторический текст: [archive/REQUIREMENTS-v1.4-2026-11-12.md](archive/REQUIREMENTS-v1.4-2026-11-12.md). Детальные решения и незавершённые S2-контракты перечислены ниже.

**Название:** ZAKAZ, кодовое имя репозитория QRM. **Рынок:** Казахстан. **Денежная единица:** KZT. **Языки:** KK/RU/EN/ZH с fallback. **Платёжная модель:** расчёты напрямую между покупателем и продавцом, платформа не является эквайером и не заявляет об оплате без доказательств.

## 1. Product vision и принцип приоритетов

ZAKAZ объединяет QR-переход на предложение конкретного продавца и общий каталог товаров/услуг разных продавцов. Покупатель может сохранить интересующие предложения, выбрать несколько SKU, отправить запрос рынку, сравнить конкурирующие условия, предложить цену, явно создать заказ и подтвердить получение. Продавец управляет предложениями, складами, переговорами, исполнением и аналитикой.

**Три differentiating направления:** (1) **Reverse Marketplace**: покупатель формулирует спрос, подходящие продавцы конкурируют; (2) **Smart Bargaining**: единый пакет выбранных позиций и версионированные встречные предложения; (3) **Smart Deal Finder**: сравнение сопоставимых товаров с *полной проверенной стоимостью*, предпочтениями и ограниченными рекомендательными уведомлениями.

Приоритет — рабочий end-to-end trade flow и измеримый пилот; не реализовывать одновременно все проекты данных, логистики и ML.

## 2. Участники, идентичность, доступ

- **GUEST**: просмотр PUBLIC каталога и UNLISTED по прямой ссылке/QR, категории/фильтры/сравнение, Web callback **PurchaseLead** с отдельным согласием; анонимный запрос не создаёт `CustomerOrder` или `BuyerDemand`.
- **BUYER**: единый аккаунт для Flutter и Web; избранное и price watch, buyer request, предложения продавцов, переговоры, явное оформление заказа, отслеживание/получение, претензии, отзывы. **На первом Web-этапе** Web-заказ/переговоры ещё не включены; целевые транзакционные операции сначала выполняются во Flutter.
- **SELLER**: профиль, свои Listings/SKUs, склады/точки выдачи, остатки, участие в запросах покупателей, SellerBids, Inbox (leads/offers/orders), выдача/отгрузка, аналитика.
- **ADMIN**: категории/фасеты, модерация, защита от злоупотреблений, аудит.
- Один User может иметь роли BUYER и SELLER. Роли и привязки объектов проверяет Backend на каждом запросе, не UI.

Телефон — **неподтверждённый alias для логина**, регистрация/обычный вход без SMS. Email после подтверждения является предпочтительным каналом восстановления. `PhoneVerificationProvider` и SMS допустимы **только в безопасном recovery flow** с независимым подтверждением личности; один лишь неподтверждённый номер никогда не даёт доступ к чужому аккаунту. Пароли хешируются, сессии/refresh revocation и rate limiting обязательны. Детали восстановления без email, сроки хранения и антиабьюз требуют S2.

## 3. Web Buyer + Flutter: каталог, избранное, фильтры

**React Web**: общий каталог по категориям, предложения других продавцов независимо от источника QR, строка поиска, фильтры города/продавца/цены, рейтинг, общие типизированные характеристики категорий, сортировка, стабильная пагинация с серверными пределами; публичный просмотр карточек и профилей продавцов. У товара может быть несколько предложений от разных продавцов; Listing и Product различаются.

- `Category` — управляемая иерархия; `Product` — нормализованный предмет/тип; `Listing` — условия конкретного продавца; `ProductSku` — 1..N вариантов внутри Listing.
- Категорийные фасеты определяются централизованно: `CategoryAttributeDefinition` (тип ENUM/NUMBER/BOOLEAN/TEXT, список/диапазон, единица, индексируемость, версия). Значения — `ListingAttributeValue` либо `SkuAttributeValue`; выбрать единую authoritative схему на S2, не дублировать факты. Примеры: телефон — бренд/память; услуга — специализация/выезд; мебель — материал/размеры.
- Результаты публичного каталога включают только PUBLIC; UNLISTED доступны через ссылку, ARCHIVED и DRAFT исключены. Сортировка whitelist, серверная валидация фильтров, стабильный tie-break по ID, защита от неограниченной выборки и SQL injection.
- **Избранное Web + Flutter**: целевая серверная `Favorite UNIQUE(userId,listingId)` и `FavoritePriceWatch`, общие между клиентами. Гостевые закладки могут быть временно локальными, без обещания синхронизации; импорт после логина — отдельное явное действие.
- В первой реализации React каталог содержит **демоданные**, localStorage-избранное, рабочие локальные фильтры/пагинацию. Реальный PostgreSQL API, авторизация/синхронизация — отдельный следующий этап.
- Любые изменения цены на экране предварительные; итог рассчитывает Backend по выбранному SKU, количеству, акциям, сроку действия и складу/региону.

## 4. Продавец, Listing и публичный QR

SellerProfile: nickname/publicSlug/displayName, необязательный logoMediaId, контент, status. Публикация Listing требует хотя бы одну локаль (с fallback), 3–5 проверенных изображений, валидные SKU/цены/единицы/тип, права продавца; опциональна внешняя YouTube-ссылка с allowlist/нормализацией. PHYSICAL/SERVICE/DIGITAL имеют разные условия выдачи и завершения.

Listing: DRAFT / PUBLIC / UNLISTED / ARCHIVED. QR карточки/продавца несёт постоянный HTTPS URL без цены и секрета; source attribution отделено от статуса заказа. Web callback `PurchaseLead` — заявка на обратный звонок, **не** `BuyerDemand`, **не** переговоры и **не** `CustomerOrder`. Контакт заявителя показывается только уполномоченному продавцу после согласия и защищённого доступа.

Seller Inbox: LEAD / NEGOTIATION / ORDER и позже BUYER_DEMAND_BID; отдельные состояния UNREAD/READ/BOOKMARKED, не смешивать с бизнес-статусом сделки. Cursor pagination, owner ACL и серверное unread-поведение.

## 5. Цены и Smart Bargaining

- Цены/количества — BigDecimal; KZT и отдельная политика округления (юридическая проверка РК перед production); `PricingTier` и `DiscountCampaign` применяются сервером к SKU с однозначным приоритетом и не допускают противоречащих активных цен.
- Покупатель выбирает **галочками 1..N SKU**, указывает qty; неотмеченное исключено из payload и `OrderLine`. UI checkbox — не отдельная JPA entity.
- Два действия: **купить по серверной подтверждённой цене** либо **предложить цену** по выбранным позициям.
- NegotiationSession → immutable PriceOffer revisions → 1..N PriceOfferLine. Комплект каждого раунда полностью определён (SKU, qty, unit, public/proposed/agreed prices, currency, version, city/region preliminary snapshot). До 5 раундов встречных предложений на сессию согласно DEC-04A. Предложение нельзя принять после expiry, отказа либо финализации.
- **AGREED не создаёт заказ автоматически**. Авторизованный покупатель выполняет явную идемпотентную конверсию в **один** CustomerOrder(PLACED); аналогичный idempotent flow для обычной цены.
- Текущая MVP-гипотеза: **один seller на CustomerOrder**, 1..N OrderLine. Раздельные продавцы — разные заказы, даже если сравнение в одном интерфейсе.
- Любое изменение состава, количества, цены/срока/существенного origin/delivery term после оффера требует явного нового акцепта. Нельзя доверять цене/остаткам из браузера.

## 6. Reverse Marketplace (новое P0-проектирование)

**BuyerDemand** создаётся только авторизованным покупателем: 1..N `BuyerDemandLine` с каноническими Product/совместимыми проверенными характеристиками, qty, единицей, желаемой максимальной ценой, выбранным городом/условиями/сроком. Не привязывать запрос к SKU конкретного продавца.

Сервер подбирает **eligible sellers** по опубликованным Listing/SKU, региону оказания/доставки, участию в запросах и модерации. Продавец присылает `SellerBid` (версионированное, истекающее предложение) и `SellerBidLine`: собственный SKU по каждому предмету, qty, цена, условия и подтверждённые доставка/ETA по доступности. Для первой версии каждый SellerBid покрывает **весь комплект одним продавцом**; частичная комплектация несколькими продавцами — позднее и требует отдельного согласия покупателя.

Покупатель получает сравнение нескольких продавцов и вправе выбрать, отклонить или начать Smart Bargaining. Создание `SellerBid`, открытие/согласование переговоров или получение ответа продавца не создаёт Order и не резервирует склад. Принять можно только актуальное, авторизованное, проверенное предложение; дальнейшая конверсия в один Order идемпотентна. Конкурентное принятие нескольких bid-версий должно завершаться ровно одной победившей версией либо чистым конфликтом без частичных записей.

Контактные данные, точный адрес покупателя, GPS и личный профиль нельзя передавать всем конкурирующим продавцам. Публичные данные запроса ограничиваются действительно необходимыми параметрами и выбранным городом; антиспам, rate limit, отзыв и срок публикации обязательны. В текущем репозитории есть **только immutable Java-domain records и unit-тесты** этих правил; хранилище/endpoint/RBAC не реализованы.

## 7. Smart Deal Finder и персональные рекомендации

Первый этап — **deterministic deal comparison**: сравнивать только эквивалентные товары/варианты (бренд, модель, память или согласованные сервисные требования), совместимые qty, опубликованные активные предложения, eligible seller, подтверждённые KZT цены и **полную стоимость** (товар + известная доставка/сборы). Если доставка неизвестна, не утверждать, что предложение дешевле по итоговой стоимости. Отобразить причину рекомендации и актуальность данных.

Второй этап — рекомендательный профиль и **Java-native ML: Java 21 + Tribuo + PostgreSQL + Spring Boot**, по [ADR-003](ADR-003-JAVA-ML-RECOMMENDATIONS.md). Разрешённые события: поиск, фильтры, favorites, квалифицированные views, negotiations/orders и обратная связь. Для R1 без данных — правило/популярность как baseline, **не** выдуманный ML score. Для ML — временно корректные признаки, train/validation/test по времени, PR-AUC/Precision@K/калибровка, модель/схема/метрики/контроль отката; обучение не выполняется в HTTP path.

Push:
- **Операционные** (ответ продавца, заказ, получение) — детерминированные события, не зависят от ML.
- **Маркетинговые/рекомендательные** — только при отдельном согласии; refresh цены и доступности, дедуп, квоты и quiet hours, opt-out/удаление интересов, защита от чрезмерного количества уведомлений.
- Отправка через Notification Center + PostgreSQL Outbox; FCM опционален, сбои push не блокируют транзакции. Java ML rollout после offline и A/B qualification.

## 8. Order, warehouses, geo, inventory

CustomerOrder имеет внутренний UUID и неизменный **публичный номер `ZK-XXXX-XXXX`**: 8 `SecureRandom` символов из алфавита без `0/1/I/O`, БД UNIQUE, bounded collision retries. Номер — публичный locator, не фактор доступа. Состав — 1..N immutable OrderLine: SKU, snapshots названия/варианта/единицы/qty/цены/KZT/версии, `originCitySnapshot`, `originRegionCodeSnapshot` (на этапе PLACED предварительные). Заказ одного продавца может исполняться несколькими группами выдачи/доставки.

`InventoryLocation` — точка продавца (WAREHOUSE/SHOP/PICKUP_POINT/SERVICE_POINT) с region/city/structured address, optional WGS84 lat/lon, active/version и publicPickupEnabled. Закрытые склады не раскрывать; разрешённые точки самовывоза могут быть показаны авторизованному участнику заказа. Не подменять origin snapshot без истории; существенное изменение города/срока/цены доставки требует нового согласия покупателя до подтверждения.

`ProductSku.stockTrackingMode` = TRACKED / UNTRACKED. `InventoryBalance` уникален по (locationId,skuId), инвариант `0 <= reserved <= onHand`. При PLACED **нет резервирования**. Только seller CONFIRMED в одной PostgreSQL транзакции проверяет все TRACKED позиции, агрегирует одинаковые SKU/location, блокирует/резервирует в детерминированном порядке, пишет movements + outbox и либо подтверждает **всё**, либо откатывает **всё**. UNTRACKED не получают фиктивный reservation. Конкурентные подтверждения не допускают oversell. Release/consume/cancellation/dispute требуют отдельных протестированных переходов.

Геолокация для будущей доставки — подтверждённая исходная InventoryLocation и предоставленный по согласию адрес/точка назначения покупателя; хранение в PostgreSQL numeric WGS84, PostGIS только при реальной потребности geo-query/ETA. Нельзя заявлять готовую доставку или расчёт тарифов без интеграции.

## 9. Fulfillment, receipt QR, disputes and reviews

Order → 1..N `FulfillmentGroup` → `FulfillmentItem` по исходным строкам. Группы могут представлять pickup, delivery либо service acceptance; digital provision рассматривается отдельно. Детальные partial quantities, multiple locations per line и derived status PARTIALLY_RECEIVED до утверждения S2 не считаются фиксированной physical JPA-схемой.

OrderStatus: PLACED / CONFIRMED / PROCESSING / CANCELLATION_REQUESTED / COMPLETED / CANCELLED_BY_BUYER / CANCELLED_BY_SELLER / CANCELLED. Переходы типизированные commands с ролями/version/audit, не произвольный PATCH.

**QR получения:** постоянный printed QR (с номером заказа) открывает авторизованный lookup, но не подтверждает RECEIVED. Для конкретной готовой группы выдаётся отдельный короткоживущий одноразовый `ReceiptChallenge` (сервер хранит hash token, buyer/group/merchant binding, expiry, replay protection). Только явное действие авторизованного покупателя после проверки завершает получение. Для услуги — отдельный тип acceptance, не ложное физическое вручение. Оплата между сторонами, платформа не управляет деньгами без отдельно утверждённого финтех-решения.

Возвраты, претензии и отзывы отделены: `ReturnClaim` с причинами/доказательствами, отзыв после подтверждённого получения, раздельные рейтинги товаров и продавцов; seller reputation позже может опираться на проверяемые outcomes. Не автоматизировать возврат средств или юридические обещания без правового анализа.

## 10. Архитектура, DB и границы модулей

- Java 21, Spring Boot 3.x, **Spring MVC REST**, Spring Data JPA/Hibernate, PostgreSQL JDBC/HikariCP, modular monolith.
- React + TypeScript Web и Flutter mobile, S3-compatible object storage для медиа, PostgreSQL Transactional Outbox для событий, Java Tribuo для ML (отдельный bounded context внутри монолита).
- Модули: identity, seller, catalog, listing, pricing, media, qr, lead, buyer-request, negotiation, order, inventory, fulfillment, favorite, rating, analytics, notification, recommendation.
- No cross-module JPA object graph leakage; application interfaces/IDs/events на границах; ArchUnit в тестах. Для публичных API — DTO, server-side ACL, versioned OpenAPI, idempotency, correlation ID.
- Hibernate `ddl-auto=update` **только development**, `validate` production. **Версионированные reviewable SQL migrations обязательны до выпуска**; использование Liquibase как инструмента отложено на R1 и не может означать отсутствие migration discipline в production.
- Временные метки UTC, бизнес-зона `Asia/Qyzylorda` по действующему конфигурационному решению; ночные расписания и локальные часовые пояса продавцов уточнить отдельно.
- Redis для catalog/read models при доказанной потребности, **никогда source of truth** для заказов, денег, резервов. Никаких микросервисов/Kafka/Python/модели как отдельного сервиса в P0.

## 11. Аналитика и ночные профили

События `LISTING_VIEW`, `QR_VISIT`, `VIDEO_CLICK`, `FAVORITE_ADDED`, `ORDER_CREATED`, переговоры, buyer demand/bid и другие подтверждённые действия. Не считать React re-render, prefetch, ботов и фиктивные сканирования. В retention/anti-tracking целях минимизировать идентификаторы, ограничивать срок событий и давать пользователю управление персонализацией.

Ночной расчёт агрегатов профиля покупателя/продавца и пары seller-buyer: consistent read, run metadata, versioned snapshot и atomic publication. Показатели/пороги рангов и расписание окончательно проходят S2 после подтверждения актуального бизнес-часового пояса; не смешивать репутацию продавца с чувствительными персональными выводами.

## 12. REST контракты — статус

**Документируются, но не существуют как готовые endpoints:**

- `GET /api/v1/catalog/listings` — PUBLIC filters/category/facets, stable pagination, visibility.
- `GET/POST/DELETE /api/v1/me/favorites` — owner-scoped synchronized favorites.
- `POST /api/v1/buyer-demands`, `GET /api/v1/buyer-demands/{id}/bids`, `POST /api/v1/buyer-demands/{id}/bids`, `POST /api/v1/buyer-demands/{id}/accept` — authenticated demand and seller competition.
- `POST /api/v1/negotiations`, `POST /api/v1/negotiations/{id}/create-order`, `POST /api/v1/orders/quotes`, `POST /api/v1/orders` — per-line offer and explicit buyer order.
- Seller confirm, fulfillment, receipt challenge and user-confirm endpoints — S2 naming/status matrix pending.
- `GET /api/v1/recommendations/deals` — eligibility-filtered, explainable full-cost comparisons (no fabricated fees).

Price, seller identity, ownership, reserve, city/coordinate disclosure and status decisions **никогда не берутся как доверенные из клиентского JSON**. Ошибки 400/401/403/404/409/422 и повторные idempotent responses должны быть единообразными; конкретный API schema утверждается в отдельной OpenAPI 3.1 спецификации.

## 13. Технические NFR и безопасность

AuthN/AuthZ per resource; Idempotency-Key и replay protection; rate limiting/anti-spam; безопасные ссылки, фото/EXIF/контент; PII protection и audit; резервное копирование с проверкой restore; traceId, метрики и профилирование N+1. Напечатанный публичный QR не является секретом или правом на получение.

Ориентиры до нагрузочного тестирования: p95 публичного чтения карточки до 300 ms и p95 создания заказа до 800 ms при определённой инфраструктуре; доступность 99.5% после выпуска — **цели, не доказанные SLA**. Для заказа/резерва позднее k6 5000 VU, сравнимые SQL/CPU/pools, p95/p99, RPS, errors, order correctness. PostgreSQL Testcontainers mandatory для уникальности, concurrency, транзакций и отличающейся от H2 семантики.

Юридически значимые вопросы Казахстана (круг прав потребителя, кассовое округление KZT, согласия, персональные данные, выдача/подтверждение оказания услуги) требуют отдельной проверки до production.

## 14. Текущий фактический implementation baseline

Код в branch `docs/adr-002-jpa-architecture-review` PR #2 включает:
- `backend/`: Java 21 Spring Boot foundation, enum, базовые JPA `CustomerOrder`, `OrderLine`, `InventoryLocation` + `GeoPoint`, `OrderNumberGenerator`, H2 and PostgreSQL Testcontainers тесты.
- `web/`: React buyer catalog prototype на демоданных, фильтры, карточки разных продавцов, пагинация и localStorage favorite; реальные seller/category/attribute/price APIs и общая Flutter синхронизация не подключены.
- `backend/.../deal/`: **pure Java records** BuyerDemand, SellerBid и детерминированные DealQualification/SmartDealFinder с unit-тестами, **без JPA persistence, REST endpoints и security integration**.
- Tribuo ML **только ADR**; нет training/inference/push. Нет работающего полного commerce order/reserve/fulfillment path. Не называть код production-ready по одному green CI.

## 15. Исполняемая дорожная карта и gates

| Slice | Deliverable | Blocking evidence |
|---|---|---|
| **R1 Catalog** | Реальная PostgreSQL модель Catalog/Listing/SKU/attribute schema, PUBLIC read API, Web integration | RED H2/Postgres + MockMvc + visibility/owner/security and pagination |
| **R2 Identity/Favorites** | Auth buyer/seller and server Favorite + Web/Flutter sync | authN/AuthZ, IDOR, repeated favorite, recovery security |
| **R3 Reverse Marketplace** | BuyerDemand/Line, SellerBid/Line JPA + seller eligibility, expiry, idempotent winning bid | PostgreSQL concurrent accept, cross-seller SKU, privacy, no premature reserve |
| **R4 Smart Bargaining/Orders** | Negotiation versioning + quote/order creation + seller-confirm all-or-none reserve | snapshot immutability, one order per offer, 409/rollback, concurrent oversell, outbox |
| **R5 Fulfillment/QR** | Multiple groups, approved public pickup, one-time receipt token, service-specific completion | expired/wrong user/replay, partial group completion, privacy |
| **R6 Deal Finder** | Server-computed product equivalence and complete cost, preferences baseline, read API | no stale price or fabricated delivery, ranked correct results, no data leaks |
| **R7 Tribuo ML/Push** | Dataset, trained model, evaluation and rollout, consent-controlled notifications | temporal leakage, offline metrics, A/B, caps, opt-out, monitoring |
| **R8 Expansion** | Optional PostGIS/Redis, delivery providers, additional analytics | quantified need, migration plan, benchmark and rollback |

Use **S0 Scenario Design → S1 executable RED → S2 independent Test Review → S3 GREEN → S4 regression/qualification** per slice. No merge to main or go-live while any P0 correctness/security gate is red. Existing PR #2 remains Draft until review.

## 16. Explicit decision / conflict ledger

| ID | Status | Decision / issue |
|---|---|---|
| CF-01 | **RESOLVED** | React Web теперь buyer catalog + целевое authenticated Favorites; старое «Web heart disabled» superseded |
| CF-02 | **RESOLVED** | Listing 1..N SKU, Order 1..N OrderLine; старое «один Listing на заказ» superseded |
| CF-03 | **RESOLVED** | Java21/Spring MVC/JPA; WebFlux/R2DBC старый ADR-001 SUPERSEDED by ADR-002 |
| CF-04 | **RESOLVED** | Java Tribuo ML утверждён как roadmap, но **не** обязательный функционал первого пилота |
| CF-05 | **RESOLVED** | Складские координаты приватны; город и регион origin snapshot в OfferLine/OrderLine |
| CF-06 | **RESOLVED** | Номер `ZK-XXXX-XXXX` не даёт прав; подтверждение только отдельным одноразовым QR |
| CF-07 | **RESOLVED** | Web callback ≠ BuyerDemand ≠ Negotiation ≠ Order |
| CF-08 | **PROPOSED / S2** | Один продавец на BuyerDemand bid/full bundle; разные продавцы в отдельные заказы; возможный partial bid позже |
| CF-09 | **PROPOSED / S2** | Quote TTL, seller bid/expiry, quantity rounding, KZT cash rounding, discount/tier precedence |
| CF-10 | **PROPOSED / S2** | Комплектность встречных предложений, выигрыш/consent state, отзыв запроса, order conversion idempotency |
| CF-11 | **PROPOSED / S2** | FulfillmentGroup/Item partial split, multiple warehouse per line и разные типы receipt |
| CF-12 | **PROPOSED / S2** | Pricing/Inventory release-consume policy, cancellations, return/dispute snapshots |
| CF-13 | **PROPOSED / S2** | Время nightly aggregation и связь с `Asia/Qyzylorda` business zone |
| CF-14 | **PROPOSED / S2** | Категорийные атрибуты: нормализованная типизированная схема vs typed read model, индексы |
| CF-15 | **PROPOSED / S2** | Сбор ML событий, сроки хранения, согласие, модельные пороги и A/B rollout |
| CF-16 | **REQUIRES LEGAL REVIEW** | Обработка телефона, платёжные статусы, KZT rounding, приёмка услуг и права потребителей в РК |

**Ссылки на детальные источники:** [ADR-002](ADR-002-MVC-JPA.md), [ADR-003](ADR-003-JAVA-ML-RECOMMENDATIONS.md), [Buyer Web](DEC-BUYER-WEB-CATALOG.md), [SKU/locations](DEC-ENTITY-MULTI-SKU-LOCATION.md), [Selected SKU Order](DEC-SELECTED-SKU-ORDER.md), [Order Number/QR](DEC-ORDER-NUMBER-001.md), [Geolocation](DEC-LOCATION-GEOPOINT.md), [Reverse/Bargaining/Deal Finder](DEC-KILLER-FEATURES-R1.md), [Entity Registry](ENTITY-REGISTRY-R05.md), [Domain Contracts](DOMAIN-CONTRACTS.md), [API-SPEC](API-SPEC.md), [TEST-PLAN](TEST-PLAN.md), [DECISIONS](DECISIONS.md).

**Note:** detailed older design documents remain for traceability but cannot override the conflict ledger above. No product feature described here is deemed implemented solely because its product direction is approved.

## 17. UCE-001 — Универсальная классификация для Buyer/Seller Matching

Утверждается для последующего S2 согласования концепт [Universal Classification Engine](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md): единый каталог PHYSICAL/SERVICE/DIGITAL, классы, типизированные атрибуты (единицы и нормализованные коды), схема с версионированием, локализация KK/RU/EN/ZH, раздельные сущности Canonical Product/Service Spec и предложение конкретного продавца. Внешние GS1 GPC/UNSPSC/Schema.org коды — optional mapping, не внутренние первичные ключи. 

Matching выполняется строго в порядке **typed normalization → eligibility/hard constraints → verified all-in cost → Tribuo score или rules fallback**. Модель не может разрешить неподходящий SKU, фиктивное наличие, чужую публикацию или автоматически акцептовать торговые условия. Политики замен/единиц/модификации схемы должны пройти S2; дополнительные таблицы не считаются утверждёнными до физического проектирования и PostgreSQL-тестов.

### DEC-UCE-002 — фиксированные разряды HCC и позиции ML-признаков

В дополнение к UCE-001 принято проектное направление: универсальный DictionaryNode (автоматический BIGINT surrogate ID), отдельный опубликованный **строковый фиксированно-разрядный ClassificationPath**, единые справочники цветов/материалов/размерных систем и типизированные атрибуты с правилами применимости. Для Tribuo вводится **версионированный неизменяемый FeatureSchemaVersion**, определяющий позиции/названия/типы/словарь категориальных значений/нормализацию/missing-policy. Категориальные numeric ID не подаются как непрерывные значения; преобразуются в именованные one-hot/binary признаки с зафиксированным порядком при экспорте массивов. Вариант формата 2-2-3-5-8 является пилотным, ширина требует S2 ревью. [Подробное решение UCE-002](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md#dec-uce-002--strict-positional-coded-hcc--versioned-ml-feature-layout-2026-11-12).

### DEC-UCE-003 — город и год рождения покупателя

В BuyerProfile допускаются необязательные `residenceCityAreaId` (выбранный покупателем город, ссылка на версионированный территориальный классификатор/КАТО) и `birthYear` (самостоятельно указанный год рождения). **Эти данные не являются атрибутами товара и не входят в код HCC.** Город назначения конкретного BuyerDemand может отличаться от города проживания; проверка доставки опирается на город запроса. Для Tribuo город кодируется категориально и через признаки совпадения зоны обслуживания, а год рождения при разрешённой персонализации преобразуется в возрастные диапазоны на дату прогноза, не в точный юридический возраст. Отдельное отзывное согласие, удаление, ограничения хранения и защита несовершеннолетних обязательны; не использовать возрастные признаки для персонализированного маркетинга несовершеннолетним без отдельной правовой и безопасностной квалификации. См. [UCE-003](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md).

### TRIBUO-HCC-001 — проверка совместимости HCC и Tribuo

Проведён проектный анализ [REVIEW-TRIBUO-HCC-001.md](REVIEW-TRIBUO-HCC-001.md): HCC и числовые DictionaryNode.id используются как идентификаторы иерархии, а не ordinal ML значения; типизированные глобальные атрибуты, реестр frozen feature schema, чистые as-of признаки и безопасный deterministic fallback обязательны. Город запроса отделён от добровольно указанного города проживания, год рождения — необязательный персональный атрибут с ограничениями обработки. Результат **архитектурно совместимо при выполнении S2-gates**, не доказательство обученной модели или реализованного модуля.

## 18. INTELLIGENCE-001 — сегментация задач и пространств ML-признаков

Единые HCC/UniversalDictionary/AttributeRegistry/KATO/BCP-47 и проверенные поведенческие события используются через логические пространства признаков `CLS`, `ATR`, `GEO`, `LNG`, `BUY`, `SEL`, `OFR`, `EVT`, `MAT`; `OUT` зарезервирован для размеченных результатов и **не** является входным прогнозным признаком в том же временном срезе. Разные задачи (каталог, проверка совместимости, продавец отвечает, покупатель выбирает, сегментация, сравнение цены, Demand Radar, комплекты, Push, риск, доставка, мультиязычность) используют общую таксономию, но отдельные feature schemas, labels, метрики и правила допуска. Цифровые `BIGINT` ID не создают семантическую близость. Персонализация по языку требует разделения `ui/content/communication/notification/service` и не должна считать язык национальностью. Подробности и 12 acceptance-оракулов — [DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md). Статус: согласованная проектная сегментация, не ML-реализация.

## 19. FeatureRegistry, executable matching foundation, killer-feature portfolio

Общий классификатор HCC и проверенные справочники атрибутов используются множеством независимых задач; отдельные `FeatureRegistry(task,version,orderedNames)` и task-specific feature encoders гарантируют воспроизводимость и строгую разрядность экспортируемого массива (не следует интерпретировать numeric dictionary ID как непрерывные признаки). S1 pure-Java proof-of-concept: `ClassifiedOfferMatcher` проверяет канонический Product/class и обязательные характеристики, eligibility/coverage и SellerBid basket contract **до** ML; `DealFeatureProjector` формирует `buyer_choice/v1` признаки из прошедшего проверку предложения. Реальные seller ACL, SKU ownership, HCC PostgreSQL schema, модель Tribuo, training, API и Push пока отсутствуют. **30 возможностей** с P0/P1/P2, зависимостями, экспериментами и защитными условиями описаны в [INTELLIGENCE-KILLER-FEATURES-PORTFOLIO.md](INTELLIGENCE-KILLER-FEATURES-PORTFOLIO.md). Принцип rollout: сначала проверенная совместимость и пользовательская ценность, затем сбор размеченных событий и только потом ML.
