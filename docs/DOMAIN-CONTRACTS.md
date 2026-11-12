# QRM — доменные контракты и бизнес-инварианты R0

**Статус:** проектные контракты v1.4, требуют S2 review. [Главное ТЗ](REQUIREMENTS.md) · [решения](DECISIONS.md).

## 1. Aggregate ownership

| Aggregate root | Модуль | Сущности/состояния | Ключевой инвариант |
|---|---|---|---|
| User | identity | roles, phone-as-login, password credential and sessions | одно нормализованное телефонное значение на аккаунт; phone unverified |
| SellerProfile | seller | name, logoMediaId, slug | только владелец/роль может менять |
| Product/Category | catalog | products, metric units | модерируемые категории |
| Listing | listing | translations, media, publication | PUBLIC/UNLISTED только с 3–5 фото, базовой ценой и одной локалью |
| PriceBook | pricing | tiers, campaigns | единственная определённая effective price для unit+quantity+instant |
| QrCampaign | qr | tag, target, counters | неизменный publicId; только публично доступная цель |
| PurchaseLead | lead | callback request, withdrawal | отсутствие создания Order из Web lead |
| NegotiationSession | negotiation | offers, rounds, expiry | max 5 встречных раундов, один accepted final offer |
| CustomerOrder | order | immutable snapshot, cancellation | единый источник подтверждённой стоимости |
| Fulfillment | fulfillment | delivery/service/digital, claims | исполнение соответствует listingType |
| Favorite | favorite | priceWatch, user preference | уникальная пара user+listing |
| SellerInboxItem | inbox | read-version, bookmark | метки персональные, не business status |
| Review | rating | verified proof, photos | рейтинг товара только после исполнения |
| ViewAggregate | analytics | raw events, daily aggregates | публичный счётчик из засчитанных событий |

Межмодульные зависимости через контракты; доступ в чужие таблицы напрямую из domain/application запрещён. Для внешнего взаимодействия события с outbox и идемпотентные обработчики.

## 2. Типы единиц, денег и цен

- Товар: `PHYSICAL, SERVICE, DIGITAL`. Метрические `PCS, KG, G, L, ML, M, M2, M3`, для услуг `HOUR, SERVICE`, для цифровых предложений `LICENSE` (до утверждения).
- Количество `Decimal`, положительное; для `PCS` и `LICENSE` только целое. Валюта только `KZT` для MVP; хранить `currencyCode` явно.
- Базовая цена и до 4 дополнительных tiers: полузамкнутые интервалы `[min, max)` с открытым концом; нет перекрытий, произвольные пробелы требуют согласованного fallback на базовую цену.
- Скидка: `FIXED_UNIT_PRICE | DISCOUNT_PERCENT`, `validFrom` включительно и `validUntil` исключительно; хранение UTC с отдельным merchant timezone. Нельзя создать конфликтную скидку на одну quantity/timestamp.
- Калькулятор: ввести listing, количество, момент расчёта; выбрать tier, применить максимум одну действующую campaign, округлить по отдельной политике для KZT, сохранить `priceQuoteId, pricingVersion, expiresAt` и слепок использованного правила.
- Переговорная цена не меняет каталог и не рассылает массовые push об изменении публичной цены.
- Order хранит копию публичных условий + acceptedOfferId (если был), unitPrice, quantity, total, currency, tax treatment (пока TBD), дату согласования.
- При публикации/редактировании при изменении effective price создаётся `EffectivePriceChanged` и отдельно пересчитывается watch.

## 3. Матрица состояний

### 3.1 Listing

| Откуда | Куда | Актор | Условие |
|---|---|---|---|
| DRAFT | PUBLIC/UNLISTED | seller | полная валидация |
| PUBLIC | UNLISTED | seller | опубликовано |
| UNLISTED | PUBLIC | seller | опубликовано |
| PUBLIC/UNLISTED | ARCHIVED | seller/admin | история сохранена |
| ARCHIVED | DRAFT | seller | правки через новый publish check (предлагаемая политика) |

Любой другой переход запрещён. Архивный QR не должен показывать private информацию.

### 3.2 Web PurchaseLead

| Откуда | Куда | Кто | Эффект |
|---|---|---|---|
| — | NEW | guest | телефон + согласие + количество + желаемая цена |
| NEW | VIEWED | seller | просмотр в кабинете |
| NEW/VIEWED | CONTACTED | seller | отметка об обратном звонке |
| NEW/VIEWED/CONTACTED | DECLINED | seller | отказ без заказа |
| NEW/VIEWED | WITHDRAWN | заявитель | одноразовый секретный токен, выданный для этой заявки (не доказывает владение телефоном) |
| NEW/VIEWED | EXPIRED | system | retention/business TTL |
| CONTACTED | EXPIRED | system | закрытие по retention отдельно от удаления |

Принятие цены по телефону не переводит Lead в Order автоматически. Действия продавца в кабинете и отметка `read` независимы.

### 3.3 NegotiationSession (Flutter)

| Действие | Условие | Итог |
|---|---|---|
| create offer | активное Listing, authenticated buyer | OPEN |
| seller accept | offer ещё действителен, version совпадает | AGREED |
| seller counter | в пределах 5 раундов, expiry в будущем | COUNTER_PENDING |
| buyer accept counter | offer действителен | AGREED |
| reject | уполномоченная сторона | REJECTED |
| withdraw | покупатель до AGREED | WITHDRAWN |
| expire | предложение просрочено | EXPIRED |
| create order from AGREED | явная команда, один раз | ORDER_CREATED / linkedOrderId |

