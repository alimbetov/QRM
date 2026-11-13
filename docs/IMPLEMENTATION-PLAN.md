> **Актуальная последовательность:** [REQUIREMENTS.md — мастер-ТЗ v2.0, раздел 15](REQUIREMENTS.md). Старые R0 slices ниже — архив планирования: при конфликте применять R1 Catalog → R2 Identity/Favorites → R3 Reverse Marketplace → R4 Bargaining/Orders/Reserve → R5 Fulfillment/QR → R6 Deal Finder → R7 Tribuo ML/Push. Каждый slice: S0 → S1 RED → S2 Test Review → S3 GREEN → S4 qualification.

# QRM — план разработки, зависимости и критерии готовности

**Статус:** R0 planning, не execution log. Источники: [REQUIREMENTS](REQUIREMENTS.md), [DOMAIN-CONTRACTS](DOMAIN-CONTRACTS.md), [TEST-PLAN](TEST-PLAN.md).

## 1. Приоритеты

- **P0 / минимальный вертикальный пилот:** парольная identity + seller/logo; один Listing с фотографиями/одной локалью, server-side KZT pricing (tiers); публичная React QR страница; Web callback lead без Order; Flutter buyer negotiation→AGREED→явное создание Order; seller inbox/read/bookmark с cursor; подтверждение продавцом и получение покупателем, отмена, product/seller rating, durable Notification Center, безопасность и базовый просмотр QR. Для включения SERVICE/DIGITAL требуются отдельные минимальные fulfillment acceptance cases.
- **P1 / после вертикального пилота, либо релизно-обязательно:** scheduled discounts, Flutter favorites/price watch, optional FCM, advanced QR-attribution, одна YouTube-ссылка, расширенные review media. **Если выпускается React Seller Cabinet, AUTH-QR и revoke Web sessions — P0 для этого кабинета**, но не для публичной React QR страницы. Ночные buyer/seller/seller-buyer профили включаются после квалификации Order/Fulfillment/Review; допускается более поздний публичный запуск уровней.
- **P2:** мультиаккаунты брендов, расширенные валюты, платежи/эквайринг, собственная логистика, полнотекстовый маркетплейс, AI, аналитический warehouse.

Указание P1 не удаляет функциональность из общего ТЗ; оно позволяет ранжировать вертикальные срезы. Приоритеты требуют продуктового sign-off перед scope freeze.

## 2. Независимые work packages

| Package | Scope | Prerequisites | Definition of Done |
|---|---|---|---|
| FND-01 | Java 21/Spring MVC/JPA foundation, PostgreSQL, dev-only Hibernate DDL, CI | ADR-001 review | red-green transaction, rollback, outbox proof |
| ID-01 | Phone-as-login + password, refresh sessions, reactive authz; NO SMS/OTP | FND-01 | AUTH-01…10, recovery and ownership tests |
| SEL-01 | SellerProfile, logo, seller page | ID-01, media | public+private integration and upload tests |
| CAT-01 | Category, measurement units, products | FND-01 | category permissions, locale contract |
| LST-01 | Listing, translation, publish/unlisted/archive | CAT-01, SEL-01, media | state matrix tests |
| MED-01 | Images 3–5, thumbnail; optional YouTube URL later | FND-01 | real S3 adapter, security and rollback |
| PRI-01 | quantity tier and effective price | LST-01 | no overlap, boundary and money tests |
| DSC-01 | timed campaigns and scheduler recovery | PRI-01 | timezone/outage/concurrency tests |
| QR-01 | stable QR product/seller URLs, source tags | LST-01 | QR reprint stability and attribution |
| WEB-01 | public React storefront and callback | QR-01, PRI-01, LEAD-01 | guest E2E no login; seller cabinet separate release gate |
| LEAD-01 | callback Lead, spam, seller handoff | ID-01, LST-01 | no auto Order; PII tests |
| NEG-01 | Flutter offers and 5 counter rounds | PRI-01, ID-01 | atomic agree/race/expiry |
| ORD-01 | order from agreed offer or public price | NEG-01, PRI-01 | exact-once, idempotent price snapshot |
| ORD-02 | buyer cancellation and audit | ORD-01 | conflicting transitions qualify |
| FUL-01 | physical fulfillment first; service/digital typed workflows required before enabling these listing types | ORD-01 | receipt evidence and type-specific lifecycle and claim tests |
| INB-01 | seller inbox, bookmark/read/counts/pagination | LEAD-01, NEG-01, ORD-01 | no duplicate, race-safe cross-device state |
| APP-01 | Flutter buyer and seller journeys | ID-01, ORD-01, INB-01 | real device smoke |
| AUTH-QR | Web Seller Cabinet QR login and sessions **conditional P0 when Web cabinet released** | ID-01, APP-01 | challenge browser-binding/replay/revoke |
| FAV-01 | Flutter favorites, quantity watch | APP-01, PRI-01 | unique favorite, watched pricing |
| NOT-01 | durable Notification Center + Outbox + optional Push | FND-01, ID-01 | NOT-01…10, FCM outage resilience; price-watch events later after DSC-01/FAV-01 |
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
| OPEN-07 | Web Leads без SMS/OTP | согласовано; антиспам + callback consent, secure per-lead withdrawal | threat model |
| OPEN-08 | Рассчитывается ли уведомление по per-unit или total watch | effective total for watched quantity | pricing UX review |
| OPEN-09 | Доступ к отмене после исполнения и возвратам | consumer-law preserving workflow | product/legal review |
| OPEN-10 | Сроки хранения detailed analytics/reviews/claims | explicit retention schedule | legal/privacy review |
| OPEN-11 | Восстановление при потере телефона без SMS | только verified email или ещё действующая доверенная сессия; иначе ручная процедура TBD | identity UX review |
| OPEN-12 | Граница первого релиза между P0/P1 | confirm scope after V1/V2 pilots | product sign-off |

