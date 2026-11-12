# QRM — R0 Scenario Design and qualification

**Status:** initial executable-test planning, not implementation. Requirements: [REQUIREMENTS.md](REQUIREMENTS.md).

## Roadmap

| Phase | Scope | Exit gate |
|---|---|---|
| R0 | scenario design, domain invariants, ER, state diagrams and draft OpenAPI | independent review of test oracles |
| R1 | Password Identity (without SMS/OTP), seller profile, logo | ownership/security integration tests |
| R2 | category, product, listing, translation, image, YouTube | public visibility + media validations |
| R3 | quantity pricing and scheduled discounts | price boundary/time/concurrency tests |
| R4 | QR attribution and React storefront | QR→page and public views E2E |
| R5 | Web purchase lead, withdrawal, callback consent/anti-spam | spam/PII/withdrawal tests |
| R6 | Flutter, App Links, Seller and Buyer UI | device E2E |
| R7 | favorites, price watch, push | effective-price notification idempotency |
| R8 | price negotiation, orders, buyer cancellation | transaction/state-machine regression |
| R9 | physical, service, digital fulfillment, return | type-specific transition matrix |
| R10 | ratings, reviews with images | verified-order eligibility |
| R11 | aggregated public and private analytics | views/QR-conversion accuracy |
| R12 | security, recovery, performance, release | exact-SHA fullstack qualification |

## R0 high-priority scenarios

| ID | Given / when | Expected |
|---|---|---|
| ID-01 | valid phone login + password | creates user with unique normalized login and phoneVerified=false |
| ID-02 | invalid password, brute force | rejected, same public error and throttled |
| SEL-01 | owner changes seller logo | profile and all listing pages show updated logo |
| SEL-02 | other account updates seller | 403, no mutation |
| CAT-01 | category missing | category suggestion, not immediate public category |
| LST-01 | three photos, KZT price, one RU description | publication works; untranslated languages fallback |
| LST-02 | draft with only two photos | draft valid; publication blocked |
| LST-03 | unlisted listing | accessible by URL but not directory |
| LST-04 | archived listing | history readable; new leads/orders disallowed |
| MED-01 | forged MIME/unsafe file | image rejected |
| VID-01 | off-domain video URL | validation rejects URL |
| PRI-01 | quantity at tier boundary | exactly one correct effective price |
| PRI-02 | overlapping tiers | rejected atomically |
| DSC-01 | campaign begins at designated seller timezone | effective price updates at correct UTC instant |
| DSC-02 | two discounts overlap on same tier | forbidden |
| DSC-03 | confirmed order followed by price change | snapshot remains unchanged |
| QR-01 | listing updated after QR printed | QR publicId stable |
| QR-02 | same listing has multiple QR sources | per-source attribution |
| WEB-01 | React listing loaded | logo, public view count, grey inactive favorite |
| LEAD-01 | phone + callback consent + proposed price | lead created, no reservation/order |
| LEAD-02 | lead requester withdraws before closure | WITHDRAWN, seller notified, cannot convert |
| LEAD-03 | other user accesses lead phone | forbidden |
| LEAD-04 | Web lead submitted and seller marks CONTACTED | no CustomerOrder automatically created |
| LEAD-05 | seller gets Web lead with phone | seller can call back; callback consent recorded and phone protected |
| APP-01 | Flutter App Link to listing | exact listing opens |
| FAV-01 | same favorite added twice | one record |
| FAV-02 | watch quantity 50 | price change compares 50-unit tier |
| PUSH-01 | same event processed twice | one logical notification |
| PUSH-02 | price notifications disabled | no marketing push |
| NEG-01 | five counter rounds exhausted | sixth counter rejected |
| NEG-02 | active listing, finished negotiation | new negotiation permitted |
| NEG-03 | buyer withdraws open offer | cannot accept withdrawn offer |
| NEG-05 | Flutter seller accepts buyer price | negotiation AGREED, not an Order yet |
| NEG-06 | Flutter agreed offer converted twice | exactly one Order with acceptedOfferId snapshot |
| NEG-04 | offer past seller expiry | cannot accept |
| ORD-01 | buyer tampers with price | server trusted price and snapshot |
| ORD-02 | duplicate POST with same key | one order |
| ORD-03 | concurrent accept/reject | one terminal outcome |
| ORD-04 | confirmed order + listing price change | order price immutable |
| ORD-05 | buyer cancels PLACED unconfirmed order | CANCELLED_BY_BUYER, atomic and audited |
| ORD-06 | buyer requests cancellation of CONFIRMED before execution | CANCELLATION_REQUESTED; seller informed |
| ORD-07 | cancellation and seller dispatch race | deterministic transition; no invalid terminal states |
| ORD-08 | buyer recreates order after cancellation | new order ID/history; allowed active listing |
| FUL-01 | service/digital order attempts physical IN_TRANSIT | rejected |
| RET-01 | return with reason/comment/photos | claim with secure evidence |
| REV-01 | before receipt | product rating forbidden |
| REV-02 | receipt then return | verified review allowed |
| ANA-01 | react re-render, prefetch or bot | does not inflate counted views |
| ANA-02 | analytics outage | page still accessible |
| SEC-01 | public listing response | no private phone/address |
| OPS-01 | outbox event replay | idempotent processing |
| OPS-02 | restore PostgreSQL backup | recovery verified |

## Domain state review blockers

