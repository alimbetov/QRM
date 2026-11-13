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

## 20. ADR-004 — десять гибридных интеллектуальных возможностей

Утверждён **целевой портфель** Smart Demand, Smart Multi-Seller Deal Optimizer, AI Shopping Assistant, Demand Radar, Smart Bundle Builder, Multilingual Product Search, Delivery Optimizer, Buyer Segmentation, Smart Bargaining Advisor и Compatible Alternatives. Портфель и engine boundary contracts: [ADR-004-HYBRID-INTELLIGENCE-ENGINES.md](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md). Обязательные правила: HCC/AttributeRegistry + Rules как первичная проверка, Tribuo только для прогнозов, Timefold для сложной допустимой оптимизации, PostgreSQL FTS → Lucene для поиска, опциональный LangChain4j для структурирования текста, JGraphT для проверенных отношений, GIS для реальных маршрутов. **Не подключать библиотеки до исполняемых тестов и доказанной необходимости**. Мультипродавцовая оптимизация допускает только предварительную симуляцию нескольких отдельных однопродавцовых заказов; единый multi-seller CustomerOrder, общий эквайринг или атомарная покупка не утверждены. Ранжирование и решения ИИ не заменяют явного согласия на цену, заказ, доставку и маркетинг.

## 21. DEC-INTELLIGENCE-002 — недельные слои аналитики и признаки закупок

Текущие доменные сущности не изменяются. Derived read model агрегирует подтверждённые факты продаж, спроса, показов и наблюдаемой доступности товара по ключу `seller × SKU × city × ISO week`. Закрытые недели сохраняются отдельно с возможностью корректировок и versioned source watermark. Из них формируются воспроизводимые 1/4/12/52-недельные окна для аналитики спроса, оборачиваемости, рекомендаций по закупкам и будущего Tribuo. Неполная текущая неделя не участвует в closed-week обучении; неизвестное наличие не равно нулю. Наличие спроса без продаж необходимо различать с ситуацией отсутствия запаса. Статус: только pure-Java foundation и синтетические smoke, нет производственной схемы PostgreSQL или обученной модели. Подробности: [DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md).

### DEC-INTELLIGENCE-002 / S1 — каталог справочников для smoke

Добавлен независимый от доменных JPA-сущностей `IntelligenceDictionarySeed` для тестового охвата **24 классов** товаров, услуг и цифровых продуктов, общих справочников цветов/материалов/брендов/размерных систем/языков, типизированных характеристик и 10 примерных городов. Используются **внутренние символьные коды проекта**, а не официальные КАТО/GS1/UNSPSC; production-справочники должны импортироваться из проверенных источников и версионироваться. Класс и применимость атрибутов проходят smoke-валидацию; числовые значения нормализуются без превращения категориальных кодов в ordinal ML features. Семантическая полнота, иерархия размеров, единицы и сочетания свойств требуют отдельного S2; текущий seed не утверждается как полный справочник рынка. Подробнее: [DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md).

## 22. DEC-ML-RISK-001 — квалификация прогнозов и безопасный fallback

Заложен pure-Java **Reliability Foundation** поверх сохранённых доменных сущностей: проверка полноты закрытых недель, пять хронологических expanding-origin испытаний, сравнение с 4-недельным средним и формализованная политика отказа от ML при недостаточности данных, провалах по ошибке, дрейфе либо несовместимости схемы. Компоненты `DataSufficiencyEvaluator`, `TemporalBacktester`, `PredictionPolicyEngine`; синтетические JUnit-тесты. Пороги предварительные, истинное качество Tribuo, drift-monitor и доверительные интервалы в production ещё не реализованы. Решение: [DEC-ML-RISK-001-RELIABLE-LEARNING.md](DEC-ML-RISK-001-RELIABLE-LEARNING.md).

## 23. ADR-005 — ночной пакетный Intelligence / физические партиции / вычислительные бакеты

