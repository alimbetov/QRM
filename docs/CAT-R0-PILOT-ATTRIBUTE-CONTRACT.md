# CAT-R0 — Утверждённый технический baseline модели атрибутов: четыре пилота

**Дата:** 2026-11-14  
**Статус:** **TECHNICAL_SCHEMA_BASELINE_ACCEPTED_FOR_CAT-R1_RED_TESTS**, не DB/S2/PRODUCT_OWNER/COMPLIANCE approved, не опубликованный справочник.  
**Основание:** [CAT-001](CAT-001-UNIVERSAL-CATALOG-EXPANSION-MASTER-SPEC.md), [CAT-R0 audit](CAT-R0-CONSISTENCY-AUDIT-2026-11-14.md), [DEC-UCE-001](DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md), [DEC-INTELLIGENCE-001](DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md).  
**Точные утверждённые HCC:** отсутствуют; приведённые коды вроде FRESH_APPLE / PASTEURIZED_MILK / SOCKET_INSTALL_SERVICE / SMARTPHONE — **subject keys для требований и тестовых фикстур**, не истинные HCC, не данные для production seed.

## 1. Почему это один каталог, но разные уровни фактов

Использовать **единую** Category/HccPath, AttributeDefinition, CategoryAttributeRule и controlled EnumOption. Развести предметные факты и меняющиеся условия:

- **CATEGORY:** стабильная классификация; внутри ProductType PHYSICAL/SERVICE/DIGITAL. Category ID UUID, HCC 20-digit string. Не упаковывать сорт, цвет, партии или место.
- **PRODUCT:** каноническая сущность с утверждённым canonical key и общими паспортными атрибутами, не цена продавца; для физического товара сохранить CanonicalProduct.
- **SKU:** вариант продавца, включая продаваемую единицу, фасовку, габариты, сорт, свойства конкретного артикула. Текущий SkuAttributeValue — базовый owner, не дублировать атрибут на Listing без причины.
- **LOT:** отдельная партия/поставка; урожай, производственная дата, срок годности, проверяемое происхождение и локальная доступность. Эта модель ещё **не реализована**.
- **SERVICE_SPEC:** отдельная каноническая спецификация работы (вид работ и базовая единица), ещё не реализована.
- **SERVICE_OFFER:** условия конкретного исполнителя (территория, материалы, цена, доступность), ещё не реализованы.
- **REQUEST_LINE / QUOTE_LINE:** нужное количество работ и согласованные условия в snapshot BuyerDemand/SellerBid; **не** постоянная характеристика канонического товара или услуги.
- **SEARCH:** read-only индексированный projection разрешённых публичных данных, не authoritative fact storage.
- **ML:** immutable versioned feature projection доверенных фактов, не источник их истины.

Не делать универсальный polymorphic subject_id без FK/owner protection. При реализации для новых scope — отдельные целостные таблицы или FK-таблицы, минимальный S2-reviewed schema diff.

## 2. Контракт Attribute Definition V2

Предлагаемый контракт записи:

~~~yaml
code: STORAGE_TEMP_MIN_C
kind: DECIMAL_SIGNED
dimension: TEMPERATURE
canonicalUnit: CEL
factScope: SKU
requiredPolicy: CONDITIONAL
filterable: false
min: -40
max: 60
precision: 19
scale: 3
schemaRevision: 1
status: PROPOSED
~~~

