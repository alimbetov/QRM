# ZAKAZ React Workspace R1 — техническое задание и первый исполняемый срез

**Дата:** 2026-11-14. **Репозиторий:** `alimbetov/QRM`. **Единственная рабочая ветка:** `docs/adr-002-jpa-architecture-review`. **Статус:** утверждённый к реализации ограниченный R1; независимый S2/production release — HOLD.
**Основание:** реальные исходники `web/src/main.tsx`, `LiveCatalog`, `LivePhotoFeed`, `QrLanding`, `BuyerFlow`, `mobile/lib/hybrid_catalog_screen.dart`, `SellerPublishingController`, `SellerPublicPriceController`, `ListingMediaController`, `AuthenticatedCommerceController`, `SellerIdentityService`. Старый `web/src/App.tsx` — только локальный demo и не подключается к настоящему каталогу.

## 1. Цель и архитектурное решение

Развить **существующий** React 19 + Vite/TS, без нового frontend проекта, в единый адаптивный Buyer/Seller Workspace. Приоритет — первый фактически используемый Seller Studio и полностью проверяемый путь продавец → товар → QR → заявка покупателя → SellerBid → Order(PLACED) → продавец CONFIRMED → reserve.

- Backend остаётся модульным Spring MVC + JPA + PostgreSQL. Категории — единственная HCC модель (2/2/3/5/8), SKU с авторитетными единицами, цены в KZT exact NUMERIC. Никогда не создавать альтернативный JSON-каталог, отдельную ML классификацию или fake-mode.
- Публичные страницы каталога/QR обслуживаются React, в Flutter optional hybrid показываются **только** `/` и `/q/{token}`; `/buy` остаётся нативным AppAuth в Flutter. Отдельная авторизация продавца **не открывается в WebView**.
- Сервер выполняет все права: JWT issuer/subject, scope + активное `seller_membership`, принадлежность объявления, status, canonical product, stock, delivery, pricing. Никакой sellerId/client-origin флаг в JSON не даёт прав.
- Визуальная архитектура: общий `WorkspaceLayout` и явный клиентский Router; страницы публичного каталога, BuyerFlow и QR остаются теми же компонентами, UI не форкается. Старые topbars скрываются внутри shell. Незнакомые URL ведут к 404, не к каталогу. Навигационный `/buy` оставлен **обычным HTML link**, а не SPA-переходом, чтобы `HybridRoutePolicy` перехватывал его в нативный Flutter.
- R1 seller UI опционален: `VITE_SELLER_WORKSPACE_ENABLED=false`; Backend `zakaz.seller.workspace.enabled=false`. Включать вместе только на изолированном staging после авторизованного теста. Старые Web/Flutter сценарии сохраняются.

## 2. Маршруты

| URL | Назначение | Доступ |
| --- | --- | --- |
| `/` | Текущий каталог / optional `LivePhotoFeed` | Guest |
| `/q/:token` | Канонический публичный QR listing | Guest, токен 43 символа |
| `/buy` | OIDC покупателя, BuyerDemand → SellerBid → Order | Buyer JWT |
| `/auth/callback`, `/auth/signed-out` | Исходный Keycloak Buyer SPA | Buyer OIDC |
| `/seller` | Seller Workspace: мои объявления, создание черновика, цена, фото, публикация | Seller OIDC, feature flag |
| `/seller/auth/callback`, `/seller/auth/signed-out` | Отдельная сессия Seller SPA Keycloak | Seller OIDC |
| `*` | Явная 404, без раскрытия идентификаторов | Guest |

Маршруты `/seller` запрещены в Flutter `HybridRoutePolicy`, без JS token bridge и без browser-to-native seller impersonation.

## 3. Seller Workspace R1 (первый исполняемый срез)

Backend добавляет **только недостающую грань**, остальные существующие API сохраняются:

| HTTP endpoint | Авторизация | Семантика |
| --- | --- | --- |
| `GET /api/v1/sellers/{sellerId}/listings?page=0&size=12` | `catalog:write` + `requireCatalogEditor` | Только свои, ограниченная сортированная страница, статус, цена, SKU, QR (только PUBLIC) |
| `GET /api/v1/sellers/{sellerId}/listings/{listingId}` | То же | Чужое объявление скрыто 404 |
| `POST /api/v1/sellers/{sellerId}/listings` | То же | Транзакция создаёт DRAFT + один TRACKED SKU внутри существующего CanonicalProduct; `201` с идентификатором |
| `PUT /api/v1/sellers/{sellerId}/listings/{listingId}/skus/{skuId}/price` | Существующий API | Только цена DRAFT, точный NUMERIC(19,2), без silent rounding |
| `POST /api/v1/sellers/{sellerId}/listings/{listingId}/media` | Существующий API | Контроллер сервиса → ClamAV → sanitizer → RustFS/S3, без прямого браузерного доступа к bucket |
| `POST /api/v1/sellers/{sellerId}/listings/{listingId}/publish` | Существующий API | Validation HCC/атрибутов; переход PUBLIC, стабильный QR |
| `GET /api/v1/sellers/{sellerId}/listings/{listingId}/qr.png` | Существующий API | PNG только для авторизованного продавца опубликованного listing |

Для R1 создание ограничено **существующим ACTIVE каноническим PHYSICAL Product**, `trackingMode=TRACKED` и допустимой единицей без SERVICE/LICENSE. Нельзя менять SKU и цену после PUBLIC; ошибка не создаёт частичный SKU. Цена публичного SKU **не равна** финальной цене CustomerOrder с доставкой.

