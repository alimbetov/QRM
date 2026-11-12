# DEC-24 — Customer Activity Aggregator и торговая классификация

**Статус:** APPROVED — бизнес-концепция. **Формулы/пороги:** PROPOSED до S2 Review.  
**Дата:** 2026-11-12 · [Основное ТЗ](REQUIREMENTS.md)

## 1. Цель

Помочь продавцу понимать торговую историю покупателя, наличие повторных сделок и актуальность отношений. Система не должна создавать видимость проверки платёжеспособности, добросовестности или финансового статуса клиента.

Различать три независимые оси:
- **Торговый уровень за 9 месяцев:** Путник → Исследователь → Купец → Мастер → Легенда. Значок отражает активность в QRM, **не подтверждает личность или платёжеспособность**.
- **Жизненный цикл:** Новый / Активный / Снижение активности / Спящий / Вернувшийся.
- **Отношение к конкретному продавцу:** Новый для продавца / Покупал ранее / Постоянный клиент / Давно не покупал.

Если клиента нет в партиции текущего месяца, его уровень **не сбрасывается** автоматически в «Путник». Отсутствие события ≠ отсутствие прошлой истории.

## 2. Источники и доверенность сигналов

| Событие | Для торгового уровня | Для Seller relationship |
|---|---|---|
| QR visit, просмотр страницы | не влияет | только агрегат посещаемости, без установления личности |
| Favorite, price watch | не влияет на торговый уровень | заинтересованность, если покупатель известен |
| Web callback lead | не является подтверждённой покупкой | обращение продавцу, не объединять автоматически по телефону |
| Price offer / AGREED | отдельная метрика намерения | интерес/переговоры, не покупка |
| Order PLACED / CONFIRMED | статус воронки | текущая активность |
| Fulfillment confirmed / received | основной кандидат для учёта сделки | проверяемая завершённая покупка |
| Return, cancellation | корректировка статов по обоснованным правилам | показывать контекст, не штрафовать автоматически |
| Seller manually contacted | факт контакта | не подтверждает продажу |

Сделки QRM без собственного эквайринга **не подтверждают факт оплаты**. Для исключения самонакрутки должны применяться контроль уникальности ордеров, статусы подтверждения обеих сторон при необходимости, обнаружение аномалий. Отзыв возврата сам по себе не означает недобросовестного поведения.

## 3. Временная модель

- **Скользящее окно:** [текущий UTC timestamp минус 9 календарных месяцев; текущий timestamp]. Для граничных дней (конец месяца) определить согласованную семантику календарного вычитания и UTC.
- **Monthly buckets:** YYYY-MM календарные периоды UTC, используются для агрегации и индексов, но не подменяют точное rolling window. Для расчёта на границах нужна дневная granular stats либо выборка из событий в частичных месяцах.
- При смене месяца/даты и поздних событиях (возврат, исправление, отмена) пересчёт осуществляется автоматически.
- Партиции событий по `event_month` PostgreSQL RANGE с опережающим созданием и monitoring default partition (при необходимости). Партиционирование **месячных агрегатов** — только если подтверждено нагрузкой и эксплуатационной выгодой; не обязательное требование MVP.
- История старше девяти месяцев не удаляется лишь по причине окончания окна. Retention — отдельное privacy/legal решение.

## 4. Данные и агрегаты

### customer_activity_event (опциональная материализованная проекция из доменных событий)
`event_id, user_id, event_type, occurred_at, event_month, source_aggregate_type, source_aggregate_id, seller_id?, quantity?, currency?, event_version`. Idempotent `event_id`; партиционированные constraints проектируются с учётом partition key.

### customer_daily_stats (для точных границ 9M)
`user_id, stat_date, qualifying_completed_orders, repeat_orders, offers, returns, last_qualifying_activity_at`. Уникальность `(user_id, stat_date)`; индекс для 9M.

### customer_monthly_stats
`user_id, period_month, qualified_orders, active_days, active_month, repeat_orders, claims_count, last_order_at, last_activity_at, calc_version`. Уникальность `(user_id, period_month)`. При необходимости materialized from daily.

### customer_rolling_score
`user_id, window_start, window_end, qualifying_orders_9m, active_months_9m, last_qualifying_at, lifecycle_segment, trade_tier, classification_rule_version, calculated_at`. PK user_id.

### seller_customer_stats
`seller_id, buyer_user_id, first_order_at, last_order_at, qualifying_orders_9m, lifetime_qualifying_orders, repeat_buyer, relationship_segment, calculated_at`. Unique `(seller_id, buyer_user_id)`. Не создавать профиль контакта для неизвестного Web-lead автоматически; связать с аккаунтом только по проверенному владению.

### classification_rule_set
`rule_version, effective_from, rule_json, status, approved_by, published_at`; конфигурация immutable и audit.

**Производительность:** API читает готовые summary; пересчёт через Outbox worker с checkpoint/retry и дедупликацией. Агрегаты не должны обновляться последовательным сканированием всех месячных партиций при каждом GET. При пересчёте нужны reconciliation/rebuild и полная отслеживаемость исходных событий.

## 5. Примерные уровни — не утверждённые пороги