## 6. Нельзя объявлять готовым без доказательств

«Spec written» не равно «implemented». Каждая история получает: scenario IDs → RED evidence → independent S2 review → code changes → Testcontainers/contract/UI E2E → security review → exact SHA → deployment evidence. Draft PR на требованиях не следует автоматически merge в main.

## Customer Activity Aggregator — work package

| Package | Scope | Dependencies | Exit gate |
|---|---|---|---|
| CAA-01 | Audited qualifying-order projection and 9M buyer/seller relationship rule calculation; rollups/partitions after load evidence | ORD-01, FUL-01, OPS-01 | CAA-01…12 Testcontainers, privacy isolation, anti-replay, reclassification, S2 review |

Рекомендую разрабатывать после подтверждения Order/Fulfillment, иначе агрегатор не получит достоверные события завершения. [Полная спецификация](CUSTOMER-ACTIVITY-AGGREGATOR.md).

## NPA-01 — Nightly Profile Aggregation

| Package | Scope | Dependencies | Gate |
|---|---|---|---|
| NPA-01 | Daily 02:30 Asia/Almaty snapshot buyer/seller/seller-buyer 9M, profile publication, rerun/recovery | CAA-01, ORD-01, FUL-01, RTE-01 | NPA-01…NPA-20 RED/S2/PG integration and failure recovery |

Единый ночной worker проектируется без Kafka/отдельного микросервиса. Реализацию начинать после квалификации реальных событий выполнения заказов и рейтингов, иначе будет рейтинг по неподтверждённым действиям. [Полное ТЗ](NIGHTLY-PROFILE-AGGREGATION.md).

## DEC-27 work package

Identity must not include SMS/OTP adapter, and notification first must persist internally, without required FCM. Build password credentials, rotating device sessions, verified-email recovery, and notification outbox projection in RED→S2→GREEN sequence. [Full design](AUTH-NOTIFICATIONS.md).

## R0 / User journey qualification

В [USER-JOURNEYS-MVP.md](USER-JOURNEYS-MVP.md) зафиксированы Mermaid flowchart/state/sequence диаграммы и матрица UX-01…16/EDGE-01…20. Перед R1: проверить OPEN-13 (неподтверждённое получение и накрутка рейтинга) и OPEN-14 (сегментация seller-buyer + доступ staff), уточнить OPEN-01/03/04/11/12. Затем переводить сценарии в тесты RED с независимым S2 Review. Реализация и CI пока отсутствуют.

## Technical review baseline (2026-11-12)

[TECHNICAL-REVIEW-MVP.md](TECHNICAL-REVIEW-MVP.md) обосновывает lean delivery. В MVP нельзя считать покупателя «оплатившим» по статусу платформы; Seller completed означает READY_FOR_RECEIPT, buyer confirmed receipt открывает рейтинг. Не создавать сразу daily/monthly profile tables, Kafka и data warehouse. Для общего full release Web seller cabinet требуется AUTH-QR, для public QR только guest Web. Решения OPEN-01/03/04/11/13/14 остаются review gates, а не произвольными implicit defaults.

## Second-pass review gate

[TECHNICAL-REVIEW-R2.md](TECHNICAL-REVIEW-R2.md) выявляет R2-01…20. Важные новые блокеры: **OPEN-15** (оспаривание неподтверждённого phone-login), **OPEN-16** (PostgreSQL source snapshot / job fencing), **OPEN-17** (consent/retention для Казахстана). Это должны быть точные S0 бизнес-оракулы и RED-тесты, не повод добавлять микросервисы. Предпочтительно один backend deployment + PostgreSQL + S3 + React + Flutter; очередь Outbox и ночной worker внутри монолита.

## R0.3 post-review decision gates

