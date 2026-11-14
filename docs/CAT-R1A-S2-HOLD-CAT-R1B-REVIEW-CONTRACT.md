# CAT-R1A — независимый S2 Review gate и CAT-R1B RED-контракт

**Дата:** 2026-11-14  
**Репозиторий:** `alimbetov/QRM`  
**Ветка:** `docs/adr-002-jpa-architecture-review`, PR #2 `Draft`; `main` не менять.  
**Статус:** `S2_REVIEW_REQUESTED_NOT_APPROVED` (требуется независимый реальный reviewer); CAT-R1B `RED_TEST_PREPARED`.  
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

## 6. Quality gates и таблица доказательств

| Гейт | Статус на момент составления |
|---|---|
| CAT-R1A Java + PostgreSQL GREEN / prior SHA | **PASS** `07369259`, run 43562005821 |
| Independent S2 reviewer GitHub APPROVE | **NOT PRESENT** |
| CAT-R1B 4 pilot PostgreSQL fixtures | CODE ADDED; ждать exact SHA CI |
| CAT-R1B 3 revision RED Testcontainers | CODE ADDED; ждать exact SHA CI |
| CAT-R1B implementation migration + Java pinned-rule reader | **HOLD until S2** |
| Existing order/QR/ML/Flutter/React regression | must rerun after every backend change |
| Live staging migration rehearsal | **NOT RUN** |
| Production expansion/catalog import | **NOT PERMITTED** |

## 7. Reviewer action

Открыть [Draft PR #2](https://github.com/alimbetov/QRM/pull/2), прочитать `docs/CAT-R1A-IMPLEMENTATION-S2-PACK.md`, `V28`, новые тесты, категорийные lookup/publication path и этот документ. Оставить **независимый GitHub APPROVE либо REQUEST_CHANGES с конкретными findings и SHA**. До этого gate нельзя снимать, даже при полном CI GREEN. Не создавать ветки и не выполнять merge.
