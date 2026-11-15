# CAT-R1A — независимый S2 Review gate и CAT-R1B RED-контракт

**Дата:** 2026-11-14  
**Репозиторий:** `alimbetov/QRM`  
**Ветка:** `docs/adr-002-jpa-architecture-review`, PR #2 `Draft`; `main` не менять.  
**Статус:** `S2_REVIEW_REQUESTED_NOT_APPROVED` (требуется независимый реальный reviewer); CAT-R1B `RED_PROVEN / MIGRATION_HOLD`.  
**Нормативные документы:** [CAT-001](CAT-001-UNIVERSAL-CATALOG-EXPANSION-MASTER-SPEC.md), [CAT-R0](CAT-R0-CONSISTENCY-AUDIT-2026-11-14.md), [модель четырёх пилотов](CAT-R0-PILOT-ATTRIBUTE-CONTRACT.md), [CAT-R1A Implementation & S2 Pack](CAT-R1A-IMPLEMENTATION-S2-PACK.md).

## 1. S2 independence / approval

Проверен PR #2: **нет GitHub Reviews с APPROVE, нет назначенных reviewers** на момент подготовки. Сами разработчики/автор изменений не вправе объявлять независимый S2 завершённым. **Ни этот документ, ни технический самоконтроль ниже не являются независимым S2 APPROVE.**

Reviewer должен быть реальным лицом с подтверждённой правоспособностью к GitHub review и достаточной независимостью от реализации. Поле `S2_APPROVER` оставлено пустым до получения реального одобрения. Не выдумывать reviewer username и не отправлять самому себе APPROVE.

**S2 scope:** миграция V28 (причём текущая ветка также содержит V29 ML и V30 ML), `AttributeDefinition`, `SkuAttributeValue`, `CatalogPublicationPolicy`, новые тесты `CatalogR1ASchemaPostgresIT` и `CatalogR1AAttributePolicyTest`, совместимость `CategoryAttributeRule` и `CatalogSkuFactsResolver` с существующими публичными SKU. Проверить последний HEAD ещё раз перед APPROVE.