**Временное ограничение продукта:** Seller ID и Canonical Product ID выбираются в R1 из известных администратору UUID — это **внутренний UAT инструмент**, не готовый продавец onboarding. Следующий срез обязан дать owner-scoped `/sellers/me` + поисковый селектор Product/HCC. Запретить произвольное создание нового CanonicalProduct, Category или Attribute из продавца.

Данные и экран: отдельные buyer/seller OIDC SPA public clients с exact HTTPS redirect, PKCE S256, без client secret; tokens не передаются в query, WebView, localStorage или sellerId JSON. Logout выполняется через подтверждённый Keycloak callback. Список объявлений не создаёт их автоматически; ошибка 403/404 не заменяется demo data.

## 4. Следующие вертикальные срезы — порядок реализации

**R2 Seller Deals Inbox (P0):** owner-scoped поиск релевантных BuyerDemand по реальным HCC/серверным фильтрам, явное приглашение только при двух согласиях, SellerBid (revision, lines, deliveryFee, originLocationId), read list собственных CustomerOrder, подтверждение/отмена через существующие методы. В раннем R1 ввод demand UUID из админ-инструмента не считается полноценным UX.

**R3 Seller inventory/media operations (P0):** выбор InventoryLocation, создание/изменение InventoryBalance, gallery edit/cover/retry, ClamAV/RustFS real test, QR preview/download, склад и ETA без обещания непроверенных остатков. Не публиковать SERVICE/DIGITAL через PHYSICAL контракт.

**R4 Unified Buyer Workspace (P0/P1):** список собственных заявок/заказов, редиректы/recovery после auth и перезапуска, оповещение о SellerBid, статус оформления и отмена; единые UX состояния loading/empty/error/retry и навигация. Реализовать Category HCC facets и photo feed на реальном Backend; не включать legacy mock `App.tsx`.

**R5 Favorites + Notifications (P1):** `Favorite(buyerId,listingId)` owner-scoped, группировка по HCC, синхронизация Web/Flutter, signed-in import гостевых избранных только с согласием. Привязка к watch/Push строго opt-in. Отдельная Notification Center/FCM инфраструктура; ML-R2 `ml_trade_event` и internal seller inbox сами по себе не означают отправку Push.

**R6 End-to-end trading and fulfillment (P0):** five-round NegotiationSession c `AGREED` без автоматического Order; buyer явно создаёт заказ, резерв только после CONFIRMED, затем fulfillment/receipt/returns/reviews. Любая новая схема согласуется с существующим одним `CustomerOrder` на одного продавца.

**R7 Product UX hardening:** i18n RU/KZ/EN/ZH, responsive 360/390/768/1280 px, accessibility keyboard/ARIA, error telemetry без PII, loading/empty states, Web Vitals; физический Flutter hybrid+native E2E. ML-R6 рейтинг/рекомендации только после ML-R3 Dataset, ML-R4 independent model review и ML-R5 model registry/fallback.

## 5. Acceptance / tests

| ID | RED/green contract |
| --- | --- |
| WEB-R1-01 | Guest видит реальный catalog без seller/buyer JWT, неизвестная страница — 404 |
| WEB-R1-02 | Seller OIDC S256 отдельный от Buyer OIDC, auth callback exact path, logout; no JWT in WebView |
| WEB-R1-03 | No scope => 403, unknown/foreign company => 403, foreign listing => 404; path UUID не выдаёт owner privileges |
| WEB-R1-04 | Verified CATALOG_EDITOR создаёт DRAFT+1 SKU atomically, QR не существует до publish |
| WEB-R1-05 | Missing CanonicalProduct => 404, non-physical/inactive/invalid unit/price => 422; no partial listing |
| WEB-R1-06 | Owner листает свои объявления; чужое и приватные атрибуты исключаются; page 0..10000 size 1..25 |
| WEB-R1-07 | Цена DRAFT редактируется, PUBLIC immutable; exact KZT strings 2 decimals, no JS float |
| WEB-R1-08 | Media through authenticated Backend controller only; scanner/storage failures fail-closed, no direct S3 URL |
| WEB-R1-09 | Publish validates HCC/SKU, public QR /q/{token}, PNG QR download by owner only |
| WEB-R1-10 | Existing guest QR→native Flutter BuyerScreen /buy link interception preserved |
| WEB-R1-11 | React Vitest / tsc / build; Java HTTP/JPA tests + PG Testcontainers, real staging Playwright after operator configuration |
| WEB-R1-12 | Independent S2 Review on exact SHA + real browser Keycloak seller+buyer and Android/iOS hybrid UAT before prod |

**Current implementation evidence:** R1 code and tests must be evaluated at exact GitHub SHA in CI. Build/test success alone does NOT certify actual Keycloak SMTP, mounted Flutter app, RustFS/ClamAV, seller order UAT or S2.

## 6. Definition of done / release

R1 code is accepted for *further review* after backend tests, Vitest, TS build, Flutter regression and staging realm checks. `VITE_SELLER_WORKSPACE_ENABLED` and Backend `ZAKAZ_SELLER_WORKSPACE_ENABLED` default OFF. Full release requires operator-run browser Playwright, real Seller OIDC redirect + verified membership, full Buyer/Seller/Inventory journey, device WebView/PKCE, independent S2, privacy and rate/security audit. Single working branch only; don't promote `main` or merge Draft PR #2 without evidence.