Утверждена целевая архитектура отделения онлайн-заказов от затратной аналитики: события Transactional Outbox → дедуплицированные факты → дневные/закрытые ISO-недельные агрегатные слои → 1/4/12/52-недельные feature snapshots → ML Reliability → условное обучение Tribuo → атомарная публикация прогноза. **PostgreSQL RANGE partition** по дате используется только на достаточно крупных исторических таблицах; **логические bucket tasks** независимо распределяют вычисления, первоначально 16 бакетов и 1–2 ограниченных worker-потока с lease, checkpoint, retries и quality gates. Ночные работы не должны блокировать торговые транзакции, менять фактические остатки по устаревшему snapshot или публиковать частичный расчёт. Исправления поздних событий выполняются версионированными revision-срезами с инвалидацией зависимых окон. Версионировать источник, схему признаков, бакеты и артефакты модели. [ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md](ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md). Статус: проектирование, **без физических PostgreSQL migrations или рабочего batch scheduler**.

## 24. DEC-ML-RISK-002 — адаптивная достаточность данных и управляемая многопоточность

Пороги ML подбираются **не по произвольному числу недель**, а в пределах жёстких версионированных ограничений по историческому out-of-sample качеству, минимальному числу независимых рядов, временных испытаний и покрытию stock exposure. Без независимых доказательств используется консервативный fallback, автоматического продакшен-включения модели нет. Ночные вычисления: стабильные детерминированные bucket ID, ограниченный пул platform threads, разделение I/O и CPU, никакой публикации частичного snapshot. В S1 добавлены `AdaptiveSufficiencyCalibrator` и `BoundedBucketExecutor` с JUnit smoke. Полноценные PostgreSQL leases, heartbeat, backpressure и adaptive resource autoscaling требуют S2/S3. [DEC-ML-RISK-002](DEC-ML-RISK-002-ADAPTIVE-GATES-AND-BOUNDED-WORKERS.md).

## 25. R1A — реализация Catalog & Classification Foundation

В GitHub добавлен начальный JPA-срез (не только проектное ТЗ): `Category` как единая иерархия HCC (20 символьных позиционных цифр), `AttributeDefinition`, `CategoryAttributeRule`, `CanonicalProduct`, `SellerListing`, `SellerSku`, `SkuAttributeValue` и репозитории. Существующие `CustomerOrder`, `OrderLine`, `BuyerDemand`, `SellerBid` сохранены. Есть быстрые unit/JPA-тесты и **ручной, ещё не применённый** PostgreSQL DDL `backend/src/main/resources/db/manual/V001__catalog_foundation.sql`. До выпуска нужны полноценная валидация правил атрибутов и позиционной иерархии, авторизация продавца, PostgreSQL schema validation с Testcontainers и контролируемый миграционный процесс. [R1-CATALOG-FOUNDATION-IMPLEMENTATION.md](R1-CATALOG-FOUNDATION-IMPLEMENTATION.md).

## 26. R1B — каталог, HCC, публикация и серверные факты SKU

Реализованы первичные R1B-проверки и JPA-слой: фиксированная 2/2/3/5/8 позиционная структура HCC, обязательность и применимость атрибутов для каждого SKU при публикации, проверка принадлежности Listing продавцу и серверное разрешение фактов PUBLIC SKU для будущего сопоставления с BuyerDemand. PostgreSQL ручной DDL проверяется отдельным Testcontainers IT; production миграция, идентификация authenticated actor, текущий остаток, seller verification, территория и ценовой quote пока не реализованы. Подробные ограничения и квалификация: [R1B-CATALOG-QUALIFICATION.md](R1B-CATALOG-QUALIFICATION.md).

## 27. R1B.2 — Catalog Security & Integrity (реализация)

Кодовая реализация добавляет `CatalogDraftMutationService` (транзакционные изменения DRAFT с блокировкой Listing, проверкой владельца), глобальные допустимые `AttributeEnumOption` и `CatalogEnumPolicy`, запрет неизвестных enum при публикации и выдаче server-owned SKU facts. Дополнительно PostgreSQL `V003` защищает опубликованные SKU/атрибуты от прямых SQL UPDATE/INSERT/DELETE. Домен заказов не менялся. **Пока нет** HTTP-авторизации seller principal, полного контролируемого процесса миграции, версионированного HCC/reference-data snapshot и разрешённого процесса переиздания SKU. Эти условия обязательны до production. [R1B.2 отчёт](R1B2-CATALOG-SECURITY-AND-INTEGRITY.md).

