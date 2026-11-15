# ZAKAZ-ML-001 — Техническое задание: интеграция ML Engine с реальным маркетплейсом

**Версия:** 1.0 — DRAFT / S0 specification; не утверждённый release gate  
**Дата:** 2026-11-14  
**Репозиторий:** [alimbetov/QRM](https://github.com/alimbetov/QRM)  
**Целевая ветка:** docs/adr-002-jpa-architecture-review; не создавать новую ветку  
**Baseline аудита:** e2e460fb34f5de841c570d34072a0e7de0fb2b0a (перед исполнением обязательно повторно проверить HEAD)  
**Владелец:** ZAKAZ product / architecture; независимое S2-утверждение ещё не получено  
**Характер документа:** требования к будущим изменениям, а не утверждение о реализованном ML

## 0. Нормативный статус и источники

Этот документ конкретизирует, но не заменяет:
- [REQUIREMENTS.md](REQUIREMENTS.md) — мастер-ТЗ v2.0 и продуктовые решения;
- [ADR-002-MVC-JPA.md](ADR-002-MVC-JPA.md) — Java 21, Spring MVC, JPA, PostgreSQL, Flyway;
- [ADR-003-JAVA-ML-RECOMMENDATIONS.md](ADR-003-JAVA-ML-RECOMMENDATIONS.md) — Java/Tribuo и ограничения персонализации;
- [ADR-004-HYBRID-INTELLIGENCE-ENGINES.md](ADR-004-HYBRID-INTELLIGENCE-ENGINES.md) — deterministic eligibility до ML;
- [ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md](ADR-005-NIGHTLY-BUCKETED-INTELLIGENCE.md) — offline-процесс;
- [DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md), [DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md](DEC-INTELLIGENCE-002-WEEKLY-AGGREGATES.md);
- [DEC-ML-RISK-001-RELIABLE-LEARNING.md](DEC-ML-RISK-001-RELIABLE-LEARNING.md), [DEC-ML-RISK-002-ADAPTIVE-GATES-AND-BOUNDED-WORKERS.md](DEC-ML-RISK-002-ADAPTIVE-GATES-AND-BOUNDED-WORKERS.md);
- [REVIEW-TRIBUO-HCC-001.md](REVIEW-TRIBUO-HCC-001.md), [SMART-DEAL-R2.md](SMART-DEAL-R2.md).

При противоречии исторической спецификации с актуальными JPA/Flyway контрактами — не переписывать исходную реализацию, а оформить delta-review, RED-тест и отдельное решение. Указанные номера новых Flyway миграций — предварительные: текущий baseline содержит V1–V22, назначать номера на фактическом HEAD перед каждым GREEN.

## 1. Контекст, факты и цель

### 1.1. Проверенная существующая база (код, не production readiness)

В ветке имеются Catalog/HccPath/AttributeDefinition/CategoryAttributeRule/SkuAttributeValue/CatalogSkuFactsResolver; BuyerDemand и SellerBid с immutable snapshots; CustomerOrder, складские резервы и order-only Outbox. В intelligence существуют:
- FeatureRegistry, FeatureVectorEncoder, IntelligenceDictionarySeed;
- ClassifiedOfferMatcher, DealFeatureProjector, WeeklyFeatureProjector;
- WeeklyIntelligenceAggregator, DataSufficiencyEvaluator, TemporalBacktester;
- AdaptiveSufficiencyCalibrator, PredictionPolicyEngine, BoundedBucketExecutor;
- отдельный deterministic SmartDealFinder.

Это преимущественно pure-Java алгоритмы и unit/smoke тесты. В backend/pom.xml нет Tribuo, нет обученного артефакта, обучающего runtime, model registry, production feature pipeline и полноценного API рекомендаций. Сам код не доказывает наличие реальных historical events и успешной внешней ML-квалификации.

**Обязательное исправление несовместимости:** реальный Catalog использует 20-значный HCC и канонические коды атрибутов вида BRAND/STORAGE_GB; тестовый matcher оперирует демонстрационными кодами класса и ключами brand/storage_gb. Тестовые данные не являются production dictionary.

### 1.2. Цель

Реализовать вертикальный путь:

**Real Catalog + verified seller/demand facts → deterministic eligibility → versioned as-of features → rule-based baseline → (только после квалификации) Tribuo inference → объяснимый и безопасный рейтинг продавцов.**

Параллельный offline-путь:

**Committed domain events → deduplicated fact log → point-in-time dataset → chronological evaluation → training → verified model artifact → approved publication → auditable rollback.**

**Первая supervised задача:** seller_response_24h — оценка отклика продавца в течение 24 часов после доказуемого появления доступного ему приглашения к спросу. Это вероятность ответа при известных условиях, а **не** вероятность продажи, выполнения заказа или качества продавца.

### 1.3. Бизнес-результат и метрики

Основные метрики пилота: доля запросов с квалифицированным ответом, время до первого ответа, доля переходов в согласованный SellerBid/CustomerOrder; дополнительно — нагрузка на продавца, доля неуместных приглашений и отказов/жалоб. Offline ML-метрика сама по себе не считается коммерческим успехом.

### 1.4. Не входит в первый ML release

Обученные модели buyer choice/segmentation, закупочный forecast, AI assistant, image embeddings, push ranking, auto-bargaining, отдельный Python-сервис, GPU, Kafka, Elasticsearch, graph DB. Их планирование не означает поставку. Не вводить новые runtime services без измеренной необходимости.

## 2. Нерушимые бизнес- и security-инварианты

| ID | Инвариант |
|---|---|
| INV-01 | ML не создаёт, не принимает и не отменяет CustomerOrder, SellerBid или резерв и не меняет согласованные цены. |
| INV-02 | Один CustomerOrder по-прежнему принадлежит одному продавцу; все экономические snapshots остаются immutable. |
| INV-03 | Детерминированная eligibility предшествует ранжированию: проверка seller ownership/membership, PUBLIC listing, product/SKU/unit/quantity, hard attributes, территории и истечения срока. Отсутствие достоверного факта не трактовать как true. |
| INV-04 | BuyerId и sellerId нельзя подтверждать по клиентскому параметру: идентификация — серверная, через verified JWT, active buyer link и seller membership. |
| INV-05 | Никаких приватных цен, телефона, email, адресов, персональной истории покупателя или скрытых SKU в features, выдаче и логах без отдельного purpose/consent/privacy review. |
| INV-06 | Необученная, невалидированная, несовместимая или недоступная модель не может выдавать «AI вероятность»: возвращать deterministic baseline и NULL probability. |
| INV-07 | Модель и offline batch не находятся в критическом пути подтверждения заказа/резерва, не держат его транзакции и не блокируют checkout. |
| INV-08 | Существующий order-only Outbox с FK к CustomerOrder не использовать как универсальный источник catalogue/ML notifications. Новый event-канал проектировать независимо. |
| INV-09 | Рекомендация не является разрешением маркетингового push; рассылки — отдельная явно разрешённая функция со своими квотами и unsubscribe. |
| INV-10 | Все новые HTTP-маршруты deny-by-default и выключены по умолчанию; S2 approval и E2E обязательны до внешнего rollout. |

## 3. Конкретные зависимости до обучения SellerResponse

**BLK-01 — Нет подтверждённого приглашения продавцу.** Нынешние BuyerDemand/SellerBid и seller lead inbox не доказывают, что продавец видел или мог получить конкретное приглашение. Guest PurchaseLead — другой бизнес-сценарий и не источник ML-метки отклика на BuyerDemand.

В отдельном минимальном Smart Demand slice требуется авторитетная запись SellerDemandInvitation (либо эквивалентное именованное отношение), привязанная к одному demand, seller и версии eligibility, с механизмом server-side создания и явной доставкой в защищённый seller inbox. В первой версии достаточно доказанной доступности в authenticated inbox; внешняя отправка email/push не требуется. Нельзя заявлять DELIVERY, если создана только запись в БД.

**BLK-02 — Нет полноценной seller-side UX квалификации.** До появления клиентского кабинета допускается тестируемый authenticated seller API; модель нельзя обучать по синтетически созданным «ответам» или ссылке на callback phone lead.

**BLK-03 — Нет независимых outcome windows.** Для каждой метки фиксировать business event-time, first eligible invitation availability, censoring, seller/demand scope и expiry; отсутствие ответа не является negative до закрытия полного окна.

**BLK-04 — Нет утверждённого purpose/retention.** Собирать лишь разрешённые минимальные события; до privacy/S2 gate использовать синтетические наборы и не включать персонализацию реальных покупателей.

Эти блокеры — зависимости этапов, не основание создавать вторую систему заказов или новую ветку.

## 4. ML-R1 — Canonical feature / classification adapter (P0)

### Функциональные требования

**FR-R1-01.** Catalog — единственный authoritative source для 20-значного HCC, Category ancestry, AttributeDefinition (ENUM/DECIMAL/BOOLEAN/TEXT), канонических единиц, applicability rules и publish state. IntelligenceDictionarySeed пометить fixture/smoke, не выполнять по нему production decisions.

**FR-R1-02.** Новый CanonicalFeatureAdapter получает server-resolved CatalogSkuFactsResolver.Facts (sellerId, skuId, productId, hccCode, canonical attributes), BuyerDemand-derived требования и timestamp; не принимает клиентские «verified» флаги как доказательство.

**FR-R1-03.** Нормализовать ключи атрибутов к реальным кодам (например, BRAND/STORAGE_GB), единицы — по существующему MeasurementUnit и typed attribute canonicalUnit. Сравнение размеров/единиц без явно определённой конверсии запрещено.

**FR-R1-04.** Различать HARD = обязательное условие и SOFT = пожелание пользователя. Missing HARD — REJECTED/UNKNOWN с reason code; missing SOFT не превращать в 100% совпадение. Объяснение: exact/compatible/missing/rejected только при наличии нормативного compatibility contract.

**FR-R1-05.** Feature schema для каждой задачи фиксирует taskCode, version, ordered feature names, name/type/unit/normalization, missing policy, source, valid-as-of policy и dictionary revision/hash. Хранить строковые category codes как one-hot/иерархические признаки, а не как числовой скаляр.

**FR-R1-06.** Повторная проекция одних и тех же approved facts при одинаковом schemaVersion даёт один результат; unknown features либо явно отклоняются, либо переводятся в предусмотренную категорию UNKNOWN, без бесконтрольного расширения вектора.

**FR-R1-07.** Current seller membership, availability, actual quote/delivery и территории — отдельные авторитетные security/domain gates. Feature projection не предоставляет доступ к товару и не подменяет эти проверки.

**Рекомендуемый интерфейс (контракт, не готовый код):** CanonicalFeatureAdapter.project(VerifiedCandidateFacts, FeatureSchemaVersion, Instant decisionTime) → FeatureProjection { eligibilityEvidenceId, schemaVersion, namedFeatures, missingFlags, reasons }.

### Проверки RED

| Test ID | RED-оракул |
|---|---|
| R1-01 | PostgreSQL Catalog → HCC 20 symbols → canonical feature names; никакого demo 0102003 / lowercase несоответствия |
| R1-02 | HARD: SKU 128GB против 256GB отвергнут до ML |
| R1-03 | Неизвестный атрибут и чужой SKU не входят в feature projection |
| R1-04 | Неактивная категория/не-PUBLIC listing не проходят |
| R1-05 | Версии словаря/схемы не смешиваются; одинаковый input даёт byte-stable named feature set |
| R1-06 | Отсутствующий показатель != достоверный ноль, включая response-rate |
| R1-07 | Нет новых побочных эффектов в SellerBid/Order/Inventory |

**DoD R1:** настоящие JPA + PostgreSQL Testcontainers tests проходят; adapter используется хотя бы в одном server-side matching flow под feature flag; synthetic-only tests не считаются интеграцией.

## 5. ML-R2 — Seller invitation & audited event capture (P0)

### Минимальный contract SellerDemandInvitation

Предусмотреть уникальную связь (demand_id, seller_id, invitation_revision/round), server-owned creation, eligibility revision, created_at, available_at, terminal status/expiry и контроль owner ACL. Правила повторного приглашения, отмены и доступности — отдельный S0 contract, до SQL RED. Не создавать invited merchant из неавторизованного клиентского sellerId. Предложение продавца обязано ссылаться на разрешённый спрос и identity; старый SellerBid flow не ломать.

**FR-R2-01.** Факты из committed business transactions пишутся атомарно с соответствующим source action или через отдельно рассмотренный надёжный transactional outbox. Не хранить непроверенные client counters.

**FR-R2-02.** Базовый vocabulary:
- DEMAND_CREATED — запрос создан;
- ELIGIBLE_CANDIDATE_EVALUATED — только расчётный eligibility, не факт показа;
- INVITATION_AVAILABLE — верифицированная доступность конкретному продавцу;
- INVITATION_DELIVERED — только при доказуемом подтверждении доставки внешним каналом, при отсутствии подтверждения не испускать;
- SELLER_RESPONDED / BID_SUBMITTED — фактически принятый сервером ответ;
- BID_ACCEPTED, ORDER_CONFIRMED, ORDER_CANCELLED — исходы, отдельно от target SellerResponse.

**FR-R2-03.** Дедупликация на авторитетных identity source event (source_type, source_id, source_version, event_type), event_id UUID и UNIQUE/UPSERT. Настоящий source timestamp обязателен; ingestion timestamp не подменяет occurred_at.

**FR-R2-04.** append-only, запрещено бесследное переписывание event-time. Исправления вносить ревизионным event/correction с provenance. Данные с дубликатами, неизвестным source и PII исключаются до dataset.

**FR-R2-05.** No marketing push and no automatic invitation without defined authorized policy. Событие RECOMMENDATION_SHOWN и фактическое взаимодействие не эквивалентны eligibility.

**FR-R2-06.** Отрицательная метка seller_response_24h возникает лишь при: (a) INVITATION_AVAILABLE, (b) полных 24 часах наблюдения после available_at, (c) известном terminal/censor state и отсутствии разрешённого ответа, (d) полном source watermark. Случаи приглашения, истёкшего до 24 часов, недоступного продавцу, ошибочной доставки, приостановки аккаунта или незавершённого окна — censored/excluded, а не negative.

**Рекомендуемая схема V23+ (пример колонок; финальный DDL после DB review):**
- seller_demand_invitation: id, demand_id FK, seller_id, eligibility_revision, state, created_at, available_at, expires_at, version, уникальные business keys.
- intelligence_event_fact: event_id PK, source_type, source_id, source_version, event_type, occurred_at, recorded_at, seller_id nullable, demand_id nullable, sku_id nullable, schema_version, payload jsonb allowlisted, correction_of nullable; unique idempotency key. FK/partial indexes и partitioning только после реальной квалификации нагрузки.

**Тесты:** R2-01 rollback без event; R2-02 double delivery/replay => один факт; R2-03 owner/scope; R2-04 24h negative gate; R2-05 cancellation/censor; R2-06 late event correction и audit; R2-07 parallel invitation creation; R2-08 guest PurchaseLead не классифицируется как SellerDemandInvitation.

**DoD R2:** воспроизводимый PostgreSQL путь demand → eligible seller → authenticated invitation available → bid response с однозначной source attribution и безопасными негативными сценариями.

## 6. ML-R3 — Point-in-time dataset & derived aggregates (P0)

**FR-R3-01.** TrainingDatasetBuilder читает только авторитетные committed события/факты из PostgreSQL, фиксирует consistent as-of cutoff, source watermark, featureSchemaVersion, eventSchemaVersion, inclusion/exclusion reason, datasetRevision и datasetChecksum.

**FR-R3-02.** Только признаки, существовавшие до prediction_time (= invitation.available_at); response/offer/order outcome за окно являются label, не feature. Проверять future leakage в JOIN, aggregations, latest-record и временных зонах.

**FR-R3-03.** Data windows: для seller_response_24h период определяется временем приглашений и завершением outcome window; для будущего inventory_advisor — closed ISO Monday–Monday, Asia/Almaty, source instants UTC. Не применять требование «26 недель» как универсальное правило ко всем задачам.

**FR-R3-04.** Метки 1/0/EXCLUDED с reason. Unknown delivery, incomplete window, duplicate identity, expired seller eligibility или late repair блокируют negative label до разрешения.

**FR-R3-05.** Учитывать exposure/selection bias: модель обучается на фактически доступных продавцу приглашениях, а не на всех sellers без ответа; фиксировать правило первоначального отбора и не принимать невиденные офферы за negatives.

**FR-R3-06.** Сценарии daily/weekly metrics строить отдельными read models только при достоверных источниках stock exposure, fulfillment и единиц. Нельзя использовать confirmed order как доказанное получение или фактическую продажу, пока нет receipt evidence.

**FR-R3-07.** Дубликаты daily seller/SKU/territory/day, изменение статуса заказа, late events и source revisions должны давать воспроизводимый результат, не суммировать повторно перекрывающиеся rolling windows.

**Минимальные объекты по потребности:** ml_dataset_build (manifest metadata), intelligence_daily_metric, intelligence_weekly_metric; сохраняемые normalized records только если доказана потребность в повторном воспроизведении. Не заводить warehouse/партиции заранее.

**Тесты:** R3-01 chronological exclusion; R3-02 repeat run same checksum; R3-03 late correction revision; R3-04 never observed invited seller is not negative; R3-05 24h boundary and cancellation; R3-06 join fanout does not inflate labels; R3-07 cross-buyer isolation; R3-08 UTC/Asia-Almaty closed week edge; R3-09 zero != unknown stock.

**DoD R3:** реальный PostgreSQL fixture создаёт point-in-time dataset с утверждёнными метками, cutoff и reason counts; повторная сборка с тем же snapshot воспроизводима.

## 7. ML-R4 — Tribuo training & independent evaluation (P1, gated)

**FR-R4-01.** Только после R3 GREEN подключить минимально необходимые released Tribuo modules с фиксированной версией и проверенными transitive dependencies. Trainer вне HTTP path; в рамках JVM отдельный ограниченный worker.

**FR-R4-02.** Первая supervised модель — binary seller_response_24h. Начальный кандидат: интерпретируемая линейная/логистическая классификация; deterministic baseline — правила + наблюдаемая история откликов с корректными missing flags. Нет модели => baseline без выдуманной probability.

**FR-R4-03.** Training rows содержат point-in-time features и outcome после закрытия наблюдения. Фиксировать random seed, split policy, versions, label horizon, class balance, training data checksum и excluded/censored counts.

**FR-R4-04.** Разбиение строго time-based, с отдельным untouched final holdout и при необходимости gap/embargo для перекрывающихся 24h окон. Учитывать зависимость по seller/demand, анализировать результаты по независимым городам/HCC groups/merchant cohorts. Synthetic 5-fold smoke не является evidence реальной полезности.

**FR-R4-05.** Метрики binary task: PR-AUC (primary for imbalance), ROC-AUC secondary, Precision@K/Recall@K по реально приглашённым candidates, Brier/ECE calibration, subgroup coverage, confidence intervals и baseline comparison. MAE и moving-average4 — только для отдельной forecast/regression task; не использовать как единственный binary model gate.

**FR-R4-06.** Никаких жёстких «доказанных» объёмов/порогов заранее: сначала определить minimum effective independent sample, positive events, cohort/period stability и power. Слабая модель остаётся CANDIDATE; фиксировать отрицательный результат исследования.

**FR-R4-07.** Артефакт с pinned feature/schema/dictionary revision, serialized protobuf/проверенным форматом, SHA-256, metrics, timestamp, training code commit, dataset hash и provenance. Не выполнять непроверенную Java deserialization.

**DoD R4:** воспроизводимый deterministic synthetic training test + успешное обучение на разрешённой исторической выборке (при её наличии), artifact/metrics; отсутствие реального dataset — явный BLOCKED FOR PROMOTION, а не подмена «обучения» фиктивной точностью.

## 8. ML-R5 — Model registry, qualification, inference, rollback (P1)

### Состояния и публикация

ModelVersion: DRAFT → TRAINED → VALIDATED → APPROVED → ACTIVE → RETIRED, либо REJECTED. Только APPROVED может стать ACTIVE; один ACTIVE pointer на (task, compatible feature schema, release channel). Смену делать транзакционно через публикационный указатель, с audit actor/at/reason и возможностью rollback на ранее APPROVED.

**FR-R5-01.** Хранить ml_training_run, ml_model_version и ml_model_publication. Для MVP допустим защищённый PostgreSQL BYTEA artifact ограниченного размера либо проверенное приватное S3-compatible object storage; выбрать вариант на S0 DB/operations review. Не публиковать artifact в публичный API.

**FR-R5-02.** При загрузке контролировать checksum, model format, schema hash, dictionary revision, feature overlap/coverage и label semantics; mismatch → baseline. В модели не должно быть произвольных secrets и PII.

**FR-R5-03.** Inference принимает только server-side verified eligible candidates. Отдельный untrusted request не может указать buyerId, seller membership или verified stock как входные доказательства.

**FR-R5-04.** Обязательный ответ сервиса: task, candidateId, eligible, rankingSource {RULES, ML}, modelVersion nullable, probability nullable, reasonCodes, asOf, featureSchemaVersion, reason of fallback. Probability — численная только для квалифицированного, откалиброванного по этому label scoring engine; никогда не именовать вероятностью покупки.

**FR-R5-05.** Inference не исполняется в транзакции Order/Inventory. Тяжёлые операции/обучение идут offline. Установить bounded candidate limits, timeout, backpressure, circuit-breaker и reliable fallback. Метрики latency/fallback/error/modelVersion/coverage не содержат личные данные.

**FR-R5-06.** Drift и feature-schema compatibility должны измеряться реальными источниками, а не Boolean-заглушками; drift breach отключает ML influence, но не влияет на оформление заказов.

**Тесты:** R5-01 invalid checksum → fallback; R5-02 schema mismatch → fallback; R5-03 no active model → null probability; R5-04 concurrent atomic publish; R5-05 rollback; R5-06 timeout; R5-07 ACL; R5-08 stale listing/model score never overrides eligibility; R5-09 HTTP order remains independent; R5-10 max candidates and memory bound.

**DoD R5:** в одной staging сборке возможен controlled switch RULES→approved ML→RULES rollback без разрыва запросов и без изменения Order semantics.

## 9. ML-R6 — Product API / Web / Flutter integration (P1)

В первой итерации — только protected BuyerDemand recommendations, без публичного анонимного buyer profile.

**Предлагаемый (ещё не существующий) REST контракт:**
- GET /api/v1/buyers/demands/{demandId}/recommendations?limit=20
- требует verified buyer JWT + active buyer_account_link, SCOPE_deal:write, проверку ownership demandId;
- только list of eligible PUBLIC seller offers/публично разрешённых идентификаторов, прозрачные reason codes;
- endpoint под флагом zakaz.ml.recommendations.enabled=false по умолчанию;
- REST route и authority должны быть явно добавлены в ApiSecurityConfig (сейчас denyAll), с negative MockMvc tests.

Обязательные поля response: demandId, asOf, rankingSource, modelVersion nullable, featureSchemaVersion, items[{publicSellerRef/allowed fields, listingId, skuId, matchReasons, score/probability nullable}], hasMore/limit. Не раскрывать private seller identity и buyer profile. Не выдавать delivery fee, total или stock, если они не взяты из проверенного свежего источника.

Новый ranking engine должен повторно проверить visibility, eligibility, срок действия, owner permissions и экономические условия перед выдачей; stale model snapshot не является источником stock/price. Выбор и отправка приглашения требуют отдельного разрешённого user action или утверждённой business policy; ML score сам не рассылает сообщения.

React/Flutter отображают понятные причины совпадения, маркируют рекомендации; offline/error/fallback — без надписи «AI probability». SellerResponse ranking не отменяет сортировку по реальной полной цене для Verified Best Deal; покупатель вправе выбрать иной eligible seller.

**E2E:** authorized buyer creates demand → verified candidates → optional recommendations → controlled invitation → seller bid → buyer explicit accept → same existing CustomerOrder; no automatic reserve/accept.

## 10. Нефункциональные требования

| ID | Условие |
|---|---|
| NFR-01 | Code/runtime: Java 21, Spring MVC/JPA, PostgreSQL/Flyway, один modular monolith. |
| NFR-02 | Training data/version provenance: deterministic rebuild and stored hash; не пересчитывать признаки на основе будущих сведений. |
| NFR-03 | No PII/phone/email/raw messages/precise location as features без отдельного review и законного основания. Назначение, срок хранения, удаление/opt-out и доступ фиксировать до сбора. |
| NFR-04 | Degraded mode: ML OFF by default, fail closed API auth; baseline works without Tribuo/model/artifact store. |
| NFR-05 | PostgreSQL: миграции только новыми Vxx, ddl-auto=validate, никаких изменений прошлых checksums; миграционный rehearsal и rollback plan. |
| NFR-06 | Batch: bounded CPU worker pool, независимый DB connection budget, низкий приоритет vs checkout; не использовать unbounded virtual threads для обучения. |
| NFR-07 | Production batch после пилота: durable run/task state, SKIP LOCKED lease/retry/checkpoint, fencing, staging+atomic manifest promotion, idempotent replay, watermark and dead-letter visibility. In-process BoundedBucketExecutor этого не обеспечивает. |
| NFR-08 | Безопасный запуск: отдельный staging, synthetic accounts, encrypted secrets, metrics/log redaction, independent S2 and external OIDC UAT before exposure. |
| NFR-09 | Предварительный SLO для оценки: p95 дополнительного in-memory ranking ≤100ms при ≤20 кандидатов в контролируемом стенде; подтвердить бенчмарком, не считать измеренным фактом. |
| NFR-10 | При 5K-vu commerce квалификации измерить ML OFF/ON на одинаковых ресурсах; предварительный gate — не более 10% ухудшения order p95 и ноль нарушенных inventory/order invariants; зафиксировать и утвердить SLO после baseline. |
| NFR-11 | Ни customer segment, ни seller response score не являются финансовой репутацией, кредитным рейтингом или гарантией ответа. |
| NFR-12 | Наблюдаемость: input completeness, event lag, censored fraction, feature drift, training job status, baseline uplift, inference p95/p99, fallback counts, stale version. |

## 11. RED → S2 → GREEN test plan (исполняемые оракулы)

Для каждого slice обязательны S0 contract review, исполняемые RED-тесты, **независимый S2 review на exact SHA**, минимальный GREEN, regression, quality evidence. Self-review не считается независимым.

| Milestone | Обязательная доказательная база | Blocker |
|---|---|---|
| ML-S1 / R1 | JUnit + PostgreSQL Testcontainers реальный Catalog/HCC/SKU и 7 R1 tests | Demo class mismatch, незащищённая feature projection |
| ML-S2 / R2 | Atomic invitation/events, idem/ownership/rollback/24h/censoring tests | нет авторитетной availability и ACL |
| ML-S3 / R3 | Consistent snapshots, no leakage, reproducible dataset hash, late correction tests | неполные event windows или выдуманные negatives |
| ML-S4 / R4 | Fixed Tribuo version, repeatable trainer, chronological holdout и baseline metrics | нет real observations / inadequate sample / неулучшенная модель |
| ML-S5 / R5 | Artifact SHA, promote/rollback, drift/fallback/timeout, negative auth | неверная схема, модель без approval |
| ML-S6 / R6 | HTTP owner-scoped MockMvc, staging OIDC + React/Flutter E2E, unchanged commerce invariants | неготовый seller inbox или отсутствие реального E2E |
| ML-S7 / Release | CI exact SHA, independent S2, migration rehearsal, operational runbooks, performance 5K qualification | любые красные required gates |

**Команды**, соответствующие текущему Maven проекту:
- mvn -f backend/pom.xml test
- mvn -f backend/pom.xml verify (Docker + PostgreSQL Testcontainers)
- web: npm test, TypeScript build, Playwright against real staging when applicable
- mobile: flutter analyze, flutter test; actual device acceptance separately

### Обязательные негативные сценарии

- Клиент прислал чужой buyerId/sellerId, forged feature или видимость PRIVATE listing.
- Подмена SKU unit/category/attribute, недопустимое HCC, неразрешённый город.
- Дублированный request/event, concurrent seller invitation, rollback после persisted event.
- Изменение цены/публикации во время рейтинга; model ranking не меняет экономические snapshots.
- Закрытие окна 24h в пограничные моменты, late bid, account deactivation и unavailable inbox.
- Data leakage из outcome в train features; положительные и отрицательные метки с неполной историей.
- Невалидный model artifact, mismatch schema, zero overlap, timeout, memory bound, concurrent publish.
- Отсутствующий или несоответствующий consent для последующей персонализации и push.

## 12. Последовательность и допустимая поставка

| Очерёдность | Slice | Результат | Допуск следующего |
|---|---|---|---|
| 1 | ML-R1 Canonical Feature Integration | реальные SKU → объяснимые hard/soft checks → versioned features | PostgreSQL tests, S2 |
| 2 | ML-R2 Verified Invitations + Events | источник обучения и 24h attribution | authenticated source, idem, censor tests |
| 3 | ML-R3 Dataset Builder | point-in-time reproducible dataset | leakage/revisions checks |
| 4 | ML-R4 Tribuo Research Candidate | реальная candidate model либо честно BLOCKED из-за данных | holdout uplift и S2 |
| 5 | ML-R5 Registry / Inference | approved model or deterministic fallback | rollback/fail-safe tests |
| 6 | ML-R6 Buyer Recommendations | owner-protected ranking в Web/Flutter | staging E2E, performance |
| 7 | ML release gate | доказательство качества и безопасности | независимое approve, нет BLOCKER |

**Не связывать выпуск базового ZAKAZ checkout с наличием обученной модели:** ML feature flags остаются OFF до своего release gate. R1–R3 можно поставлять без Tribuo или без реального ML score.

## 13. Решения, которые нельзя принять молча (OPEN / S0)

| ID | Решение, предлагаемое по умолчанию | Риск до утверждения |
|---|---|---|
| DEC-ML-01 | First task seller_response_24h, не buyer purchase probability | Нет seller invitations/history |
| DEC-ML-02 | Availability timestamp — фактическое server persisted/seller-accessible inbox событие; push delivery отдельно | ложные negative labels |
| DEC-ML-03 | ML decisions только advisory, buyer explicitly triggers invitations | unsolicited outreach |
| DEC-ML-04 | Minimal non-PII event payload и versioned retention; отдельный consent-purpose review | privacy/legal |
| DEC-ML-05 | В пилоте artifact внутри защищённой БД с size limit, затем private object storage по результатам квалификации | безопасность сериализации и размер |
| DEC-ML-06 | В первой итерации штатный RULES ranking; ML включить только после benchmark/holdout | недостоверная эффективность |
| DEC-ML-07 | Отдельный SellerDemandInvitation контракт, не дублирующий PurchaseLead и BuyerDemand | повреждение действующих business flows |
| DEC-ML-08 | Existing order-only Outbox не расширять под произвольные events без отдельного ADR | order FK/payload/idempotency |

Принятие пользовательского ТЗ не является заменой S2 review и не означает автоматического утверждения этих operational/privacy решений.

## 14. Change management и Git rules

- Работать исключительно в уже существующей docs/adr-002-jpa-architecture-review; **не создавать ветку**.
- PR #2 остаётся Draft до независимых acceptance gates. В main ничего не переносить автоматически.
- В рамках **данного коммита** добавляется только это ТЗ; Java, SQL, workflows, CI и пользовательские интерфейсы не меняются.
- Перед реализацией сверять actual HEAD, schema/migrations и тесты; не переписывать V1–V22; не вводить параллельную версию order/deal/identity.
- Каждый slice оформлять с ID требований и исполняемыми тестами, точным SHA, наблюдаемыми результатами и списком оставшихся рисков.

## 15. Итоговая Definition of Done

**Документация:** утверждены по S0 реальные event/label/data contracts, source ownership, retention, out-of-sample metrics, model rollback и auth. Все изменения привязаны к существующим ADR.

**Implementation slices:** приложены PostgreSQL RED→GREEN evidence, обязательные отрицательные кейсы, regression, exact-SHA CI и independent S2 review. Невозможно объявить «реализован ML» на основании только FeatureRegistry и smoke tests.

**ML activation:** только с реальной квалифицированной моделью, независимым chronological holdout, согласованными метриками, целостным artifact+schema, безопасным owner-scoped API и измеренной производительностью. В отсутствие данных **правильный результат — действующий deterministic baseline с понятным reason code**, а не демонстрационная вероятность.

**Первый шаг разработки после утверждения:** ML-R1 / PostgreSQL RED tests для реального CatalogSkuFactsResolver → canonical matcher → FeatureVectorEncoder, с проверкой 20-символьного HCC, canonical uppercase attributes, missing values и ownership.
