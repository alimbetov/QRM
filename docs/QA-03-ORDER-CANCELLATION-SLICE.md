# QA-03 — Исполненный P0-срез: конкурентное принятие SellerBid и отмена PLACED

**Дата:** 2026-11-14  
**Рабочая ветка:** `docs/adr-002-jpa-architecture-review`  
**Статус:** Code GREEN, external UX/operational S2 OPEN; не production release.  
**Backend GREEN evidence SHA:** `d87cac6b08c254766e5a12d12d0a4e2270e25ed5`; последующие изменения требуют отдельного exact-SHA CI.

## 1. Два закрытых серверных сценария

### QA03-007 / EDGE-03 — конкурентное принятие

`AuthenticatedDealHttpPostgresIT.twoConcurrentHttpBidAcceptancesCreateExactlyOnePlacedOrder` запускает два независимых MockMvc HTTP POST на один SellerBid/Demand с разными Idempotency-Key одновременно, на реальном PostgreSQL 17 (Testcontainers).

**Оракул:** ровно один HTTP 201 и один 409, один `customer_order`, ровно один `bid_acceptance`, состояние `PLACED`, без резервирования до seller confirmation. Сервис `BidAcceptanceService` использует PESSIMISTIC_WRITE lock на `buyer_demand_snapshot`, unique constraints — последний рубеж.

### QA03-009 / UX-10 — buyer cancellation

**Новый REST endpoint:** `POST /api/v1/buyers/orders/{orderId}/cancel`  
**Auth:** JWT `SCOPE_deal:write` + server-side active BuyerAccountLink; foreign seller/иной Buyer получают запрет, только buyer order owner.  
**Успех:** HTTP 204, в том числе безопасный повтор тем же покупателем.  
**Недопустимое состояние:** HTTP 409 (CONFIRMED, PROCESSING, любая иная стадия).  
**Неизвестный заказ:** HTTP 404.  
**Unauthenticated:** HTTP 401.  
**Атомарность:** `CustomerOrder(PLACED → CANCELLED_BY_BUYER)` + `order_outbox_event(ORDER_CANCELLED_BY_BUYER)` в одной PostgreSQL transaction; изменяется только status. Отсутствие inventory reservations перед cancellation — обязательная проверка, чтобы не скрывать повреждённый PLACED order. `totalAmount`, `deliveryFee`, `acceptedBidId`, lines неизменяемы.

**Тесты:**
- `AuthenticatedDealHttpPostgresIT.buyerCanCancelPlacedOrderExactlyOnceButCannotCancelConfirmedOrder` — denied/no token/foreign buyer, 204+replay, no reserve/outbox duplicate, late seller confirm denied, CONFIRMED buyer cancel 409.
- `AuthenticatedDealHttpPostgresIT.buyerCancellationRacingSellerConfirmationLeavesOneLegalOutcome` — при двух потоках только `204 + 409`: либо CANCELLED_BY_BUYER без резерва и confirm event, либо CONFIRMED с ровно одним reservation и без cancel event.
- `CustomerOrderLifecycleRegressionTest.buyerCancellationIsPlacedOnlyAndNeverRewritesEconomicSnapshots` — domain state invariants.
- `web/src/buyer-api.test.ts` — owner-scoped POST, 204, 403/409 и отсутствие fake success.
- `mobile/test/zakaz_api_test.dart`, `mobile/test/mobile_screens_test.dart` — POST bearer, подтверждение в AlertDialog, повторный GET статуса из Backend, отсутствие прежней кнопки при CANCELLED.

**Новый UI:** React `BuyerFlow` отображает «Отменить заказ до подтверждения» только при `PLACED` и требует `window.confirm`. Flutter `BuyerScreen` использует системное диалоговое подтверждение. После 204 оба клиента перечитывают Order: нельзя фальсифицировать CANCELLED state при timeout/409.

**Staging Playwright:** `web/e2e/staging-buyer.e2e.ts` дополнен вторым заказом и UI отменой; после отмены попытка продавца CONFIRM должна дать 409. **НЕ** выполнен на живом staging: Web CI только компилирует/перечисляет. Для независимой E2E нужны настоящие synthetic users, Keycloak, browser, PostgreSQL и redacted evidence.

## 2. RED/GREEN CI

| Коммит / gate | Результат |
|---|---|
| RED `86ef216d3b35a15ad17fef20e8df63eb1030eff6` | HTTP Cancel: ожидаемый FAIL 403 вместо 204 до реализации; одновременно race acceptance test |
| GREEN `1af275343963deba42f1d79a3ad2a2a9c059803f` | Server Service + security route + outbox + status; Java Backend CI PASS |
| UI `74047570587b49b3a4096f6c9274788fea5120fb` | React Vitest/build и Flutter analyze/widget/unit PASS |
| Race `d87cac6b08c254766e5a12d12d0a4e2270e25ed5` | [Java CI 43513242321](https://github.com/alimbetov/QRM/actions/runs/43513242321): 105 Surefire + 95 Failsafe PASS, 0 failures |
| UX E2E | Playwright scenario authored; real staging execution **not done** |
| Independent S2 | **Not approved**, PR remains Draft, no merge |

В логах GREEN `d87cac6`: JaCoCo line 89.21%, branch 63.52% (новый код/тесты меняют denominator; значения не заменяют предыдущие baseline).

## 3. Архитектура и ограничения

- Никаких новых Flyway миграций: `CANCELLED_BY_BUYER` уже входил в JPA OrderStatus, status хранится как VARCHAR; `order_outbox_event` поддерживает новый тип в своём `event_type`.
- Резервирование происходит только в `CONFIRMED` через существующий `OrderConfirmationService`; отмена `PLACED` **не** освобождает и не создаёт резервы.
- Order locking `CustomerOrderRepository.findForUpdate` сериализует seller confirmation / buyer cancellation и препятствует double transitions.
- Заказы `CONFIRMED` не отменяются покупателем автоматически. Нужен отдельный будущий `CancellationRequest` с seller approval, dispatch guards, compensation, notification и audit — **отдельный RED/GREEN**.
- Внешний OIDC JWT, SMTP, физический Android/iPhone, push и оплата **не** тестировались данным изменением.

## 4. Следующий P0 порядок

1. Запустить **real staging Playwright** и Android/iOS AppAuth/WebView E2E; не путать Playwright `--list` с фактическим тестом.
2. Seller Inbox/Studio: продавец получает событие, видит заказ и исполняет его в собственном UI (сейчас seller API only).
3. CancellationRequest для CONFIRMED и guarded fulfillment: PROCESSING/READY_FOR_RECEIPT, затем buyer receipt.
4. Возврат/спор/отзывы после verified receipt.
5. S2 review + KZT/inventory correctness + 5k VUs + production rollout gates.

**Не менять `main`, не удалять другие ветки, не считать новый endpoint основанием для production activation.**
