# QA-03 — ZAKAZ: аудит пользовательских сценариев и доказательства тестирования

**Дата:** 2026-11-14. **Статус:** S0 / audit & executable-test backlog, **НЕ** акт полной готовности.  
**Репозиторий/ветка:** `alimbetov/QRM` / `docs/adr-002-jpa-architecture-review`.  
**Точный проверенный baseline:** `d075f480738168a8e7a7da55f04498f02b12c959`. Позднейшие коммиты проверять заново до выводов о CI.  
**Основные спецификации:** [REQUIREMENTS](REQUIREMENTS.md), [USER-JOURNEYS-MVP](USER-JOURNEYS-MVP.md), [SCENARIOS](SCENARIOS.md), [TEST-PLAN](TEST-PLAN.md), [HYBRID-MOBILE-R0](HYBRID-MOBILE-R0.md), [ML-R2](ML-R2-IMPLEMENTATION-STATUS.md), [R2.3 E2E Gates](R2-3-QR-FIRST-E2E-5K-GATES.md).

## 1. Вердикт / нормативное толкование

**Невозможно подтвердить, что все сценарии работают.** Код имеет полноценные серверные фрагменты, но единого UI-пути seller → buyer → fulfillment → receipt → review нет. Соответственно, никакой процент JaCoCo не может означать 100% user-journey coverage.

**Уровни доказательств** (НЕ смешивать):
- **A — CODE TESTED:** исходный код + исполняемые JUnit/PostgreSQL/React/Flutter тесты и exact-SHA CI PASS. Покрывает только указанный контракт, не автоматически весь UI.
- **P — PARTIAL:** часть логики или тестов работает, но пользовательский результат или существенная проверка отсутствует.
- **M — MISSING:** сценарий как бизнес-функция не реализован, нет возможности честно объявить его GREEN.
- **E — EXTERNAL PENDING:** код или сценарий готов к проверке, но нет доказанного запуска на реальном staging, внешних провайдерах, браузере или устройстве.
- **R — RELEASE VERIFIED:** точный SHA, полный положительный/негативный сценарий, фактический пользовательский интерфейс, интегрированный PostgreSQL/Keycloak/Media, независимое S2 и evidence. **Сценариев уровня R сейчас нет.**

Этот отчёт не заменяет execution evidence. Маркировка A означает доказанное ограниченное поведение на уровне теста, а не факт прохождения всех шагов конечным пользователем.

## 2. Наблюдаемые результаты CI (exact SHA `d075f480...`)