**Важно:** \`DECIMAL_SIGNED\`, \`DATE\`, \`DATETIME\`, \`REFERENCE\`, \`MULTI_ENUM\` — **новые проектные семантики**, текущий \`AttributeDefinition.Kind\` поддерживает только ENUM/DECIMAL/BOOLEAN/TEXT. Не записывать proposed kind в действующую БД до новой миграции/валидаторов. В минимальном GREEN можно расширить \`DECIMAL\` per-attribute min/max вместо выделения \`DECIMAL_SIGNED\`; окончательная физическая реализация требует ADR.

Поля и политика:
- \`code\` — глобально уникальный immutable business-code, uppercase, never human translation.
- \`factScope\` — single authoritative owner. Передача факта из SKU в lot не делает две версии идентичной истины.
- \`requiredPolicy\`: **M** mandatory в объявленном пилотном сценарии, **C** conditional (зависит от sell unit, типа, маркировки, бизнес-политики), **O** optional. M/C не равно мгновенная production mandatory migration.
- \`facet\`: Y может использоваться в typed search при наличии PG индекса и контролируемого значения; N нельзя напрямую делать faceted filter.
- \`match\`: \`HARD\` для обязательного соответствия scope; \`SELECTABLE_HARD\` — HARD только когда покупатель явно потребовал; \`SOFT_DEFAULT\` — сортировка после eligibility; \`INFO\` — справка; \`VERIFIED_ONLY\` и \`POLICY_GATE\` — доказательства/комплаенс до вывода или продажи. Нельзя делать все поля автоматически HARD в Tribuo.
- Валидация: bounds, canonical unit, decimal/DATE strict, dimension, stable enum option, isActive, provenance, missing-vs-unknown-vs-false, revision + applicable class.
- Любое изменение опубликованной схемы — новая ревизия, совместимый adapter либо явный fail-closed, никогда silent rewrite заказов.

## 3. Пилот A — Овощи и фрукты (яблоки + картофель)

**Классификационный охват:** свежие яблоки, картофель; категория отлична от сорта/фасовки/партии. CanonicalProduct \`FRESH_APPLE\` и \`POTATO\` — только примеры идентичности. \`CULTIVAR_CODE=GALA\` — SKU hard-attribute при точном требовании, не новая HCC-ветвь. Условные коды справочника подлежат лингвистической/предметной модерации.

| Код характеристики | Тип | Факт/владелец | Ед. | Треб. | Search | Matching / policy |
|---|---|---|---|---|---|---|
| `CULTIVAR_CODE` | ENUM | `SKU` | — | C | Y | SELECTABLE_HARD |
| `GRADE_CODE` | ENUM | `SKU` | — | O | Y | SELECTABLE_HARD |
| `CALIBER_MM` | DECIMAL | `SKU` | MM | O | Y | SELECTABLE_HARD |
| `PACKAGE_TYPE` | ENUM | `SKU` | — | M | Y | INFO |
| `PACKAGE_NET_MASS_KG` | DECIMAL | `SKU` | KG | C | Y | HARD_FOR_CONVERSION |
| `HARVEST_DATE` | DATE | `LOT` | — | C | N | INFO |
| `EXPIRY_DATE` | DATE | `LOT` | — | C | Y | HARD_WHEN_REQUESTED |
| `LOT_CODE` | TEXT | `LOT` | — | C | N | INFO |
| `ORIGIN_COUNTRY_CODE` | ENUM | `LOT` | — | C | Y | SELECTABLE_HARD |
| `ORIGIN_AREA_CODE` | REFERENCE | `LOT` | — | O | Y | SELECTABLE_HARD |
| `STORAGE_TEMP_MIN_C` | DECIMAL_SIGNED | `SKU` | CEL | C | N | POLICY_GATE |
| `STORAGE_TEMP_MAX_C` | DECIMAL_SIGNED | `SKU` | CEL | C | N | POLICY_GATE |
| `ORGANIC_CLAIM_STATUS` | ENUM | `LOT` | — | O | Y | VERIFIED_ONLY |
| `VARIETY_CODE` | ENUM | `SKU` | — | O | Y | SELECTABLE_HARD |
| `CROP_YEAR` | DECIMAL | `LOT` | YEAR | O | Y | INFO |

**Нормативные случаи:**
- Продавец A предлагает 12 KG рассыпных яблок, продавец B — BOX яблок; пока нет подтверждённого веса ящика, \`BOX\` не должен проходить точный запрос «12 KG» или участвовать в «самый дешёвый за KG».
- Продавец может выставить конкретные SKU типа «ящик», учитывая одну упаковку как PCS, с \`PACKAGE_NET_MASS_KG=12.0\`, но это **только основание для информативной нормализованной цены**. Текущий \`OrderConfirmationService\` требует точной единицы, автоматический заказ в KG из PCS запрещён до conversion/price ADR.
- Год урожая / срок / происхождение относятся к партии. В реальной БД такой Lot ещё нет. Запрещены заявленные «органические» свойства без проверенного evidence.
- Для отрицательной температуры storage применяются per-code bounds, тогда как \`STORAGE_GB=-18\` строго reject.

## 4. Пилот B — Продовольствие (молоко + фасованная бакалея/мёд)

**Классы:** пастеризованное молоко; мука/мёд с массовой фасовкой. Не смешивать объём L и массу KG без проверенного коэффициента и оснований; отличие рецептуры/аллергенов — потенциальное hard-ограничение.

| Код характеристики | Тип | Факт/владелец | Ед. | Треб. | Search | Matching / policy |
|---|---|---|---|---|---|---|
| `BRAND_CODE` | ENUM | `PRODUCT` | — | C | Y | SELECTABLE_HARD |
| `FAT_PERCENT` | DECIMAL | `SKU` | PERCENT | M | Y | SELECTABLE_HARD |
| `PACK_VOLUME_L` | DECIMAL | `SKU` | L | M | Y | HARD_FOR_CONVERSION |
| `PACKAGE_TYPE` | ENUM | `SKU` | — | M | Y | INFO |
| `INGREDIENTS_TEXT` | TEXT | `PRODUCT` | — | C | N | INFO |
| `ALLERGEN_CODES` | MULTI_ENUM | `PRODUCT` | — | C | Y | HARD_WHEN_REQUESTED |
| `STORAGE_TEMP_MIN_C` | DECIMAL_SIGNED | `SKU` | CEL | C | N | POLICY_GATE |
| `STORAGE_TEMP_MAX_C` | DECIMAL_SIGNED | `SKU` | CEL | C | N | POLICY_GATE |
| `PRODUCTION_DATE` | DATE | `LOT` | — | C | N | INFO |
| `EXPIRY_DATE` | DATE | `LOT` | — | C | Y | POLICY_GATE |
| `LOT_CODE` | TEXT | `LOT` | — | C | N | INFO |
| `COLD_CHAIN_REQUIRED` | BOOLEAN | `SKU` | — | C | Y | POLICY_GATE |
| `COMPLIANCE_CLAIM_STATUS` | ENUM | `LOT` | — | O | N | VERIFIED_ONLY |
| `PACK_NET_MASS_KG` | DECIMAL | `SKU` | KG | C | Y | HARD_FOR_CONVERSION |
| `COUNTRY_ORIGIN_CODE` | ENUM | `LOT` | — | O | Y | SELECTABLE_HARD |

**Нормативные случаи:**
- \`PACK_VOLUME_L=1\` и \`PACK_VOLUME_L=0.9\` не равны при точном запросе; цена за L отображается только при доверенном объёме. Нельзя автоматически превращать цену «за пакет» в цену «за литр» без достоверной информации.
- \`EXPIRY_DATE\` живёт на LOT, не в атрибуте универсального CanonicalProduct. Проверка актуальности — часть fulfillment/policy; текущий складской резерв это **не проверяет**.
- \`ALLERGEN_CODES\` требует controlled many-valued хранение и консервативную обработку UNKNOWN; free-text ingredients нельзя делать гарантирующим фильтром отсутствия аллергенов.
- Правило cold-chain является бизнес/комплаенс-gate, не рекомендацией нейросети.

## 5. Пилот C — Электротовары + электромонтаж

**Два различных продукта:** PHYSICAL SOCKET \`WALL_SOCKET\` (разновидность розетки) и SERVICE \`SOCKET_INSTALL_SERVICE\` (работа по установке). Связь \`INSTALLED_BY\` не превращает их в synonyms, substitute или один OrderLine.

### C.1 Электротовары

| Код характеристики | Тип | Факт/владелец | Ед. | Треб. | Search | Matching / policy |
|---|---|---|---|---|---|---|
| `BRAND_CODE` | ENUM | `PRODUCT` | — | C | Y | SELECTABLE_HARD |
| `MODEL_CODE` | ENUM | `PRODUCT` | — | C | Y | SELECTABLE_HARD |
| `RATED_VOLTAGE_V` | DECIMAL | `SKU` | V | M | Y | SELECTABLE_HARD |
| `RATED_CURRENT_A` | DECIMAL | `SKU` | A | M | Y | SELECTABLE_HARD |
| `MOUNT_TYPE` | ENUM | `SKU` | — | M | Y | SELECTABLE_HARD |
| `IP_RATING` | ENUM | `SKU` | — | O | Y | SELECTABLE_HARD |
| `COLOR` | ENUM | `SKU` | — | O | Y | SOFT_DEFAULT |
| `MATERIAL` | ENUM | `SKU` | — | O | Y | SOFT_DEFAULT |
| `CERTIFICATION_CLAIM_STATUS` | ENUM | `LOT` | — | O | N | VERIFIED_ONLY |

### C.2 Установка розетки как услуга

| Код характеристики | Тип | Факт/владелец | Ед. | Треб. | Search | Matching / policy |
|---|---|---|---|---|---|---|
| `WORK_TYPE` | ENUM | `SERVICE_SPEC` | — | M | Y | HARD |
| `WORK_UNIT` | ENUM | `SERVICE_SPEC` | — | M | Y | HARD |
| `WORK_POINTS` | DECIMAL | `REQUEST_LINE` | POINT | M | Y | HARD |
| `WALL_TYPE` | ENUM | `REQUEST_LINE` | — | O | Y | SELECTABLE_HARD |
| `MATERIALS_INCLUDED` | BOOLEAN | `SERVICE_OFFER` | — | M | Y | HARD_WHEN_REQUESTED |
| `SERVICE_MODE` | ENUM | `SERVICE_OFFER` | — | M | Y | HARD |
| `SERVICE_AREA_CODE` | REFERENCE | `SERVICE_OFFER` | — | M | Y | ELIGIBILITY_GATE |
| `EARLIEST_SLOT_AT` | DATETIME | `QUOTE_LINE` | — | C | Y | ELIGIBILITY_GATE |
| `SERVICE_PRICE_BASIS` | ENUM | `SERVICE_OFFER` | — | M | Y | HARD |
| `ESTIMATED_DURATION_MIN` | DECIMAL | `SERVICE_OFFER` | MIN | O | Y | SOFT_DEFAULT |

**Нормативные случаи:**
- Покупатель запрашивает установку 5 розеток в бетонной стене без включённых материалов. SellerBid с включёнными материалами — **другие коммерческие условия**, нужна отдельная общая смета и согласие; простой текстовый match не значит эквивалентную цену.
- \`WORK_POINTS=5\` — request/quote snapshot, не CanonicalServiceSpec для всех покупателей.
- \`WORK_UNIT=POINT\` не существует в текущем \`MeasurementUnit\`: это утверждённая концепция услуги, а **не разрешение** отправить её в существующий API заказа.
- \`SERVICE_AREA_CODE\` не должен получать точный адрес покупателя; проверка покрытия по официальному/проектному georegistry и разрешённой зоне.
- Монтажник не имеет \`InventoryBalance\` из «5 услуг»; capacity/slot будет другим ресурсным контрактом.
- Проверка обязательной квалификации/допусков (если применимо к работам) — отдельный review, не флаг из необоснованного seller input.

## 6. Пилот D — Электроника (смартфоны)

CanonicalProduct должен отличать бренд/модель, SKU — память/цвет/состояние. Не плодить HCC по памяти/цвету и не сливать два разных model codes в один CanonicalProduct.

| Код характеристики | Тип | Факт/владелец | Ед. | Треб. | Search | Matching / policy |
|---|---|---|---|---|---|---|
| `BRAND_CODE` | ENUM | `PRODUCT` | — | M | Y | SELECTABLE_HARD |
| `MODEL_CODE` | ENUM | `PRODUCT` | — | M | Y | SELECTABLE_HARD |
| `STORAGE_GB` | DECIMAL | `SKU` | GB | M | Y | SELECTABLE_HARD |
| `RAM_GB` | DECIMAL | `SKU` | GB | O | Y | SELECTABLE_HARD |
| `COLOR` | ENUM | `SKU` | — | O | Y | SOFT_DEFAULT |
| `CONDITION` | ENUM | `SKU` | — | M | Y | SELECTABLE_HARD |
| `NETWORK_STANDARD` | ENUM | `SKU` | — | O | Y | SELECTABLE_HARD |
| `SIM_TYPE` | ENUM | `SKU` | — | O | Y | SELECTABLE_HARD |
| `WARRANTY_MONTHS` | DECIMAL | `SKU` | MONTH | O | Y | SOFT_DEFAULT |
| `GTIN_CODE` | TEXT | `SKU` | — | O | N | INFO |
| `BATTERY_CAPACITY_MAH` | DECIMAL | `SKU` | MAH | O | Y | SOFT_DEFAULT |

**Нормативные случаи:**
- \`STORAGE_GB=256\` hard ≠ \`128\`, \`0256.00\` normalizes to \`256\`.
- Цвет может быть SOFT, но если покупатель выбрал exact \`BLACK\`, становится hard-атрибутом запроса.
- \`GTIN\` — необязательный идентификатор trade item, с проверкой формата/источника, не HCC и не доказательство подлинности.
- Individual \`IMEI\` не включать в общедоступный поисковый атрибут или модельный вектор.

## 7. Предметная политика общего SKU и canonical identity

**Выбранный design baseline:**
1. \`Category.id UUID\` + \`hccCode String(20)\` не менять.
2. \`CanonicalProduct.id UUID\` — existing reference. Добавить **после миграции и dedup audit** registry-approved \`canonicalKey\` для уникальной предметной идентичности (category + нормализованные manufacturer/model/subject identifiers там, где применимо). Значения \`canonicalKey\` не являются HCC, не пишутся через user input без утверждения.
3. \`SellerSku\` — разные варианты товарного предложения, включая \`salesUnit\`, с существующим publish immutability. Facts-политика определяется категорией и scope; price не переносится в canonical product.
4. \`InventoryLot\` — отдельный будущий owner для expiring perishable batches, qty/reservation allocation после физического ADR. \`CatalogSku\` не должен хранить универсальную дату годности для всех партий.
5. \`CanonicalServiceSpec\` и \`ServiceOfferTerms\` — отдельные будущие authoritative owners, но не новый HCC tree.
6. Не создавать generic EAV для всех предметов без FK, индексов и declared SQL typed validation; existing SKU value table возможно расширять, а для LOT/SERVICE хранить отдельные scope-aware таблицы.
7. Все hard comparison и unit conversions используют approved values и asOf/revision snapshot.

## 8. Единый read contract: Search → ML → Commerce

| Процесс | Использует | Нельзя делать |
|---|---|---|
| Seller catalog | Product + SKU typed schema, verified seller | свободный создание HCC/ENUM без approve |
| Search | approved multilingual terms + typed facets + PUBLIC Listing | выводить unpublished/private/unknown price как подтверждённые |
| Smart Demand | canonical Product or ServiceSpec + verified buyer requirements | demand по seller-specific SKU без явного контракта |
| Seller Matching | hard attributes, grade/cultivar/model/unit/geo/expiry policy | считать RELATED эквивалентным, подставлять факт из ML |
| Verified Best Deal | declared sell unit, known net content, all-in price and terms | \`BOX=KG\`, 0 delivery fee if unknown, service cost as product price |
| ML | task-specific feature schema, verified fact scope, revisions/asOf | categorical IDs as continuous ordinal; future labels and private PII |

**Предлагаемые версии пилотного feature space** (не существующие production models): \`CAT_FRUIT_MATCH_V1\`, \`CAT_FOOD_MATCH_V1\`, \`CAT_ELECTRICAL_SERVICE_MATCH_V1\`, \`CAT_SMARTPHONE_MATCH_V1\`. Имена и запись утверждаются S2, старый \`buyer_choice/v1\` не менять in-place. Обязательные presence flags при missing facts.

## 9. Machine-readable таблица и правила её использования

Файл [CAT-R0-PILOT-ATTRIBUTES.csv](cat/CAT-R0-PILOT-ATTRIBUTES.csv) синхронно формируется из этого контракта и содержит 60 предметных применений атрибутов. Повтор одного \`code\` в разных пилотах — намеренное переиспользование **одного глобального AttributeDefinition**, а не дубликат definition. Таблица — \`SCHEMA_DESIGN_ONLY\`, **не** Flyway seed, не import в production и не allocation HCC.

В CSV: \`pilot,subject,code,type,scope,unit,req,facet,match,rule\`. После CAT-R1 надо добавить \`approvedCategoryId\`, \`schemaRevision\`, \`verifiedValueVocabularyRevision\`, \`status\` и provenance на реальный, проверенный data seed. Не дополнять по догадке автоматически.

В CSV:
- A: 15 применений
- B: 15 применений
- C: 9 применений электротоваров + 10 услуг
- D: 11 применений электроники

## 10. Автоматизированные RED-тесты (oracle до GREEN)

| ID | Given → When | Then |
|---|---|---|
| P01 | category apples, cultivars Gala/other, buyer hard Gala | only Gala eligible |
| P02 | same apples, seller BOX, buyer 12 KG, unknown net mass | refuse exact unit match and cheaper-by-KG claim |
| P03 | known package 12 KG, two pack prices | normalized **display** price correct; no implicit checkout unit conversion |
| P04 | DECIMAL -18 on STORAGE_TEMP_MIN_C vs STORAGE_GB | temperature accepted under bounds; storage GB rejected |
| P05 | expired/undocumented lot under mandatory fresh policy | reject eligibility; no inventory/order movement |
| P06 | milk 900mL / 1L, buyer precise 1L | exact 1L only; no guessing from package label |
| P07 | missing/unknown allergen data and buyer hard restriction | fail closed; no false safety claim |
| P08 | 'масло' query with food and engine terms | intent disambiguation, no exact synonym cross-class |
| P09 | 'розетка' and 'установка розетки' | PHYSICAL vs SERVICE, related section separate |
| P10 | service quote 5 points vs quote 3 points | not hard compatible; no fake reserve |
| P11 | materials included changes total and scope | require new explicit buyer acceptance |
| P12 | phone 256GB vs 128GB under hard storage | 128 rejected before ML |
| P13 | user chooses exact color BLACK vs soft color | hard BLACK restricts, soft color only ranks |
| P14 | Search localized terms four locales | one immutable category ID for approved synonyms |
| P15 | category schema revision after order/bid/ML event | historical facts/replay unchanged |
| P16 | unapproved enum/certification provenance | no public verified claim/feature |
| P17 | seller unauthorized editing foreign category listing | 403/404 and no mutation |
| P18 | full public query + search pagination | PUBLIC-only and no private lot provenance |

Тестовые данные не получают произвольные production HCC; вместо этого использовать fixtures с валидными child/path codes в isolated Testcontainers, без публикации в production.

## 11. Approvals / HOLD

**Принято как техническая модель для написания CAT-R1 RED-тестов:** атрибуты, коды-сущности, scopes, типы, политики matching/search и 4 пилота.

**Не утверждено для релиза:** физическая БД-схема v2, exact regulatory category required rules, реальные code assignments HCC, canonical key generation and dedup, lot reservation policy, service booking/order, official country and territory vocabulary, legal/compliance, массовые импортируемые данные, полнота переводов и независимое S2.

CAT-R1 gate: schema ADR + PostgreSQL Testcontainers RED tests + независимый S2 Review + только затем миграция и GREEN. Совместимость \`OrderLine\`, \`SellerBid\`, \`CustomerOrder\`, \`InventoryReservation\`, \`PublicSearch\` и текущих ML adapters обязательна.
