# QRM — план разработки, зависимости и критерии готовности

**Статус:** R0 planning, не execution log. Источники: [REQUIREMENTS](REQUIREMENTS.md), [DOMAIN-CONTRACTS](DOMAIN-CONTRACTS.md), [TEST-PLAN](TEST-PLAN.md).

## 1. Приоритеты

- **P0 / MVP:** идентичность и продавец с логотипом; каталог и типы предложений; объявление с фотографиями/одной локалью; quantity pricing + scheduled discounts; публичная React QR страница; Web callback lead; Flutter offer→AGREED→Order; отзыв/отмена; seller inbox/read/bookmark/keyset pagination; базовое выполнение; rating после получения; минимальная аналитика просмотров; безопасность.
- **P1 / MVP+ / release dependent:** Flutter favorites/price watch + FCM; QR Web login; dashboard QR-attribution, YouTube, расширенное review media. Если QR-login обязательная точка входа для Web Seller Cabinet, его security verification становится P0 для выпуска кабинета.
- **P2:** мультиаккаунты брендов, расширенные валюты, платежи/эквайринг, собственная логистика, полнотекстовый маркетплейс, AI, аналитический warehouse.

Указание P1 не удаляет функциональность из общего ТЗ; оно позволяет ранжировать вертикальные срезы. Приоритеты требуют продуктового sign-off перед scope freeze.

## 2. Независимые work packages

| Package | Scope | Prerequisites | Definition of Done |
|---|---|---|---|
| FND-01 | Java 21/WebFlux/R2DBC spike, Liquibase, CI | ADR-001 review | red-green transaction, rollback, outbox proof |
| ID-01 | Phone OTP, identity, reactive authz | FND-01 | OTP/rate-limit/ownership tests |
| SEL-01 | SellerProfile, logo, seller page | ID-01, media | public+private integration and upload tests |
| CAT-01 | Category, measurement units, products | FND-01 | category permissions, locale contract |
| LST-01 | Listing, translation, publish/unlisted/archive | CAT-01, SEL-01, media | state matrix tests |
| MED-01 | Images 3–5, thumbnail, external video | FND-01 | real S3 adapter, security and rollback |
| PRI-01 | quantity tier and effective price | LST-01 | no overlap, boundary and money tests |
| DSC-01 | timed campaigns and scheduler recovery | PRI-01 | timezone/outage/concurrency tests |
| QR-01 | stable QR product/seller URLs, source tags | LST-01 | QR reprint stability and attribution |
| WEB-01 | public React storefront and callback | QR-01, PRI-01, LEAD-01 | guest E2E no login |
| LEAD-01 | callback Lead, spam, seller handoff | ID-01, LST-01 | no auto Order; PII tests |
| NEG-01 | Flutter offers and 5 counter rounds | PRI-01, ID-01 | atomic agree/race/expiry |
| ORD-01 | order from agreed offer or public price | NEG-01, PRI-01 | exact-once, idempotent price snapshot |
| ORD-02 | buyer cancellation and audit | ORD-01 | conflicting transitions qualify |
| FUL-01 | physical/service/digital type-specific flows | ORD-01 | lifecycle and claim tests |
| INB-01 | seller inbox, bookmark/read/counts/pagination | LEAD-01, NEG-01, ORD-01 | no duplicate, race-safe cross-device state |
| APP-01 | Flutter buyer and seller journeys | ID-01, ORD-01, INB-01 | real device smoke |
| AUTH-QR | QR Web login and sessions | ID-01, APP-01 | challenge browser-binding/replay/revoke |
| FAV-01 | Flutter favorites, quantity watch | APP-01, PRI-01 | unique favorite, watched pricing |
| NOT-01 | push+outbox and scheduled discount alerts | DSC-01, FAV-01 | dedup/retry/consent |
| RTE-01 | reviews, seller/product rating | FUL-01 | purchase eligibility and abuse tests |
| ANA-01 | view/QR event ingestion, daily aggregates | QR-01, WEB-01 | nonblocking, bot/dedup/test |
| OPS-01 | deployment, monitoring, backup/recovery | FND-01 | documented runbooks and restored DB |
| REL-01 | fullstack exact-SHA qualification | all P0, release-scoped P1 | signed release checklist |