- Explicitly distinguish **PurchaseLead**, **PriceNegotiation**, **CustomerOrder**, **ReturnClaim**, **CancellationRequest**. No overloaded "cancelled" value across different aggregates.
- Buyer may unilaterally withdraw a Web lead and an unaccepted price proposal; buyer may cancel an unconfirmed order. For confirmed/in-progress orders, define buyer cancellation request and legal exceptions without waiving statutory rights.
- Each transition needs actor, guard, expected effects, race conflict behavior, notification and audit record.
- Clarify what exactly counts as a negotiation round (counter versus offer/counter pair) before implementing the five-round gate.
- Confirm product-type-specific service and digital completion/refund semantics.
- Define KZT rounding, campaign boundaries, mobile marketing consent, analytics retention and legal text.

## Development protocol

1. **S0 Scenario Design** — expected outputs, negative paths, actors, permissions, idempotency, race conditions.
2. **S1 RED Tests** — JUnit 5, Testcontainers PostgreSQL, Liquibase, REST contracts, Playwright, Flutter widget and device integration.
3. **S2 Independent Test Review** — independent assessment of test assertions and business invariants before implementation.
4. **S3 GREEN Implementation** — minimal code to satisfy approved tests and architecture.
5. **S4 Qualification** — regression, security, exact-SHA CI, real-media/storage, native UI, backup/restore evidence.

No automatic merge into main without approved PR review. This repository currently contains specifications, not finished software.


## Seller Inbox — сценарии DEC-19…23

| ID | Given / when | Expected |
|---|---|---|
| INB-01 | новая Web-заявка продавцу | непрочитанная, счётчик +1 |
| INB-02 | продавец открывает детализацию | прочитана текущая версия, счётчик уменьшается |
| INB-03 | продавец вручную отмечает как непрочитанное | запись снова в UNREAD на Web и Flutter |
| INB-04 | продавец добавляет закладку | в BOOKMARKED, бизнес-статус не меняется |
| INB-05 | продавец снимает закладку | из BOOKMARKED исчезает, запись не удаляется |
| INB-06 | новое входящее предложение после прочтения | становится непрочитанным, закладка сохраняется |
| INB-07 | продавец читает старую версию, параллельно приходит новая | новая активность остаётся непрочитанной, гонка не теряет событие |
| INB-08 | смена Web → Flutter | метки и счётчики совпадают после синхронизации |
| INB-09 | другой продавец запрашивает чужие inbox ID | 403/404 без утечки данных |
| INB-10 | повторное PUT read/bookmark | идемпотентно, без ложного увеличения счётчиков |
| INB-11 | cursor pagination при 1000+ записях | нет дублей в статическом наборе, есть детерминированный tie-break |
| INB-12 | сочетание type, unread, bookmarked и статуса | корректная серверная фильтрация и nextCursor |
| INB-13 | отзыв/отмена заявки покупателем | входящее изменение непрочитано, закладка сохраняется |
| INB-14 | собственное действие продавца | не создаёт для него новое непрочитанное событие |

**Test-first:** R2DBC Testcontainers/PostgreSQL для индексов и конкурентного versioned read, API authz, React/Flutter UI фильтров и badge counters, QR-auth replay/expiry/security. R0 независимо утверждает семантику read state и точный контракт cursor.

## Дополнительные S0/S1 test specs

Доменная state matrix и concurrency: [DOMAIN-CONTRACTS.md](DOMAIN-CONTRACTS.md). API error and cursor: [API-SPEC.md](API-SPEC.md). Reactive transaction/replay/security test plan: [TEST-PLAN.md](TEST-PLAN.md). Утверждение tests требует независимого S2 Review; этот документ не является отчётом об их выполнении.

## Customer Activity Aggregator — сценарии R0

Тесты `CAA-01…CAA-12` и критерии разграничения данных продавцов, точных границ девятимесячного окна, replay/idempotency, статусов новых и вернувшихся клиентов приведены в [CUSTOMER-ACTIVITY-AGGREGATOR.md](CUSTOMER-ACTIVITY-AGGREGATOR.md). Отдельный S2 Review должен проверить алгоритм уровней и антинакрутку до реализации.

## DEC-27 scenarios

AUTH-01…AUTH-10 and NOT-01…NOT-10 from [AUTH-NOTIFICATIONS.md](AUTH-NOTIFICATIONS.md) supersede obsolete OTP/phone verification tests. Test on real PostgreSQL, ensure no SMS gateways or required FCM presence.

## Единая карта пользовательских сценариев MVP

[USER-JOURNEYS-MVP.md](USER-JOURNEYS-MVP.md) объединяет 16 сквозных UX-сценариев и 20 негативных/конкурентных случаев, диаграммы Web→Flutter→Order, PurchaseLead, торговых переговоров, получения/отзыва, QR Web login, уведомлений и ночных агрегатов. До implementation нужно закрыть явно обозначенные OPEN-01/03/04/11/13/14 и проверить диаграммы независимым S2 Review. Схемы описывают проект, не реализованный Backend.

## Lean MVP consistency review

Сценарии REV-01…REV-13 и canonical flow сведены в [TECHNICAL-REVIEW-MVP.md](TECHNICAL-REVIEW-MVP.md). Для RED обязательны негативные случаи: AGREED не создаёт Order, buyer-only явная конвертация (предложено/S2), продавец не может пометить заказ полученным от имени покупателя, Review отсутствует без подтверждённого receipt, продавец видит только собственную статистику, безошибочная работа без FCM, парольный вход без SMS. SERVICE/DIGITAL запрещено включать без собственных tested fulfillment flows.