**CI evidence:** `0736925968b3ab685df4c7658069a77212d97ca0` / [run 43562005821](https://github.com/alimbetov/QRM/actions/runs/43562005821), 106 unit + 124 integration PASS; CAT-R1A 4 unit + 5 Postgres PASS, Flyway V1–V29 validated/applied, 89.71% line / 63.81% branch JaCoCo. **В текущей ветке есть новые коммиты (включая ML V30), поэтому этот CI не подтверждает все более поздние изменения.**

## 2. Положительные свойства CAT-R1A, подтверждённые кодом

- Единственная authoritative HCC Category + UUID и immutable HCC при update.
- `DECIMAL` допускает отрицательные значения только при явно утверждённом `min_decimal`, старые определения имеют `min=0`.
- `DATE` проверяется ISO `YYYY-MM-DD`, включая календарную валидность.
- `catalog_attribute_schema_revision` хранит append-only историю numeric policy; `SkuAttributeValue.definitionRevision` указывает определение в момент записи.
- PostgreSQL trigger отклоняет нарушение FACT_SCOPE SKU, типовые и диапазонные ошибки при записи вне Java.
- Размерности единиц (`catalog_unit`) физически отделены от checkout; нельзя автоматически конвертировать `BOX` в `KG`.
- Текущие order/inventory/QR/ML-таблицы не переписаны CAT-R1A.

## 3. Adversarial S2 findings (author-side review; NOT independent APPROVE)

| Finding | Приоритет | Код/почему это проблема | Требуемый результат |
|---|---|---|---|
| `S2-CAT-01` | **P0** | `CatalogSkuFactsResolver.resolveForSeller` берёт `rules.findByCategoryId` из **текущей** таблицы. Добавление обязательного правила после публикации делает прежнюю карточку ошибочной | pinned category-rule revision на момент публикации; historical read не пересматривает старую обязательность |
| `S2-CAT-02` | **P0** | `catalog_category_attribute_rule` хранит только `required` / `filterable`, отсутствует immutable rule history; действующие SQL UPDATE/DELETE меняют interpretation | new revision snapshot + immutable retention; governance process for schema publish |
| `S2-CAT-03` | P1 | SKU value pinned на `definitionRevision`, но это **не** полный snapshot category rule / option vocabulary / label | отдельно фиксировать category rule revision + definition revision + vocab revision + asOf для search/ML replay |
| `S2-CAT-04` | P1 | В V28 `parent_id=NULL` допускается у глубоких HCC-категорий для legacy; категорийная полнота дерева не доказана | freeze legacy semantics, additional governance validation at new category intake |
| `S2-CAT-05` | P1 | Прямой пересчёт `M3`→`L`, `G`→`KG` пока informational, в checkout стоит exact `MeasurementUnit` | не менять checkout / total price до отдельного conversion ADR |
| `S2-CAT-06` | P1 | Политики `LOT/SERVICE_SPEC/SERVICE_OFFER` объявлены в `fact_scope`, физические owner-таблицы и fulfillments ещё не созданы | тестировать ограничение: нельзя записывать service fact как SKU или заявлять поддержку service order |
| `S2-CAT-07` | P1 | V28 не содержит регулярной практики migration rehearsal на реальной непустой БД с ревизиями и статусами | перед релизом backup/restore, Flyway checksum, lock/plan, тест rollback через forward compensating migration |
| `S2-CAT-08` | P1 | CI QA-02 измеряет общий coverage; branch 63.81% не доказывает все SQL-trigger error paths | отказные PostgreSQL-тесты на изменение rev, FK bypass, HCC и границы сумм/дат |

**S2 disposition:** HOLD, пока независимый reviewer не подтвердит P0 устранение/approved risk treatment и PR review. CAT-R1B RED — средство доказательства P0, но не право на обход S2.

## 4. CAT-R1B: схема версий правил (proposed technical contract)

### 4.1 Current projection vs immutable history

Сохранить действующий `CategoryAttributeRule` (`category_id,attribute_id,required,filterable`) как **текущую write/read projection для DRAFT**. Не создавать второй независимый AttributeDefinition или HCC. Дополнительно:

- `catalog_category.attribute_schema_revision bigint DEFAULT 1` — ревизия согласованного набора правил конкретной Category. **Не путать** с существующим `Category.version` (JPA optimistic lock) или `AttributeDefinition.schemaRevision`.
- `catalog_category_rule_revision(category_id,revision,attribute_id,required,filterable,attribute_definition_revision,...) ` — **immutable снимок всего набора правил** на выбранную ревизию, FK на существующие категории и атрибуты; `UNIQUE(category_id,revision,attribute_id)`.
- `catalog_category_schema_publication(category_id,revision,approved_by,approved_at,checksum,...)` — метаданные для одобренных ревизий; запрещено автоматически поднимать `approved_by` через seller mutation; для legacy rev1 provenance отмечать `LEGACY_BASELINE`, не выдумывать human approval.
- `catalog_listing.category_schema_revision` — закреплённая ревизия правил категории на первой публикации. Для текущих `PUBLIC/UNLISTED/ARCHIVED` сохранить baseline reference/legacy status, для `DRAFT` — `NULL` до публикации. Нельзя менять pin задним числом.
- `catalog_category_rule_revision` и публикационные метаданные нельзя UPDATE/DELETE, даже при изменении текущей политики; только append нового approved revision.
- Rule proposal обновляет текущую политику **только на этапе утверждённого publish**. Рекомендуемый pipeline: draft → validate → review → approve → immutable snapshot → atomic activation. Способ bulk transactional DB update и lock ordering утверждает S2, не принимать SQL с N раздельными ревизиями за один batch без решения.
- Новые публикации и DRAFT mutation используют актуальные утверждённые правила. Уже опубликованные SKU — закреплённый полный rule snapshot; изменение новых правил не делает их автоматически DRAFT/invalid, если только не действует отдельно зафиксированная safety revocation.
- Enum revocation/regulated listings may legitimately fail closed by safety policy, но должен отличаться от обычной taxonomic schema revision. Не отключать существующую защиту revoked ENUM silently.
- Покупательские demand/bid/order immutable snapshots и текущие exact SKU unit/QR contracts не менять.

### 4.2 Правила чтения/конкурентности

1. При публикации `CatalogPublishingService`: seller ownership, listing FOR UPDATE, reading *current approved category schema* under consistent lock, validating exact SKU required rules, pin current revision, QR issue in one transaction.
2. При чтении `CatalogSkuFactsResolver`: `PUBLIC` использует **pinned snapshot** (not current RuleRepository), `DRAFT` остаётся в текущем editor contract; hard safety gates (inactive category, revoked ENUM, seller ownership) проверяются по действующей safety policy.
3. Каталогное изменение с category lock и CAS expected revision: при конфликте ref/rows — fail (409/internal conflict), no partial revision. Отдельная транзакция для утверждения; batch import из каталога 4 пилотов не одобрять без REVIEWER.
4. Для POSTGRES constraints надо обеспечить уникальность ревизий, непрерывность последовательности, отсутствие snapshot gaps и историческую идемпотентность.
5. S2 обязан решить, если CategoryRule изменения не допускаются без workflow, как старые тестовые/внутренние `CategoryAttributeRuleRepository.save` продолжат создавать первичный набор правил для тестовых DRAFT категорий.

### 4.3 Совместимость и rollback

- **Не изменять** V1–V30 и исторические статусы CustomerOrder/BuyerDemand/SellerBid/InventoryReservation/ML events.
- Новый номер миграции брать **из актуального HEAD после S2**, не закреплять в ТЗ `V31` без проверки конфликтов.
- Legacy snapshot seed/backfill из текущих category rules делается как `LEGACY_BASELINE`, без фиктивной подписи approver; не переписывать `catalog_sku_attribute_value.definition_revision`.
- Старые `PUBLIC`/QR ссылки должны оставаться валидными; lock + migration ordering проверить на непустой staging БД.
- Вместо удаления миграции — reviewed forward-only rollback; обе модели могут сосуществовать под feature flag до квалификации.
- Query helper обязан использовать категории/правила из авторитетных таблиц, не offline CSV как источник business truth.

## 5. Четыре реальные PostgreSQL fixture-вертикали (test-only)

Сохранён `backend/src/test/java/kz/zakaz/catalog/CatalogR1BFourPilotPostgresIT.java`:

| Пилот | Тестовые факты | Acceptance oracle |
|---|---|---|
| A. Овощи и фрукты | Fresh apples `GALA/FUJI`; упаковка PCS, подтверждённая нетто масса 12 KG | разные сорта не сливаются; `BOX` без утверждённой конверсии не появляется |
| B. Продовольствие | Milk 1.0 L/0.9 L, жирность 3.2%, температура -18 °C | bounds в PG, precision and volume distinct; expired lot **NOT COVERED** |
| C. Электрика + монтаж | PHYSICAL розетка 16 A, SERVICE установка; `WORK_TYPE` имеет `fact_scope=SERVICE_SPEC` | нет фальшивого service SKU и попытка записать service-spec факт в SKU отвергается |
| D. Электроника | Smartphone 128/256 GB, цвет `BLACK/WHITE` | две distinct SKU; negative storage отвергается БД |

Сгенерированные тестовые HCC, SKU, UUID — **изолированные fixtures**, а не утверждённые записи каталога или seeds для production. Фикстуры не проверяют end-to-end ServiceOrder, expiry lot, four-locale search, ML learning, фото или compliance claims.

### 5.1 Обязательные RED rule-revision тесты

`CatalogR1BRuleRevisionPostgresIT.java`:

- **CAT-R1B-RED-01:** уже опубликованный SKU остаётся читаемым после новой required характеристики в Category.
- **CAT-R1B-RED-02:** новая политика создаёт новую category revision, старый snapshot остаётся полный и неизменный, новая revision отражает новую политику.
- **CAT-R1B-RED-03:** UPDATE/DELETE исторической ревизии отклоняется напрямую PostgreSQL.

Текущая V30 не содержит `attribute_schema_revision` у Category и `catalog_category_rule_revision`: RED обязан документировать отсутствие этих ограничений, до реализации не считать GREENS доказательством snapshot semantics.

**Фактические результаты RED/GREEN test-first (PostgreSQL 17):**

- [RED workflow #43593510809](https://github.com/alimbetov/QRM/actions/runs/43593510809), точный коммит `f7045f351dfe6b367a9b0fff4b248956d41e724c`: `CatalogR1BFourPilotPostgresIT` **4/4 PASS**; `CatalogR1BRuleRevisionPostgresIT` **3 executed: 1 FAIL + 2 ERROR, 0 skipped**.
- Смысл первого RED: `alreadyPublishedSkuMustStayReadableAfterFutureRequiredRule` падает, поскольку новый required rule задним числом блокирует старый SKU; это воспроизводимый domain/regression gap.
- Смысл остальных двух RED: в действующей V30 отсутствует `catalog_category.attribute_schema_revision` (ошибка PostgreSQL `column "attribute_schema_revision" does not exist`), поэтому нет доказанной immutable revision history.
- RED fixture code прошёл проверку на уникальность тестовых HCC и global AttributeDefinition.code; более ранние падения из-за дублирования fixture keys устранены в `f7045f35`.
- В этом же общем интеграционном workflow присутствуют **два независимых ML-R3 error** из-за отсутствия `ml_invitation_bid_attribution`. Это отдельная параллельная доработка, **не доказанный эффект CAT-R1B**; общий CI не GREEN.
- После доказанного RED класс `CatalogR1BRuleRevisionPostgresIT` отмечен явным `@Disabled("CAT-R1B S2 HOLD ...")`, commit `bf87f90b`. **Все 3 pending RED tests сохранены, но в последующем CI будут SKIPPED до миграции и S2.** Нельзя выдавать их за GREEN. Четыре реальные пилотные Testcontainers-фикстуры остаются активны.

**Обязательный шаг перед CAT-R1B GREEN:** убрать `@Disabled`, получить настоящие три успешных теста на новой Flyway migration + replay/snapshot tests; затем полная регрессия и независимое S2.

## 6. Quality gates и таблица доказательств

| Гейт | Статус на момент составления |
|---|---|
| CAT-R1A Java + PostgreSQL GREEN / prior SHA | **PASS** `07369259`, run 43562005821 |
| Independent S2 reviewer GitHub APPROVE | **NOT PRESENT** |
| CAT-R1B 4 pilot PostgreSQL fixtures | **PASS 4/4** on `f7045f35`, PostgreSQL 17/Testcontainers |
| CAT-R1B 3 revision RED Testcontainers | **RED PROVEN** 1 FAIL + 2 ERROR, then explicit `@Disabled` S2 HOLD; **not GREEN** |
| CAT-R1B implementation migration + Java pinned-rule reader | **HOLD until S2** |
| Existing order/QR/ML/Flutter/React regression | full current CI not GREEN: 2 unrelated ML-R3 PostgreSQL errors; owner investigation required |
| Live staging migration rehearsal | **NOT RUN** |
| Production expansion/catalog import | **NOT PERMITTED** |

## 7. Reviewer action

Открыть [Draft PR #2](https://github.com/alimbetov/QRM/pull/2), прочитать `docs/CAT-R1A-IMPLEMENTATION-S2-PACK.md`, `V28`, новые тесты, категорийные lookup/publication path и этот документ. Оставить **независимый GitHub APPROVE либо REQUEST_CHANGES с конкретными findings и SHA**. До этого gate нельзя снимать, даже при полном CI GREEN. Не создавать ветки и не выполнять merge.


## 8. Additional S2 blockers discovered during CAT-R1B review (2026-11-14)

**Reviewer independence:** PR #2 still has no submitted GitHub review or assigned reviewer on the independently inspected HEAD `c57b22ecbcc7e4f45b4c64d36fcf76b6f3901c48`. The existing issue comment requesting review is not approval. No real external identity may be inferred or impersonated by this implementation.

### S2-CAT-09 — Rule INSERT vs separately approved activation (P0)

The CAT-R1B RED test `ruleMutationMustCreateReproducibleImmutableCategorySchemaRevision` currently performs **a direct INSERT** into `catalog_category_attribute_rule` and expects `catalog_category.attribute_schema_revision` to increase immediately. This describes **automatic activation of an unreviewed rule**. But CAT-001 and §4.1 above require `proposal → validate → review → approve → atomic activation`. Those are incompatible acceptance oracles.

**Required S2 resolution before GREEN:** do not implement a trigger that silently activates every raw INSERT/UPDATE/DELETE of a current rule as an approved schema revision. Define one of two mutually exclusive paths:

- **Recommended:** separate *staging/proposal* from the *active* `catalog_category_attribute_rule`. A privileged approval transaction locks the Category, confirms expected current revision, validates all rules, stores the immutable full snapshot and approval provenance, swaps the active projection and increments Category schema revision atomically. Any unauthorized raw change after Category has been published must fail closed.
- **Alternative, not recommended:** automatically version every low-level rule mutation with an auditable system actor. This does **not** meet human approval provenance and must not be called approved. Requires an explicit deviation ADR and retention of intermediate partial-snapshot states.

The **existing RED trace remains valid evidence of missing history** but the literal auto-increment oracle is **NOT approved** as the target GREEN behavior. Rewrite that test to invoke an approved, privileged governance transaction (or a test-only approved SQL procedure with explicit actor/provenance) *after* S2 selects an API. Do not remove `@Disabled` before then.

### S2-CAT-10 — Publication and approval transaction race (P0)

`CatalogPublishingService.publishForSeller` currently locks the Listing, not the Category governance row. The proposed activation writes new rules/category revision separately. Thus a concurrent seller publication can validate an old subset and then pin an unrelated new revision unless both paths serialize on a shared Category lock with a deterministic acquisition order.

**Required oracle:** transaction A begins publication, transaction B approves new rule; exactly one consistent Category revision is used for both full-rule validation and pinned `catalog_listing.category_schema_revision`, or one operation aborts and retries. No mixed snapshot, dangling revision, or partially updated QR/listing is allowed.

### S2-CAT-11 — Direct SQL writes on historical pinned listing (P0)

The current `catalog_listing` has no `category_schema_revision` column; `CatalogSkuFactsResolver` re-reads current rules. Simply adding a column is insufficient if a privileged SQL UPDATE can rewrite a non-null pinned revision, or if a migration leaves historical PUBLIC/UNLISTED/ARCHIVED without an auditable legacy snapshot.

**Required oracle:** existing published SKU retains its full original rule snapshot under later approved rule additions; unauthorized updates to `catalog_listing.category_schema_revision` and UPDATE/DELETE of `catalog_category_rule_revision` are rejected at PostgreSQL level. Legacy backfill carries provenance `LEGACY_BASELINE`, never fabricated human approver.

### S2-CAT-12 — Pilot fixture authoritativeness boundary (P1)

`CatalogR1BFourPilotPostgresIT` currently sets Listing `PUBLIC` by raw SQL and uses generated fake HCC root codes; this is valid for isolated **fact read** tests but **does not** exercise real `CatalogPublishingService`, public QR issuance, authorized seller API, approval governance, or actual ServiceOrder. Do not misrepresent 4/4 as full four-pilot E2E.

**Before CAT-R1B GREEN:** add a separate PostgreSQL business-flow test for approved category-rule activation plus real seller publication/QR and old-versus-new revision reads. Keep fake test HCC scoped to Testcontainers only.

### S2 gate / implementation rule

Until a real independent GitHub APPROVE explicitly addresses S2-CAT-01/02 and S2-CAT-09/10/11, the only permitted CAT-R1B work is review, test-only evidence, or documentation. Do not add a category-rule Flyway migration or change active published SKU rule lookup under a pseudo-approval. Existing CAT-R1A V28, other ML migrations and historical orders/QR remain immutable.


### S2-CAT-13 — Reuse of one global definition across pilot categories (P1)

An additional active PostgreSQL test was added in `CatalogR1BFourPilotPostgresIT` at code commit `cbec38407384ee4dc41583f806bf9245f6551719`:

- `oneGlobalAttributeDefinitionIsReusableAcrossPilotCategoriesWithoutFactLeakage`: two different ProductTypes? No: two PHYSICAL categories (electrical and electronics), **one** global `PILOT_GLOBAL_COLOR` AttributeDefinition with category-scoped rule applicability, two published Listings, distinct seller/SKU-specific values and forbidden cross-seller read.
- Invariant: **one definition, multiple category rule memberships, no SKU fact leakage**.
- This is the **fifth** active fixture test in the class. **Verified 5/5 PASS** at source commit `cbec38407384ee4dc41583f806bf9245f6551719` in [Java Backend run 43598587009](https://github.com/alimbetov/QRM/actions/runs/43598587009).

The latest pre-change baseline CI at SHA `c57b22ecbcc7e4f45b4c64d36fcf76b6f3901c48` (run [43594738635](https://github.com/alimbetov/QRM/actions/runs/43594738635)) had a PostgreSQL job **infrastructure** failure because Maven Central could not resolve `org.apache.maven.plugins:maven-jar-plugin:3.4.2` before integration tests started; fast lane succeeded. Failed PostgreSQL job was manually re-run. The CI failure is **not** proof of broken catalog semantics, but it is also **not** a qualification pass.

**Verified retries and current CAT-R1B fixture GREEN:** [retried run 43594738635](https://github.com/alimbetov/QRM/actions/runs/43594738635) succeeded (110 unit / 144 integration PASS; 3 rule-revision RED tests explicitly skipped). [Source run 43598587009](https://github.com/alimbetov/QRM/actions/runs/43598587009) succeeded after adding fifth fixture: **110 unit / 145 integration PASS, 0 failures/errors, 3 rule-revision tests SKIPPED**. CAT-R1B four-pilot fixture class **5/5 PASS**; CAT-R1A PostgreSQL **5/5 PASS**. JaCoCo overall **89.85% lines, 64.26% branches**. All four CI workflows for the source commit passed. **These GREEN results do not clear independent S2 HOLD.**