| Направление | Реальный результат | Источник |
|---|---|---|
| Backend Surefire | **105/105 PASS**, 0 errors | [GitHub Java run 43508174215](https://github.com/alimbetov/QRM/actions/runs/43508174215) |
| PostgreSQL Testcontainers/Failsafe | **81/81 PASS**, 0 errors | тот же run, `mvn verify` |
| JaCoCo | **89.51% line**, **63.75% branch**, 646 missed branches | тот же run, QA-02 report |
| React | 9 Vitest files PASS, build PASS | [Web run 43508174295](https://github.com/alimbetov/QRM/actions/runs/43508174295) |
| Flutter | `flutter analyze`, `flutter test --coverage` PASS | [Mobile run 43508174248](https://github.com/alimbetov/QRM/actions/runs/43508174248) |
| Staging configuration | static/config and isolated Keycloak import/JWKS smoke PASS | [Staging run 43508174207](https://github.com/alimbetov/QRM/actions/runs/43508174207) |
| Реальный Playwright browser + Keycloak/SMTP | **НЕ ЗАПУСКАЛСЯ** в CI | `.github/workflows/web-ci.yml` запускает только `--list` |
| Реальный Android/iOS WebView/AppAuth | **НЕТ ПОДТВЕРЖДЕНИЯ** | в `mobile/` отсутствуют Android/iOS runners |
| Live RustFS + ClamAV | **НЕТ ПОДТВЕРЖДЕНИЯ** | only mocks/adapters/compose |
| Нагрузка 5000 VU, backup/restore | **НЕ ПОДТВЕРЖДЕНЫ** | k6/ops scripts не являются result evidence |

**Детальные точки покрытия ветвей:** media 46.25%, publicqr 58.89%, inventory 64.10%, security 64.29%, deal 64.34%, catalog 71.05%, order 75.81%. Не вводить порог покрытия «100%» как замену тестам инвариантов; сначала закрыть реальные error/rollback/retry paths.

## 3. Канонические UX-01…UX-16 из USER-JOURNEYS-MVP

| ID | Пользовательское действие | Состояние | Подтверждение / незакрытый путь |
|---|---|---|---|
| UX-01 | Guest сканирует QR и открывает карточку | **P** | `PublicQrJourneyPostgresIT.publishedQrIsStableAndShowsOnlyWhitelistedCatalogFields`; нет device QR-camera, атрибуции scan и полного browser E2E |
| UX-02 | Guest создаёт callback заявку | **P** | `PublicQrJourneyPostgresIT.guestConsentAndIdempotencyProduceLeadNeverOrderOrPlaintext`; Lead сохранён, но отсутствуют Inbox UNREAD/CONTACTED |
| UX-03 | Seller обрабатывает callback | **M** | Только `SellerLeadInboxPostgresIT` подтверждает приватное чтение; нет CONTACTED/DECLINED mutation |
| UX-04 | Buyer регистрируется и входит | **P** | `BuyerEnrollmentPostgresIT`, JWT криптотест; real Keycloak email mailbox + Flutter/browser E2E не подтверждены. **Исторический парольный UX заменён OIDC/Keycloak** |
| UX-05 | Buyer начинает ценовой торг | **M** | BuyerDemand/SellerBid не эквивалентны NegotiationSession |
| UX-06 | Две стороны торгуются → AGREED без Order | **M** | Отсутствуют 5 counter rounds/AGREED state; текущий SellerBid acceptance создаёт Order напрямую |
| UX-07 | Buyer явно создаёт Order | **P** | `AuthenticatedDealHttpPostgresIT.buyerEnrollmentQrDemandOfferOrderAndReserveShareOneDbJourney`, `BidAcceptanceFlowTest`; реальный browser/mobile UAT отсутствует, не реализован AGREED-путь |
| UX-08 | Seller исполняет Order | **P** | `OrderConfirmationPostgresIT.confirmationReservesAtomicallyEmitsOneOutboxAndRetryIsIdempotent`; только CONFIRMED+reserve, нет fulfillment UI/dispatch/READY_FOR_RECEIPT |
| UX-09 | Buyer подтверждает получение | **M** | Отсутствуют ReceiptChallenge, buyer-only receipt API, expiry/replay protection |
| UX-10 | Buyer отменяет заказ | **M** | Реализована seller cancellation; нет buyer cancel/request/dispatch race |
| UX-11 | Buyer оставляет отзыв | **M** | Нет Review entities/API/moderation и receipt eligibility |
| UX-12 | Seller видит репутацию | **M** | Нет qualified seller reputation snapshot/UI |
| UX-13 | Seller видит постоянного клиента | **M** | Нет seller-buyer activity read model/ACL |
| UX-14 | User читает Notification Center | **M** | Order Outbox есть, но нет `user_notification` и read/unread UI |
| UX-15 | Seller входит в Web через QR approval | **M** | Есть Seller JWT/OIDC, **нет** browser-bound QR approval |
| UX-16 | Favorite + Price Watch синхронизированы | **M** | `web/src/App.tsx` содержит лишь локальное demo; активные React/Flutter не используют серверный Favorite |

**Итого по каноническим UX:** **5 P / 11 M / 0 R**. Реализованный новый BuyerDemand→SellerBid→Order имеет собственный backend с тестами, но не заменяет отсутствующий сценарий ценового торга.

## 4. Канонические EDGE-01…EDGE-20

| ID | Negative/race-case | Уровень | Проверка / оставшаяся работа |
|---|---|---|---|
| EDGE-01 | ARCHIVED между QR view и Lead POST | **P** | Добавлен `PublicQrJourneyPostgresIT.archivedBetweenQrViewAndLeadSubmitCannotCreateLeadOrOrder`; проверить новый тест в exact-SHA CI, параллельный race пока не измерен |
| EDGE-02 | Tier/Discount изменился до order quote | **M** | Нет PricingTier/DiscountCampaign |
| EDGE-03 | Два одновременных order create | **P** | Есть retry/uniqueness; нужен отдельный two-thread HTTP acceptance PostgreSQL race |
| EDGE-04 | Flutter App Link сохраняет exact listing после login | **P** | `hybrid_route_policy_test.dart`; нет реального Android/iOS App Link + PKCE context-return |
| EDGE-05 | Чужой номер callback не создаёт связанный Buyer | **A** | `PublicQrJourneyPostgresIT.guestConsentAndIdempotencyProduceLeadNeverOrderOrPlaintext` |
| EDGE-06 | Seller A читает телефон Lead продавца B | **A** | `SellerLeadInboxPostgresIT.onlySellerAdminCanReadEncryptedGuestContactWithAuditableAccess` |
| EDGE-07 | Push недоступен, бизнес-событие не теряется | **M** | Нет Notification Center / FCM channel |
| EDGE-08 | Read-state изменён offline → cross-device sync | **M** | Нет versioned unread Inbox |
| EDGE-09 | Buyer cancel конкурирует с seller dispatch | **M** | Нет buyer cancellation/dispatch state machine |
| EDGE-10 | Approved return перед nightly rollup | **M** | Нет ReturnClaim/qualifying profile pipeline |
| EDGE-11 | 0 completed orders не даёт фиктивного buyer tier | **M** | Нет buyer tier |
| EDGE-12 | 0 reviews не отображает фиктивный seller 0-star | **M** | Нет seller rating |
| EDGE-13 | Recovery без подтверждённого фактора запрещён | **P** | Verified-email/revocation guards есть; полный recovery security E2E отсутствует |
| EDGE-14 | Повтор QR seller-login screenshot | **M** | Нет QR login challenge |
| EDGE-15 | Expired/superseded SellerBid нельзя принять | **A** | `BidAcceptanceFlowTest.staleOrSupersededBidCannotBeAccepted`; user-visible expiry message проверить отдельно |
| EDGE-16 | Order price immutable после изменения Listing | **A** | `CustomerOrderLifecycleRegressionTest.acceptedOrderSealsPriceDeliveryAndSnapshotAtCreation` |
| EDGE-17 | SERVICE/DIGITAL не проходит PHYSICAL dispatch | **M** | Type-specific fulfillment не реализован |
| EDGE-18 | Lead withdrawal без capability запрещён | **M** | Нет capability-based withdrawal |
| EDGE-19 | Nightly job stage failure сохраняет previous published | **M** | Нет durable scheduled profile publication |
| EDGE-20 | Double review за одну покупку | **M** | Нет Review/PG uniqueness |

**Итого по EDGE:** **4 A / 4 P / 12 M / 0 R**. Не отмечать EDGE-03 как полностью проверенный без реального concurrent PostgreSQL API теста.

## 5. Дополнительные реализованные сценарии текущего приложения

Старый `USER-JOURNEYS-MVP.md` местами описывает неактуальный password flow, а `REQUIREMENTS.md` содержит устаревший implementation baseline. Источником фактического поведения служат код и новые implementation-документы.

| Код | Текущий действующий сценарий | Code evidence | Вердикт |
|---|---|---|---|
| LIVE-01 | PUBLIC каталог, search, bounded pagination, никакой приватной карточки | `PublicCatalogHttpPostgresIT.publicOnlySearchPaginationAndPriceWithoutLeakingPrivateRows` | **A**, browser ещё не доказан |
| LIVE-02 | Словарный поиск / HCC / 4 языка | `SearchDictionaryPostgresIT.fourLocalesAndHccParentFilterFindSamePublishedListing` | **A**, утверждение реального справочника pending |
| LIVE-03 | Фотофид и cursor | `CatalogFeedPostgresIT.pageByCursorAndFilterPrivateListingsWithoutMedia`, Vitest `catalog-api.test.ts` | **P**, реальный Playwright/медиа pending |
| LIVE-04 | Seller authenticated image upload, publish, read | `ListingMediaHttpPostgresIT.uploadPublishGalleryReadAndUnpublishAllRespectLiveStatus` | **E**, live RustFS/ClamAV pending |
| LIVE-05 | BuyerDemand creation + bids listing | `AuthenticatedDealHttpPostgresIT` | **A**, frontend/device E2E pending |
| LIVE-06 | SellerBid quote, selection and PLACED | `AuthenticatedDealHttpPostgresIT`, `BidAcceptanceFlowTest` | **A**, не путать с 5-раундовым торгом |
| LIVE-07 | Seller CONFIRMED → multi-line reserve, rollback | `OrderConfirmationPostgresIT` | **A**, 5k-vu pending |
| LIVE-08 | Seller cancellation → inventory release/outbox | `OrderConfirmationPostgresIT.sellerCancellationReleasesEveryReservationExactlyOnceAndPublishesOutbox` | **A**, buyer cancellation отсутствует |
| LIVE-09 | Concurrent Outbox claim/replay/fencing/dead-letter | `OutboxWorkerPostgresIT` | **A**, независимые процессы не квалифицированы |
| LIVE-10 | Signed JWT and ownership + Keycloak buyer enrollment | `SignedJwtCryptographicSecurityIT`, `BuyerEnrollmentPostgresIT` | **A**, external SMTP/device pending |
| LIVE-11 | Flutter WebView React public → native BuyerScreen | `mobile/test/hybrid_route_policy_test.dart`, `web/src/buyer-link.test.ts` | **E**, физическое устройство отсутствует |
| LIVE-12 | CanonicalFeatureAdapter HCC/hard/soft | `CanonicalFeatureAdapterPostgresIT` | **A**, это НЕ обученный ML |
| LIVE-13 | ML-R2 append-only trade facts and seller eligibility decision | `MlTradeEventPostgresIT`, `MlSellerInvitationPostgresIT` | **P**, eligibility НЕ означает delivered/available invitation |
| LIVE-14 | Real deployed browser login → accept bid → confirm → logout/relogin | `web/e2e/staging-buyer.e2e.ts` | **E**, CI только перечисляет тесты |
| LIVE-15 | Нагрузка 5000 пользователей с post-run SQL integrity | `perf/k6/r2-confirm-reserve.js`, `perf/k6/post-run-integrity.sql` | **E**, нет результатов запуска |

## 6. P0 RED-тесты и конкретные тестовые контракты

Каждый новый slice: **S0 Contract → S1 RED → S2 independent review → S3 GREEN → S4 Postgres + browser/device E2E**. До реализации нельзя имитировать «PASS».

| ID | Обязательный тест / вход | Oracle / release blocker |
|---|---|---|
| QA03-001 | Seller UI создаёт DRAFT, 1..N SKU, изображения и публикует | Только собственные товары; QR стабилен; запрещено редактирование чужого/опубликованного SKU |
| QA03-002 | React Web guest открывает 10+ страниц photo feed | Нет дублей/cursor tamper, скрытые объявления не видны, responsive мобильный viewport |
| QA03-003 | PUBLIC→ARCHIVED прямо перед guest Lead POST | Нет Lead/Order/PII, корректные 404/409, отсутствие частичной записи |
| QA03-004 | Buyer регистрируется через real Keycloak и настоящий email | Реальная доставка, verifyEmail, PKCE, buyer profile, logout/relogin; неподтверждённый phone не восстанавливает аккаунт |
| QA03-005 | BuyerDemand → seller eligible → invitation actually available → response | Авторитетное available_at, идемпотентное приглашение, seller ACL, без фиктивных negatives ML |
| QA03-006 | Buyer и Seller выполняют 5 встречных раундов | Невозможно 6-й, stale proposal/expiry/revoke rejected, AGREED не создаёт Order |
| QA03-007 | Buyer принимает последнюю цену и отдельно создаёт Order | Exactly-once Order при 2 concurrent POST; frozen totals; no reserve until seller CONFIRMED |
| QA03-008 | Продавец подтверждает multi-SKU и одновременно меняет stock | Atomic reservation all-or-nothing; no oversell; idempotent Outbox |
| QA03-009 | Buyer отменяет PLACED, Seller пытается CONFIRM | Только один допустимый terminal state, нулевой reservation, корректный Outbox/audit |
| QA03-010 | Buyer просит отмену CONFIRMED одновременно с dispatch | Legal state matrix, без отрицательных остатков и double release |
| QA03-011 | Seller исполняет одну физическую группу | READY_FOR_RECEIPT только после подтверждённого fulfillment evidence |
| QA03-012 | Buyer получает одноразовый Receipt QR и подтверждает | Buyer only, expiry/replay/foreign-order, нельзя seller-self-confirm |
| QA03-013 | Buyer создаёт ReturnClaim с фото | После квалифицированного receipt, owner ACL, immutable order history, safe media |
| QA03-014 | Review после receipt, повторная/конкурентная попытка | Unique(review per order,target), review не разрешён до получения |
| QA03-015 | Favorite/PriceWatch на Web и Flutter | Один серверный record, exact quantity/tier, sync after login/device switch |
| QA03-016 | Notification Center + seller inbox race | Versioned UNREAD/READ/BOOKMARKED, тот же event не создаёт 2 уведомления, push outage не влияет на Order |
| QA03-017 | Seller QR Web approval | 1-use browser-bound challenge, screenshot replay/foreign browser/revoke rejected |
| QA03-018 | Hybrid WebView → native checkout с real AppAuth | Чужой origin blocked; никаких JWT в JS; back/retry; Web+Flutter app link и реальный device UAT |
| QA03-019 | 2+ реальных workers Outbox crash/restart | 1 business effect, bounded retry, dead letters, lease fence |
| QA03-020 | 5k VUs с отдельными заказами и SQL audit | RPS/p95/p99/CPU/GC/errors, zero oversell/duplicate Order/reserve/outbox |
| QA03-021 | Live S3/RustFS + ClamAV upload, corrupt/virus/timeout, deletion | Private bucket, fail-closed scan, orphan reconciliation/lease, scanner and storage outage recovery |
| QA03-022 | Backup → restore clean staging + post-restore smoke | Восстановлены идентичность, catalog, Order, reserves, Outbox, audit; измеренные RPO/RTO |

## 7. Тестовая стратегия по каналам

1. **Java/JPA/PG:** каждый изменяемый business invariant имеет JUnit unit tests и минимум один PostgreSQL Testcontainers negative case. Concurrency, unique triggers, signed JWT/ownership и transaction rollback — только реальная PG.
2. **HTTP/API:** MockMvc с реальным PostgreSQL и авторитетным seller/buyer membership. Проверять 400/401/403/404/409/422, replay, JSON DTO, ограниченную пагинацию и запрет PII.
3. **React:** Vitest для DTO, reducers/error paths; настоящий Playwright против изолированного HTTPS+Keycloak и Playwright mobile viewport. Существующий `--list` не считается запуском E2E.
4. **Flutter:** unit/widget и integration на Android/iOS после создания runners; camera/QR, WebView, AppAuth PKCE/deep links, background/resume, logout и network loss.
5. **Data/ML:** real verified event-time, independent datasets, no future leakage, label windows/consent; no «ML success» на синтетической статистике.
6. **Operations:** независимые storage/ClamAV/SMTP, multi-worker failure, backup restore и 5k load; артефакты с SHA, средой, временем и redacted evidence.

### Конкретные слабые зоны тестирования

- **Media (46.25% branches):** malformed image, decompression bombs, ClamAV unavailable, failed S3 delete, DB rollback after object PUT, orphan GC concurrent workers.
- **Public QR (58.89%):** status transitions, forged token/source, guest anti-abuse, consent revocation, replay, timeout, unauthorized seller disclosure.
- **Inventory (64.10%):** 2+ lines/locations, cancellation vs confirmation, duplicate release, outbox unique failures, locked row contention.
- **Security (64.29%):** subject inactive, wrong audience, email_verified false, cross-seller IDOR, buyer profile retry, token expiry/logout.
- **Deal (64.34%):** concurrent accept, stale bid, conflicting Idempotency-Key, partial basket, expiry, seller origin/delivery.

## 8. Release gate для ВСЕХ P0 пользовательских сценариев

Для каждого `P0` создать evidence entry: `scenarioId`, `exactSha`, `testClass/testName`, `testLevel`, `runUrl`, `executionTime`, `environment`, `pass/fail`, `negativeCases`, `reviewer`, `unresolvedBlockers`. Таблицы в Markdown или успешная сборка **без этого evidence не являются доказательством пользователя**.

Для P0 пользовательский E2E считается RELEASE VERIFIED только если:
- [ ] UI позволяет реальному Guest/Buyer/Seller завершить **именно этот** сценарий без ручного SQL/публикации через приватный API;
- [ ] разработаны и прошли позитивные/негативные HTTP+PostgreSQL тесты, в том числе гонки и нарушения прав;
- [ ] настоящий Playwright или физический Flutter device исполняет соответствующую роль и переходы;
- [ ] есть exact-SHA CI, реальные OIDC/SMTP/Storage evidence, privacy/security approve;
- [ ] независимый S2 reviewer дал APPROVE по принятой матрице;
- [ ] если функция отсутствует, статус **M/BLOCKED**, а не PASS.

**Не объявлять общий production-ready или merge PR #2**, пока не выполнены P0 и эксплуатационные gates. Работать в одной ветке; исторические PR/ADR и начальные спецификации не считать evidence текущего релиза.

## 9. Очерёдность устранения дефектов и доработок

- **Wave 1 — существующий checkout:** закрыть QA03-003/004/007/008, усилить code branch coverage в deal/security/inventory/publicqr; выполнить real browser OIDC + PostgreSQL journey.
- **Wave 2 — Seller Studio + Buyer cancellation:** QA03-001/005/009/010 + seller inbox; без этого невозможно полностью проверить успешный маршрут продавца.
- **Wave 3 — Bargaining + Fulfillment/Receipt:** QA03-006/011/012/013/014; установить единую state machine.
- **Wave 4 — Shared UX и notifications:** QA03-015/016/017/018, включить WebView только после device S2.
- **Wave 5 — operational qualification:** QA03-002/019/020/021/022, затем ML-R2 delivery ledger и ML-R3.

**Следующий исполняемый шаг:** проверить новый PostgreSQL тест архивации Listing между публичным QR GET и Guest Lead POST, затем написать **PostgreSQL RED** для двухпоточного принятия SellerBid и buyer cancellation (пока RED, если отсутствует API). Пройти независимый S2 review точных оракулов; затем реализовать минимальный GREEN. Это точка входа в доказуемый user-journey coverage, а не ещё один документ со списком возможностей.

## 10. Проверка QA03-003 после базового аудита (дополнение)

**Коммит негативного PostgreSQL-теста:** `177ba53fe41b765b22780d815faadfd72120df0c` (следует после SHA базового аудита).  
**GitHub Actions:** [Java run 43511158395](https://github.com/alimbetov/QRM/actions/runs/43511158395) — `mvn test` PASS, `mvn verify` PASS.  
**Проверено в PostgreSQL Testcontainers:** `PublicQrJourneyPostgresIT` **6/6 PASS**, включая новый `archivedBetweenQrViewAndLeadSubmitCannotCreateLeadOrOrder`. Сценарий: GET PUBLIC QR → статус ARCHIVED → GET и POST callback получают 404 → не появляется ни нового PurchaseLead, ни CustomerOrder. Это последовательная state-transition проверка, не одновременная гонка двух PostgreSQL транзакций; UX-01/EDGE-01 остаются PARTIAL до живого browser/UAT и соответствующей гонки.

В этой же точке ветки уже шли параллельные ML-R2 доработки: общий Backend `verify` содержал **105 unit** и **89 PostgreSQL integration** тестов, все PASS. Текущая измеренная JaCoCo точка для указанного коммита: **89.01% line / 63.22% branch** (изменение знаменателя после появления нового ML-кода). Эти показатели **не заменяют** baseline `d075f480...` и не должны произвольно смешиваться с ним.

**Дополнительное S0 противоречие:** мастер-ТЗ упоминает доступ к `UNLISTED` по прямой ссылке, но `PublicQrCatalogService.findPublicLink` в аудированном коде разрешает только `PUBLIC`. Не объявлять direct-link UNLISTED работающим: сначала уточнить security/visibility контракт и написать PostgreSQL RED на переход PUBLIC → UNLISTED → direct access/listing discovery.