## 28. Seller Authentication / Flyway Migration Gate — реализовано, S2 pending

Реализованы Spring Security OAuth2 JWT resource-server, server-managed `seller_membership(issuer,subject,sellerId,role,active)`, защищённый seller publish API, отказ при отсутствии JWT/JWT scope или членства; добавлены проверка RSA-подписи и claim-валидация в интеграционных тестах. PostgreSQL теперь управляется Flyway V1–V4, включающими и прежние таблицы заказов/локаций; `ddl-auto=validate`, `baseline-on-migrate=false`, `clean-disabled=true`. Автоматическое принятие существующей pre-Flyway базы запрещено. Внешний OIDC/JWKS, доверенное создание членства, переход существующей production базы и независимое S2 решение пока открыты. [S2 Review Package](S2-REVIEW-SELLER-AUTH-FLYWAY-R2-GATE.md), [R2 Scenario Design](R2-BUYER-DEMAND-BID-ORDER-RESERVATION-SCENARIOS.md).

## 29. R2.0A — сохранение BuyerDemand и SellerBid (Java/JPA/PostgreSQL)

Добавлены отдельные JPA snapshots для `BuyerDemand` и `SellerBid` с позициями, неизменяемыми ревизиями, проверкой принадлежности SKU продавцу и канонического товара, валидацией expiry и правильной последовательности revision. Flyway V5 включает FK/UNIQUE/CHECK; V6 защищает исторические записи от SQL UPDATE/DELETE. Сервис работает только внутри Backend без публичного checkout; создание `CustomerOrder`, авторизация покупателя, единицы измерения, цена с доставкой, идемпотентность принятия, outbox и резервирование остатков ещё **не реализованы**. [R2.0A](R2-0A-DEMAND-BID-SNAPSHOT-IMPLEMENTATION.md).

## 30. R2.0B и R2.1 — принятие SellerBid и транзакционное резервирование

Реализованы внутренние JPA-сервисы: проверенная единица измерения SKU и BuyerDemand, зафиксированная KZT-стоимость позиций+доставки, Buyer JWT issuer+subject→buyer_account_link, seller delivery area, замороженные bid quote и acceptance, идемпотентный выбор одного bid и CustomerOrder(PLACED) без резерва (Flyway V7–V9). Отдельная транзакция продавца с SELLER_ADMIN авторизацией подтверждает PLACED → CONFIRMED, выполняет детерминированное блокирование balance, атомарное резервирование всех позиций и запись ORDER_CONFIRMED Outbox (Flyway V10). V11 защищает экономические поля заказа от прямого SQL изменения. Нет публичного checkout, интеграции реального IdP/гостевого покупателя, движения/освобождения остатков, Outbox publisher и 5K нагрузочного теста; независимый S2 Review открыт. [R2.0B](R2-0B-BID-ACCEPTANCE-IMPLEMENTATION.md) · [R2.1](R2-1-INVENTORY-RESERVATION-OUTBOX-IMPLEMENTATION.md).

## 31. R2.1 Hardening / R2.2 Stock Movement & Seller Cancellation (2026-11-13)

Flyway V12 закрывает возможность прямой дозаписи `order_line` после создания заказа и проверяет `expected_line_count`/сумму в момент COMMIT; V13 гарантирует принадлежность SKU/склада/резерва продавцу и его заказу. Исправлены полнота недель и сопоставимость когорт в ML. Flyway V14 создаёт неизменяемый `InventoryMovement` (RECEIPT/RESERVATION/RELEASE), а Java-сервисы реализуют идемпотентный приход товара SELLER_ADMIN и отмену PLACED/CONFIRMED с release, movement и Outbox в одной транзакции. Публичный API, buyer cancellation, реальный WMS, доставка Outbox и 5K нагрузка пока не реализованы. Старые резервы должны быть отдельно согласованы перед V14. [Квалификация](R2-1-HARDENING-AND-R2-2-INVENTORY-AUDIT.md).

## 32. R2.2B — распределённая PostgreSQL очередь Outbox / R2.3–R2.4 gates