**Рекомендованная точность лимита:** раунд = один новый контроффер в ответ на действующее предложение, до 5 за сессию; отдельное утверждение на S2. AcceptedOffer нельзя преобразовать в два заказа, включая параллельные запросы.

### 3.4 CustomerOrder

| Состояние | Разрешённые переходы | Актор |
|---|---|---|
| PLACED | CONFIRMED, CANCELLED_BY_BUYER, CANCELLED_BY_SELLER | buyer/seller по действию |
| CONFIRMED | PROCESSING, CANCELLATION_REQUESTED | seller/buyer |
| PROCESSING | COMPLETED (только после подтверждения RECEIVED), CANCELLATION_REQUESTED, CLAIM_PENDING | по типу исполнения |
| CANCELLATION_REQUESTED | CANCELLED, CONFIRMED/PROCESSING (с обоснованием), CLAIM_PENDING | authorized workflow |
| COMPLETED | CLAIM_PENDING (отдельный claim), остаётся completed | buyer |
| CANCELLED_* | terminal | none |

Это **предварительная** матрица; требуется раздельно сформировать полную state machine для physical, service, digital. Нельзя обходить законное право потребителя на отказ внутренней машиной состояний. Финансовый статус `PAID` не устанавливается без подтверждённого источника платежа.

## 4. Конкурентные сценарии

1. Два запроса на accepted offer → ровно один linkedOrderId (unique constraint + transactional lock/version).
2. Одновременный accept и withdraw → один допустимый terminal outcome, конкурентная сторона получает 409.
3. Истечение акции во время создания заказа → расчёт по фиксированной серверной временной точке и транзакционной версии.
4. Отмена заказа и передача в доставку → заранее определённый выигравший transition; второй запрос получает конфликт.
5. Новое сообщение между fetch деталей и mark-read → readActivityVersion не может обогнать unseen incomingActivityVersion.
6. Два вызова keyset pagination с постоянным snapshot → без повторов/пропусков.

## 5. Правила целостности

- `unique(userId, listingId)` Favorite, `unique(listingId, locale)` Translation, `unique(sellerId, subjectType, subjectId, userId)` InboxState; verified ratings unique by `(orderId,targetType)` and target is derived from immutable Order.
- `unique(acceptedOfferId)` на создание Order из Negotiation, включая nullable semantics.
- `unique(requestActorId, idempotencyKey, operationKind)` для mutable operations.
- Строгий tenant/owner predicate во всех seller queries, включая списки, count и cursors.
- Аудит: actor, action, old/new state, version, timestamp, traceId, без избыточного PII.
- В R0 проектировать индексы и constraints параллельно с API oracle; не полагаться на in-memory validators.

## DEC-27 invariants

Phone is only an unverified login identifier, no OTP confirmation. Passwords hashed Argon2id; access/refresh sessions can be revoked; no silent account recovery by phone alone. Notification Center is a distinct projection from seller Inbox: reading one doesn't mark the other or affect business state. [Security contract](AUTH-NOTIFICATIONS.md).

## MVP consistency decisions (R0 recommendations)

[TECHNICAL-REVIEW-MVP.md](TECHNICAL-REVIEW-MVP.md) определяет canonical Negotation enum, buyer-only command создания Order после AGREED, различие Fulfillment READY_FOR_RECEIPT и Order COMPLETED, независимые seller/product ratings. **Пять встречных сообщений** как определение лимита — PROPOSED до S2. Состояния `ORDER_CREATED` не являются заказом, пока транзакция Order+Outbox не закоммичена.

## Second-pass critical invariants

- **Fulfillment:** READY_FOR_RECEIPT продавца != RECEIVED покупателя; нажатие продавцом «выполнено» не создаёт право на рейтинг и не улучшает торговый уровень. Случаи отсутствия подтверждения разрешаются отдельной процедурой, не auto-completion.
- **Lead:** секрет отзыва одноразовый, криптостойкий, хранится хеш, TTL/rate-limit; совпадение телефона не связывает с зарегистрированным аккаунтом.
- **Reviews:** по одному независимому seller/product отзыву на квалифицированный Order; отзыв всегда указывает продавца/объявление из снапшота заказа, не из клиентского payload; модерация меняет рейтинг при rebuild.
- **Money:** server-side BigDecimal и immutable Order price/terms snapshot; оффер не может менять каталог, при переходе AGREED→Order проверяются актуальные права, expiry и условия согласования. Числовая шкала KZT и округление проходят S2 до реализации.
- **Identities:** телефон без подтверждения — alias для логина, не гарантирует владение и не допускает восстановления доступа по номеру.

[R2 technical review](TECHNICAL-REVIEW-R2.md).

## R0.3 fulfillment / prices

- Наличные расчёты: отдельно от immutable Order total вычисляется **округлённый итог платежа** для информирования сторон; QRM не подтверждает внешнюю оплату. По проектной денежной политике сохраняются KZT 2 decimals, сумма округляется после quantity × price/discount, а не по каждой единице. Формула и налоговые правила требуют S2/legal Review.
- Продавец переводит Fulfillment в READY_FOR_RECEIPT, создаёт одноразовый привязанный к Order QR challenge; **только аутентифицированный покупатель данного заказа** после явной команды подтверждения переводит в RECEIVED/Order COMPLETED. Проверить TTL, rate-limit, replay и конкурентные обращения. Само сканирование или ввод кода продавцом не является подтверждением. Отказ покупателя ведёт к dispute, без автоувеличения уровня.
- Детали схемы и оракулы: [MVP-DECISIONS-R03.md](MVP-DECISIONS-R03.md).