## 3. Recommended vertical slices

1. **V0 technical proof:** reactive transaction + outbox + concurrent offer conversion; run on actual PostgreSQL.
2. **V1 QR storefront:** seller/logo→listing/photos/translation→price→QR→React view→seller analytics.
3. **V2 contact:** Web lead→seller inbox→read/bookmark→manual callback→secure withdrawal.
4. **V3 transaction:** Flutter price negotiation→AGREED→Order→buyer cancel/complete→review.
5. **V4 retention:** favorite/quantity watch→scheduled discount→deduplicated push→return journey.
6. **V5 seller desktop:** QR session linking→Web seller cabinet↔Flutter shared state.
7. **V6 release:** multi-type service/digital flows, legal/privacy, recovery, performance, security.

Отдельные slices могут идти параллельно при стабильных API contracts; запрещены merge без test evidence.

## 4. Owners and review

Нужны владельцы доменов и oracle-approvers: product/business, backend/DB, React, Flutter, security/privacy, QA, DevOps. Для каждого PR фиксировать owner, reviewer, business oracle, dependency и exact commit SHA. Не назначать вымышленных исполнителей.

## 5. R0 open decisions (blocking implementation details)

| ID | Unresolved item | Proposed default | Gate |
|---|---|---|---|
| OPEN-01 | Определение одного раунда торга | одно встречное предложение | S2 negotiation |
| OPEN-02 | Длительность QR challenge и Web session | 90 сек, idle 30 мин | security review |
| OPEN-03 | Политика округления KZT, налоги | exact decimal + centrally defined scale | pricing tests |
| OPEN-04 | Услуги и digital: единицы, исполнение, претензии | separate fulfillment strategies | product/legal review |
| OPEN-05 | Юр. правила обработки/хранения контактов в Казахстане | minimize+expire, local legal counsel | production launch |
| OPEN-06 | React Web seller cabinet: какие изменения разрешены | full seller management subject to RBAC | UX/API review |
| OPEN-07 | Обязателен ли OTP для добровольных Web leads | нет, антиспам + callback consent | threat model |
| OPEN-08 | Рассчитывается ли уведомление по per-unit или total watch | effective total for watched quantity | pricing UX review |
| OPEN-09 | Доступ к отмене после исполнения и возвратам | consumer-law preserving workflow | product/legal review |
| OPEN-10 | Сроки хранения detailed analytics/reviews/claims | explicit retention schedule | legal/privacy review |
| OPEN-11 | Доступность авторизации через QR при потере телефона | recovery via verified phone + session reset | identity UX review |
| OPEN-12 | Граница первого релиза между P0/P1 | confirm scope after V1/V2 pilots | product sign-off |

## 6. Нельзя объявлять готовым без доказательств

«Spec written» не равно «implemented». Каждая история получает: scenario IDs → RED evidence → independent S2 review → code changes → Testcontainers/contract/UI E2E → security review → exact SHA → deployment evidence. Draft PR на требованиях не следует автоматически merge в main.

## Customer Activity Aggregator — work package

| Package | Scope | Dependencies | Exit gate |
|---|---|---|---|
| CAA-01 | Outbox consume, daily/monthly stats, 9M rolling score, seller-specific customer relationship, optional monthly partitioning | ORD-01, FUL-01, OPS-01 | CAA-01…12 Testcontainers, privacy isolation, anti-replay, reclassification, S2 review |

Рекомендую разрабатывать после подтверждения Order/Fulfillment, иначе агрегатор не получит достоверные события завершения. [Полная спецификация](CUSTOMER-ACTIVITY-AGGREGATOR.md).