Добавлены `JdbcOutboxQueue` на `SKIP LOCKED`, lease/generation/token fencing, bounded retry с `DEAD_LETTER`, сохранение причин и порядок событий по `event_sequence`, отдельный `outbox-worker` профиль и idempotent внутренний обработчик с уникальным `consumer_id+event_id`. Flyway V15 намеренно блокирует автоматическую миграцию существующего непустого Outbox без сверки истории. Построение очереди **не означает внешнюю доставку**: готов только внутренний projection sink, а real external transport/multi-process staging/S2 audit ещё не выполнены. R2.3 реальный QR-first Public Listing/Lead и авторизованный Smart Deal HTTP остаются S0, R2.4 только k6 тестовый сценарий и SQL-проверки, **5000 VU не прогонялись**. [Outbox](R2-2B-OUTBOX-QUALIFICATION.md) · [E2E + load](R2-3-QR-FIRST-E2E-5K-GATES.md).

## 33. R2.3 QR-first HTTP API / React QR landing (2026-11-13)

Flyway V16 создает стабильный непрогнозируемый публичный QR-токен вместе с публикацией объявления, шифрованные `PurchaseLead` и PostgreSQL rate-limit. Реализованы публичная `GET /api/v1/public/q/{token}`, POST гостевого обращения с согласием/Idempotency-Key (не создает CustomerOrder), seller-only QR PNG с фиксированным HTTPS origin, серверный seller lead inbox (только SELLER_ADMIN+`leads:read`) и неизменяемый аудит чтений по Flyway V17. В React работает реальный маршрут `/q/{token}`, но основной каталог еще демо. Авторизованные BuyerDemand→SellerBid→Acceptance(PLACED)→Seller Confirm(CONFIRMED, reserve, Outbox) и buyer order GET подключены к HTTP контроллеру. Оба публичных функциональных контура выключены по умолчанию до S2 Review, внешнего IdP/JWKS, согласования обработки персональных данных, реального браузерного E2E и нагрузки 5000 пользователей. [Реализация](R2-3-HTTP-API-QR-JOURNEY.md).

## 34. SEARCH-001 — словарный поиск, фотолента и визуальное обнаружение (DRAFT)

**Аудит реализации 2026-11-13: CONDITIONAL S0, не S2 APPROVE.** Обнаружены order-only Outbox FK на CustomerOrder, отсутствие runtime Favorites/Media/Notifications, невозможность легально менять опубликованную цену (V3), и отсутствие SellerListing/SKU в текущем BuyerDemand route. Исправленный порядок исполнения: S1 Search → S2 Media → S3 PhotoFeed → S4 Favorites → S5 Notifications/Repricing → S6 Visual; точные контракты и BLOCKERS: [SEARCH-AUD-001](SEARCH-AUD-001-TECHNICAL-REVIEW.md) и [SEARCH-001 implementation contract](SEARCH-001-IMPLEMENTATION-CONTRACT.md).


**Проект требований, не реализовано и не является production gate PASS.** Единый поиск по названию/словарю синонимов RU/KK/EN/ZH, HCC и pg_trgm; далее безопасные медиа в SellerListing, визуальная лента с cursor/keyset pagination; затем image embedding/pgvector и Photo-to-Service. Также «Моя памятка» с группировкой сохранённых SellerListing по HCC, синхронизацией React/Flutter и явно включаемыми price-watch и Push-подписками по категориям через существующий Notification Center/Outbox; добавление в избранное само по себе не означает согласия на маркетинговые уведомления. Все результаты проходят публичную SQL-visibility PUBLIC + active category + QR; Order/Inventory/BuyerDemand остаются authoritative. Техническое задание, API/SQL контракт, границы безопасности и RED-тесты: [SEARCH-001 Unified Discovery](SEARCH-001-UNIFIED-DISCOVERY.md).

Идентификатор фичи SEARCH-001 выбран специально, поскольку старое обозначение R2.4 в §32 относится к k6/5K нагрузочному gate. Релизное переименование должно быть согласовано отдельно; S2 R2.3F, внешний staging E2E и 5K нагрузка не сняты.