| Уровень | Пример кандидата |
|---|---|
| Путник | 0–2 квалифицируемые сделки |
| Исследователь | >=3 сделки в >=2 активных месяцах |
| Купец | >=10 сделок в >=3 активных месяцах |
| Мастер | >=25 сделок в >=5 активных месяцах |
| Легенда | >=50 сделок в >=7 активных месяцах |

Пороговая логика применяется в порядке от самого высокого уровня вниз. Числа — **гипотезы**, требуют анализа распределений реальных пользователей и проверки антинакрутки. Статусы нового/спящего рассчитываются отдельно. Не понижать уровень за одну жалобу/возврат автоматически.

## 6. Seller-facing UX

В Seller Inbox рядом с **аутентифицированным покупателем** по реальной сделке или Flutter-предложению:
- маленький значок торгового уровня, подпись «Активность в QRM за 9 месяцев»;
- «Новый для вас / Покупал ранее / Постоянный клиент» (только если продавец имеет фактическую историю сделок с ним);
- общее число подтверждённых исполнений у этого продавца и дата последнего заказа, если доступ разрешён;
- отметка актуальности данных и понятное пояснение «не кредитный рейтинг / оплата платформой не проверена».

Для Web callback lead без верифицированной QRM-идентичности отображать **«История покупок неизвестна»**, не угадывать пользователя по номеру телефона. Продавцу нельзя открывать покупки этого покупателя у других продавцов или подробный профиль вне текущего обращения. Для нового аккаунта статус «Новый», даже если истории для ранжирования ещё нет; не писать «Путник» как негативный ярлык.

## 7. API proposal

```http
GET /api/v1/me/customer-tier
GET /api/v1/seller/customers?segment=RETURNING&limit=30&cursor=...
GET /api/v1/seller/customers/{buyerUserId}/relationship
GET /api/v1/seller/inbox?customerSegment=REPEAT&limit=30&cursor=...
```

Seller relationship endpoints require verified seller ownership/participation in prior transaction. Public user-level score exposes only user-consented tier; never expose private underlying transaction counts globally.

## 8. Safety, privacy and fairness

- Предоставлять продавцу только необходимый **контекст своих** взаимодействий с покупателем.
- Возраст аккаунта, мало покупок и перерывы не трактовать как плохую репутацию; причина отсутствия данных — UNKNOWN.
- Никакой автоматической блокировки заказа/отказа по tier; уровень — информационный сигнал, не юридическое решение.
- Защита от накрутки: self-dealing, отменённые/дублирующиеся заказы, подставные аккаунты, фиктивное «получено» — квалификация до публичного рейтинга.
- Контролировать privacy и применимое законодательство Казахстана о персональных данных, доступах, целях обработки и retention.

## 9. Test oracles (S0 → S1 RED)

| ID | Scenario | Expected |
|---|---|---|
| CAA-01 | Аккаунта нет в текущем месяце, есть 20 сделок за 9M | старый уровень сохраняется; lifecycle может стать inactive |
| CAA-02 | 9M пересекает первый/последний частичный месяц | считаются только события внутри точного окна |
| CAA-03 | тот же Outbox event replay x3 | одно влияние на метрики |
| CAA-04 | сделка отменена после подсчёта | компенсирующее событие и reconciliation без двойного вычета |
| CAA-05 | один покупатель у двух продавцов | у каждого свой relationship; чужие транзакции не открываются |
| CAA-06 | новый Web lead с совпавшим телефоном | не связывается с user без верификации |
| CAA-07 | новое правило версии N+1 | вычисление воспроизводимо, история версий сохранена |
| CAA-08 | новый пользователь без сделок | статус «Новый», отсутствие данных не означает плохую репутацию |
| CAA-09 | месяц без действий | деградирует activity lifecycle по правилам, но не произвольный tier |
| CAA-10 | partial-window return after 9M boundary | корректный расчёт и событийный replay |
| CAA-11 | seller A запрашивает stats buyer у seller B | 403/404 без утечки |
| CAA-12 | read API во время пересчёта | показывает последний консистентный snapshot с calculatedAt |

**Release gates:** independent S2 review of score thresholds + anti-abuse + data privacy, PostgreSQL Testcontainers for partial boundaries and partitions, load tests, React/Flutter labels, reconciliation metrics and error budget.

## Implementation sequencing clarified after technical review

Logical `customer_daily_stats` and `customer_monthly_stats` definitions are **optimization candidates**, not a requirement to create all tables before MVP. First calculate exact rolling 9M using auditable indexed qualified-order event times and publish one nightly snapshot. Daily/monthly rollups and partitioning are deferred unless load evidence requires them. Canonical minimal pipeline: [NIGHTLY-PROFILE-AGGREGATION.md](NIGHTLY-PROFILE-AGGREGATION.md), [TECHNICAL-REVIEW-MVP.md](TECHNICAL-REVIEW-MVP.md).

## R0.3 tier proposals

For the lightweight MVP, preliminary buyer 9M levels by qualified receipts are 0–2/3–9/10–24/25–49/50+; seller levels are 0–4/5–19/20–49/50–149/150+. No minimum active-month requirement for **tier** in MVP; lifecycle activity is separate. New / no-history is a neutral profile label, never a negative trust marker. These thresholds **are proposed, not final S2-approved**; version them and test all boundaries. Avoid counting seller self-confirmation without buyer receipt. See [MVP-DECISIONS-R03.md](MVP-DECISIONS-R03.md).