[Decision pack](MVP-DECISIONS-R03.md) records user direction: no SMS, verified-email recovery, buyer-confirmed receipt using short-lived QR/code, nightly profile job after local midnight (00:30 proposed), data residency in Kazakhstan, proposed buyer/seller tier thresholds, KZT legal cash-rounding separation. **OPEN-15** phone-as-unverified-login remains deferred and **must not** be silently declared safe for broad public signup. R03-01…18 → RED/PostgreSQL integration → S2 review → implementation. No extra infrastructure for QR receipt or nightly job.

## DEC-34 optional recovery provider

Do **not** build SMS delivery in ID-01 registration or normal auth. Keep `PhoneVerificationProvider` as optional recovery-bound port only; implementations may be deferred. A valid SMS code for an unverified phone **must not** reset password. Recovery by previously verified email is first option. Implement testable no-SMS-by-default flow and keep OPEN-15 as public-signup blocker. [Auth design](AUTH-NOTIFICATIONS.md).

## ADR-004 — roadmap гибридных движков

Целевой портфель из десяти продуктовых функций утверждён в [ADR-004-HYBRID-INTELLIGENCE-ENGINES.md](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md). Порядок срезов: P0-F1 HCC/атрибуты/проверка совместимости и FeatureRegistry → P0-F2 реальный каталог/запрос/однопродавцовый заказ → P1-F3 языковой поиск и аналоги → P1-F4 Demand Radar и Bargaining Advisor → P1-F5 размеченные события/Tribuo baseline → P2-F6 симуляция мультипродавцового набора и комплекты (Timefold при оправданной сложности) → P2-F7 AI Shopping Assistant / сегментация → P3-F8 GIS Delivery Optimizer. Физическое включение библиотек только после RED → S2 Review, тестов совместимости Java 21, лицензий и оценок производительности.

## DEC-INTELLIGENCE-002 — Weekly Aggregation / Inventory smoke

Созданы `WeeklyIntelligenceAggregator` (полные ISO-недели, ежедневные наблюдения и 1/4/12-недельные rolling windows), `WeeklyFeatureProjector` (freeze `inventory_advisor/v1`, девять слотов) и синтетические проверки `WeeklyIntelligenceSmokeTest` на 12 неделях/3 SKU. Следующий этап после успешного CI: независимый S2 Review схемы PostgreSQL, идемпотентного source watermark, коррекции прошлых недель, реальной истории stock exposure и сопоставления BuyerDemand с каноническими товарами. Реальные закупочные рекомендации блокируются до получения lead time, себестоимости, возвратов, минимальных партий и точных остатков. [Спецификация](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md).

## ML-RISK-001 — от smoke к модели

S1: пять временных fold, sufficiency, fallback выбора алгоритма, детерминированные тесты. S2: независимый review качества labels, Stockout censoring, event as-of, версионированных окон и schema manifest. S3: реальные датасеты, group/time holdout, baseline benchmark, распределения ошибок и bootstrap интервалы, дрейф и модель Tribuo. S4: shadow inference, canary, monitoring + rollback, без автоматической закупки. [Техническое решение](DEC-ML-RISK-001-RELIABLE-LEARNING.md).

## ADR-005 — staged nightly intelligence batching

Новые S1/S2 задачи: deterministic bucket assignment и 12 NB тестов → source watermark/period revision → PostgreSQL monthly RANGE events/daily (weekly partitions only after measured size), control run/bucket task schema и worker claim с `FOR UPDATE SKIP LOCKED` → bounded chunks/checkpoints/lease expiry → closed-week revision + 1/4/12/52 feature invalidation → staged whole-run publication / rollback → conditional Tribuo retraining и benchmark против online p95/p99. Нагрузка сначала 16 виртуальных бакетов, 1–2 реальных worker-потока; числа являются гипотезой для k6/pg benchmark. Начать без Kafka/нового микросервиса. [ADR-005](ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md).

## ML-RISK-002 — self-tuning gates + bounded multithreaded buckets

S1: безопасная калибровка порогов на исторических независимых cohorts, floor/ceiling и отказ при слабых доказательствах; детерминированные SHA-256 buckets и bounded platform worker pool, тесты на провал бакета. S2: запрет tuning/evaluation leakage, независимый holdout, clustered intervals, проверка worst segments, версии threshold policy; реальный adaptive worker admission по p95/CPU/DB pool, ограниченная очередь, PostgreSQL lease/checkpoint и atomic publication. S3: Testcontainers/k6 стресс с 5K пользователей и nightly 1→4 workers. [DEC-ML-RISK-002](DEC-ML-RISK-002-ADAPTIVE-GATES-AND-BOUNDED-WORKERS.md).

## R1 Catalog & Classification Foundation — executed slice A

R1A implemented in branch: minimal category/HCC, typed attribute definition + applicability, canonical product separate from seller listing and SKU, smoke tests, draft SQL DDL. R1B next: PostgreSQL Testcontainers **against real migration**, required attribute/enum/units rules, category parent depth/cycle, authenticated seller ownership, server-side SKU matching facts. RED test review before implementation. [Implementation note](R1-CATALOG-FOUNDATION-IMPLEMENTATION.md).

## R1B — executed catalog qualification slice

RED tests for HCC positional validation, required SKU facts, seller publication ownership; GREEN: `HccPath`, `CatalogPublicationPolicy`, `CatalogPublishingService`, `CatalogSkuFactsResolver`, Testcontainers PostgreSQL manual DDL validation. [R1B evidence](R1B-CATALOG-QUALIFICATION.md). Before R2, independent S2 review must validate seller principal integration, enum/unit policy, catalog mutation guards and schema migration path.

## R1B.2 — seller draft protection and PostgreSQL integrity

RED → GREEN: тесты подтверждают отклонение чужого sellerId, изменение только DRAFT, idempotent attribute upsert, одобренный enum lookup, отказ от неизвестных значений в catalog facts; PostgreSQL manual V002 enum check и V003 trigger замораживают опубликованные SKU при прямом SQL. Требуются S2 review, реальные auth principal / membership, schema migration history для всех доменов и versioned categorical facts перед ML. [Отчёт R1B.2](R1B2-CATALOG-SECURITY-AND-INTEGRITY.md).

## Seller JWT + Flyway → S2 → R2

RED test commits for ACL/migration → GREEN: JWT issuer+scope, issuer+sub membership DB authorization, HTTP seller publication endpoint; Flyway complete V1–V4 fresh PostgreSQL schema and JPA validation, existing schema fail-closed; RSA-signed JWT integration test. Independent reviewer: issue [#3](https://github.com/alimbetov/QRM/issues/3); cannot be replaced by developer self-audit. R2 scenario design and 20 acceptance oracles: issue [#4](https://github.com/alimbetov/QRM/issues/4). Before R2 RED implementation: approve migration of any existing database and issuer/JWKS + membership provisioning integration. [S2 package](S2-REVIEW-SELLER-AUTH-FLYWAY-R2-GATE.md), [R2 oracles](R2-BUYER-DEMAND-BID-ORDER-RESERVATION-SCENARIOS.md).

## R2.0A — BuyerDemand / SellerBid persisted snapshots

RED contract tests → JPA snapshot entities/repositories, internal `DealPersistenceService`, Flyway V5 historical basket and immutable bid revisions, Flyway V6 PostgreSQL append-only triggers; fast H2 + real PostgreSQL qualification tests. Next S2 gate: verified units, immutable quote (price+shipping), buyer identity and bid acceptance/idempotency; then CustomerOrder(PLACED) → seller confirmation → atomic reservation/outbox. Do not expose public endpoints without real auth / stock authority. [Evidence/remaining scope](R2-0A-DEMAND-BID-SNAPSHOT-IMPLEMENTATION.md).

## R2.0B → R2.1 — executed vertical transaction slices

RED → GREEN R2.0B: canonical sales unit per SKU/demand, immutable seller quote including delivery, server-verified buyer account link, idempotent bid acceptance, one CustomerOrder(PLACED) per demand, Flyway V7–V9. RED → GREEN R2.1: InventoryBalance / InventoryReservation, SELLER_ADMIN verified confirmation, ordered PostgreSQL row locking and atomic status/outbox, V10, V11 SQL economic immutability. PostgreSQL IT includes last-unit concurrent confirmation and multiline full rollback. Further P0: independent S2, real identity/onboarding, seller stock movements, cancellation/release, outbox delivery/reconciliation, load test 5000 sessions. [R2.0B](R2-0B-BID-ACCEPTANCE-IMPLEMENTATION.md) · [R2.1](R2-1-INVENTORY-RESERVATION-OUTBOX-IMPLEMENTATION.md).

## Единая рабочая ветка QRM (2026-11-13)

**Единственная активная рабочая ветка:** `docs/adr-002-jpa-architecture-review`; `main` — базовая ветка и не изменяется до review. [Smart Deal R2](SMART-DEAL-R2.md) и [его технический аудит](SMART-DEAL-R2-AUDIT.md) перенесены сюда как функциональная спецификация и аудит, **не как повторный JPA Backend**. [План консолидации](SMART-DEAL-JPA-CONSOLIDATION.md) требует delta-review относительно реализованных Flyway V1–V11 и R2.0A/0B/1, а затем только недостающие MockMvc/JPA/PostgreSQL Testcontainers RED-тесты и S2 review. Отдельный `smartdeal-contract-tests` из `docs/smart-deal-r2` не переносить из-за дублирования persistence. Документы в старой ветке считать историческими; статус удаления ссылки проверять отдельно.
