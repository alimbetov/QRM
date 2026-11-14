# CAT-001 — Универсальный каталог ZAKAZ: согласованная стратегия расширения товаров и услуг

**Версия:** 0.1 / 2026-11-14  
**Статус:** DRAFT — отдельное нормативное ТЗ-кандидат; существующие утверждённые решения сохраняют приоритет, новые архитектурные и регуляторные решения требуют S2 и явного утверждения.  
**Репозиторий:** alimbetov/QRM  
**Единственная рабочая ветка:** docs/adr-002-jpa-architecture-review; PR #2 Draft; main не изменять.  
**Предмет:** от сельскохозяйственного сырья, фермерской продукции и продовольствия до повседневных потребительских товаров, профессиональных и бытовых услуг, цифровых предложений.  
**Результат этого изменения:** ТЗ и стратегия, НЕ реализация схем БД/API/UI, НЕ импорт справочника, НЕ подтверждение готовности ML.

## 0. Место документа в действующей системе требований

CAT-001 — единый рабочий контракт расширения справочника, а не параллельная архитектура:

1. docs/REQUIREMENTS.md — master business requirements (v2.0).
2. docs/ADR-002-MVC-JPA.md — Spring MVC + JPA + PostgreSQL/Flyway.
3. docs/DEC-UCE-001-UNIVERSAL-CLASSIFICATION.md — HCC, классификация, typed attributes и предложения по UniversalDictionary.
4. docs/DEC-INTELLIGENCE-001-TASK-SEGMENTATION.md — общий canonical dictionary и самостоятельные ML feature spaces.
5. docs/SEARCH-001-UNIFIED-DISCOVERY.md и docs/SEARCH-001-IMPLEMENTATION-CONTRACT.md — Search и фото/словарь.
6. docs/SMART-DEAL-R2.md, docs/DOMAIN-CONTRACTS.md, docs/ML-R2-IMPLEMENTATION-STATUS.md — demand/bid/order и ML event pipeline.
7. Реальные Flyway, JPA, HTTP, React, Flutter и PostgreSQL тесты в текущем HEAD — источник истины о том, что уже реализовано.

При противоречии CAT-001 не отменяет существующий продакшен-контракт автоматически. Создать явный ADR/DEC с вариантами, последствиями, миграцией и тестами; лишь затем менять код. Каждая редакция CAT-001 содержит журнал решений и матрицу совместимости. Область CAT-001 не затрагивает текущие order/reservation/outbox транзакции без отдельного утверждённого среза.

## 1. Цель и продуктовые инварианты

**Цель:** один постоянно расширяемый, многоязычный и управляемый реестр товарных и сервисных понятий для публикации, поиска, сравнения, BuyerDemand, SellerBid, Smart Deal, агрегации спроса и воспроизводимых ML feature projections.

Три независимых вопроса:
- **WHAT**: классификационная категория и утверждённый канонический предмет/спецификация работы (не продавец).
- **VARIANT / FACTS**: сорт, масса, упаковка, модель, размер, состояние, объём, условия и т. п. с типом, источником и версией.
- **OFFER / EXECUTION**: продавец, цена, единица продажи, остаток, лот, место/время, доставка/выезд, согласование, резервирование, исполнение.

Инварианты CAT-I:
- I01: ровно одна authoritative иерархия категорий — существующая Category/HCC, не создавать независимые деревья для Search/ML.
- I02: использовать существующие UUID Category/Product/Listing/SKU для всех действующих FK; целевой bigint DictionaryNode обсуждать отдельно, не заменять UUID без ADR и миграции.
- I03: существующий 20-символьный HCC 2/2/3/5/8 — строковый, не числовой, с сохранением ведущих нулей. Не перенумеровывать опубликованные коды и не кодировать в HCC сорт, упаковку, партию, цвет или адрес.
- I04: публичная идентичность — Category/CanonicalProduct/CanonicalServiceSpec; цена и доступность — только seller-owned Listing/SKU/Offer. Одна категория не означает эквивалентные товары.
- I05: PHYSICAL / SERVICE / DIGITAL не взаимозаменяемы; соответствия товаров/услуг — именованные отношения, не синонимы.
- I06: публикация только после проверки seller membership, typed facts, moderation/restrictions, допустимости единиц и требований конкретного вида предложения.
- I07: Search и ML читают только сервером утверждённые словари/факты, не доверяют продавцу/LLM для новых публичных ключей.
- I08: HARD-условия, owner, visibility, geographic coverage, stock/capacity, expiry/price проверяются детерминированно до ML ranking. Модель не создаёт заказ или резервирование.
- I09: исторические BuyerDemand, SellerBid, CustomerOrder, Search/ML события и feature snapshots не переосмысляются при изменении словарей; фиксировать версии.
- I10: KZT BigDecimal, один CustomerOrder — один seller, согласованные цены и существующий QR-first flow сохраняются.
- I11: никаких автоматических утверждений об органическом качестве, происхождении, безопасности, наличии сертификатов или разрешении продажи без проверенного evidence.
- I12: release readiness = код + реальные PostgreSQL/API/E2E/нагрузочные доказательства + независимый S2, а не ярлык IMPLEMENTED.

## 2. Состояние репозитория на момент подготовки (проверить повторно на execution HEAD)

Подтверждённые классы: Category, HccPath, AttributeDefinition (ENUM/DECIMAL/BOOLEAN/TEXT), CategoryAttributeRule, AttributeEnumOption, CanonicalProduct, SellerListing, SellerSku, SkuAttributeValue, CatalogPublishingService, PublicCatalogReadService, PublicSearchReadService. Поиск использует PostgreSQL FTS/pg_trgm, версионированный SearchTerm, keyset cursor и явную проверку PUBLIC. Есть FeatureRegistry, FeatureVectorEncoder, CanonicalFeatureAdapter; ML-R2 — журнал событий и внутренний consumer, не доказанная обученная production recommendation модель. React SellerCatalogWorkspaceService пока принимает только PHYSICAL, TRACKED; SERVICE/DIGITAL HTTP order flows не квалифицированы. Данные реального утверждённого массового отраслевого справочника отсутствуют/не подтверждены.

Следовательно CAT-001 — staged expansion; не выполнять массовый seed до авторитетной ревизии HCC, миграций и negative tests.

## 3. Ландшафт доменов: что обязано покрывать единое дерево

Следующие ветви — **концептуальные семейства**, не утверждённые 20-значные HCC-коды. Конкретные места, глубину и владельцев утверждает taxonomy board на CAT-R1. Бизнес-направление витрины может группировать несколько Category; «для дома», «фермерское», «для дачи» — фасеты/витрины, а не дубли дерева.

| Домен | Примеры конечных товарных/сервисных классов | Особые факты |
|---|---|---|
| AGR-01 Растениеводство, сельхозсырьё | зерно, масличные, бобовые, овощи, фрукты, ягоды | культура, сорт, год урожая, калибр, качество, партия |
| AGR-02 Животноводство и продукция | молоко сырое, яйца, мясо, шерсть, мёд | происхождение, условия хранения, прослеживаемость, допуски |
| AGR-03 Семена, саженцы, посадочный материал | семена овощей, рассада, плодовые саженцы | вид, сорт, партия, всхожесть/сертификат при применимости |
| AGR-04 Сельхозоборудование и расходные материалы | полив, ручной инвентарь, запчасти, тара | совместимость, размер, мощность, запасные части |
| FOOD-01 Свежие продукты | овощи, фрукты, молочные, мясо, рыба, яйца | срок годности/условия хранения, партия, масса |
| FOOD-02 Переработанные продукты | мука, крупа, консервы, хлеб, напитки, масла | состав, аллергены, масса/объём, упаковка, бренд |
| FOOD-03 Готовая еда | блюда, наборы питания, выпечка | состав, аллергены, время изготовления/доставка |
| HOME-01 Товары для дома и быта | мебель, посуда, текстиль, бытовая химия | размеры, материал, назначение |
| FASH-01 Одежда, обувь, аксессуары | одежда, обувь, сумки | пол/посадка (если применимо), размерная система, материал |
| TECH-01 Электроника и бытовая техника | смартфоны, ноутбуки, холодильники | модель, память, мощность, состояние, гарантия |
| BUILD-01 Стройматериалы и инструменты | плитка, кабели, розетки, УШМ | размер, мощность, материал, единицы, совместимость |
| AUTO-01 Автотовары | шины, аккумуляторы, запчасти | модель автомобиля/совместимость, сезон, габарит |
| HEALTH-01 Повседневные товары здоровья и гигиены | гигиенические товары, средства ухода | состав, назначение, ограничение продажи/рекламы |
| CHILD-01 Детские товары, книги, игрушки | игрушки, учебные материалы | возрастная маркировка/безопасность при применимости |
| SERV-AGR Сельхозуслуги | вспашка, посев, сбор урожая, аренда техники с оператором | гектар/час/рейс, техника, территория, сезон |
| SERV-HOME Домашние услуги | электромонтаж, сантехника, уборка, ремонт | объём работ, материалы, выезд, доступность |
| SERV-MOB Транспорт и логистика | перевозка мебели, курьерская доставка | маршрут/география, груз, грузоподъёмность, слот |
| SERV-EDU Обучение и специалисты | репетиторство, курсы, бытовые консультации | язык, формат, длительность, квалификация |
| DIG-01 Цифровые продукты | ПО, лицензии, курсы, VR-уроки, шаблоны | лицензия, срок/объём права, способ предоставления |

Не включать запрещённые либо регулируемые подкатегории в открытый публичный импорт по умолчанию. Для пищевой продукции, агрохимии, ветеринарии, лекарственных и иных ограниченных товаров требуется отдельная актуальная юридическая/комплаенс-проверка Казахстана до публикации; техническая классификация не даёт разрешения на продажу.

## 4. Канонические понятия, товары, варианты, партии и предложения

### 4.1 Антидублирование
- Category: «яблоки свежие», «молоко пастеризованное», «установка розетки».
- CanonicalProduct: нормализованная сущность для сопоставления идентичных/строго эквивалентных товаров. Не создавать один CanonicalProduct на каждого продавца и каждую цену.
- SellerListing: объявление одного продавца со статусом DRAFT/PUBLIC/UNLISTED/ARCHIVED.
- SellerSku: один продаваемый вариант и unit/price из существующей модели; упаковка, размер, сорт, объём и др. проверенные факты.
- **InventoryLot / Batch (предлагаемый отдельный bounded context):** партия продукции с происхождением, сроком, фактическим количеством, документами; не путать с Product/Category/SKU. Стартовая модель резервирования по SKU остаётся неизменной до отдельного ADR по lot allocations.
- **CanonicalServiceSpec (candidate):** утверждённый шаблон работ и параметров; не GTIN и не физический склад.
- **ServiceOfferTerms (candidate):** условия конкретного исполнителя, район, цена за единицу работы, материалы, расписание, SLA. Точная граница SellerListing/ServiceOfferTerms требует S2.

### 4.2 Сельхозтовары и продовольствие
Различать:
1. Канонический класс «яблоки свежие» и сорт «Гала» (факт продукта/варианта, не дерево HCC).
2. Единицы предложения: KG, PCS, BOX и их явные отношения; BOX не переводить в KG без массы упаковки из подтверждённых данных.
3. Цена за 1 KG и цена за 1 BOX — разная база; допускается сравнение только после точного пересчёта в общую единицу с provenance.
4. Переменный вес: заказанная и фактическая масса, допустимые отклонения и явный пересчёт/акцепт — отдельный коммерческий ADR, не обходить immutable OrderLine.
5. Срок годности, дата изготовления/сбора, температурный режим, lot/batch, срок доступности — в утверждённых фактах уровня lot/offer, не постоянно в CanonicalProduct.
6. «Эко», «органик», сортность, регион происхождения, ветеринарный/фитосанитарный статус: claim + evidence + verification state; не принимать маркетинговое заявление за проверенный факт.
7. Freshness и cold-chain eligibility не сводить к текстовому совпадению; нужны детерминированные политики и отдельные приёмочные сценарии.

### 4.3 Услуги
SERVICE не резервируется через InventoryBalance как обычный TRACKED SKU. Нужен type-specific процесс ServiceDemand → SellerBid → scope/time agreement → ServiceOrder/booking → execution → confirmation; точная связь с CustomerOrder утверждается после review. В пилоте допускается запрос и bid без обещания бронирования. Не создавать фиктивные складские остатки или «доставку» услуги. Ограничения: территория, календарь/время, квалификация, материалы, перечень включённых работ, цена за час/точку/м²/рейс и минимальный объём. Смешанная корзина товар + услуга может показываться совместно, но атомарный комбинированный заказ откладывается.

## 5. Управляемые справочники и модель атрибутов

### 5.1 Переиспользуемые namespaces
- CLS — Category / HCC / ancestry / domain / revision.
- ATR — сорт, культура, тип продукта, материал, цвет, размерная система, упаковка, аллерген, объём, совместимость, вид работ.
- UOM — измерение, базовая единица, конверсия/точность/размерность; PACK/BOX без подтверждённого размера не конвертируем.
- GEO — территория обслуживания и происхождения, отдельная схема/источник; КАТО не часть HCC.
- LNG — ru/kk/en/zh переводы, alias и язык оказания услуги.
- REL — утверждённые связи между категориями/каноническими сущностями.
- REG — регулируемость/подтверждаемые заявления и источники, без хранения чувствительных документов в публичных DTO.
- ML — отдельная модельно-специфичная frozen schema, не вторая таксономия.

### 5.2 Правила AttributeDefinition / CategoryAttributeRule
Развить существующие ENUM/DECIMAL/BOOLEAN/TEXT без дублирования таблиц характеристик под каждую категорию. DATE, RANGE, REFERENCE, MULTIVALUE — только после проверки требований, backward compatibility и миграции. Правила: applicable/required/filterable/searchable, value bounds, canonical unit, allowed vocabulary, inheritance, fact scope (PRODUCT / SKU / LOT / LISTING / SERVICE_SPEC / SERVICE_OFFER), dictionaryRevision и schemaRevision. HARD/SOFT сравнение определяется запросом и бизнес-политикой, не одним глобальным флагом.

Значения UNKNOWN, MISSING, NOT_APPLICABLE и фактический 0 / false не эквивалентны. ENUM сохраняет стабильный code и ссылку на утверждённое значение, не произвольный ввод. Стандартизировать нормализацию десятичных значений BigDecimal, не использовать float для цен и количества.

### 5.3 Локализация
RU/KK/EN/ZH — одинаковые canonical IDs, локализованные title, description, search synonyms, transliteration и предметные alias. «Болгарка» ↔ УШМ — утверждённый SYNONYM, но «плитка» ↔ «укладка плитки» — REL/SERVICE_INTENT, а не равенство. Омонимы (например «масло» пищевое/автомобильное) требуют intent disambiguation и category context. Не создавать неутверждённые словарные значения из пользовательских запросов.

## 6. Catalog Relations: связь товаров и услуг

Отношения, не эквивалентность:
- RELATED_TO — связанные в предметной области;
- INSTALLED_BY — розетка → установка розетки;
- USED_IN — плиточный клей → укладка плитки;
- REPAIRED_BY — компьютер → ремонт компьютера;
- CONSUMABLE_FOR — расходник → модель устройства/процесс;
- SUBSTITUTE_FOR — только после совместимости + buyer opt-in, не автоакцепт.

Каждая relation содержит sourceId, targetId, type, direction, ruleRevision, source/provenance, approvalStatus, validFrom/To, признаки верификации. Cycle checks — по типу отношения; не все графовые отношения обязаны быть ациклическими. ML может использовать REL как сигнал candidate recall / ranking, но не как доказательство идентичности.

Примеры: свежие яблоки ↔ доставка продуктов; кондиционер → установка кондиционера; семена → услуги посева; розетка → монтаж. В интерфейсе различать «соответствует запросу» и «может пригодиться дополнительно».

## 7. Поисковый контур

Сохранить PostgreSQL native Search (FTS, pg_trgm, HCC tree, SearchTerm, cursor HMAC) как P0. Расширить без обязательного Elasticsearch:
1. normalize(language, Unicode, curated terms) → intent (PHYSICAL/SERVICE/DIGITAL) → category candidates → typed facets → live PUBLIC/active/QR/owner rules.
2. Typed filters: сорт, фасовка, масса, срок/температура, регион, condition, brand/variant, наличие подтверждённой цены, units; не принимать динамические SQL columns, только allowlist.
3. Exact canonical / must-have > approved synonym > correct class > typo > related content **отдельной маркированной группой**.
4. Product price per unit, possible delivery charge and fresh goods constraints — не утверждать «самый дешёвый» без корректно подтверждённой общей цены.
5. Не смешивать STATIC classification revision, SEARCH dictionary revision, LISTING publication revision; cursor несёт подписанные query/filter/revision и корректно инвалидируется.
6. Материализованная SearchDocument и отдельный индекс допустимы только после PostgreSQL EXPLAIN ANALYZE/нагрузочного сравнения; при появлении projection — надёжный change capture, backfill, lag monitoring, публичная проверка актуального источника истины.
7. Photo/visual search — только пометка «похоже» и подтверждение категории пользователем; никакой автоматической продажи или сертификации по фото.

## 8. ML-контур и стабильность признаков

**Один canonical dictionary, независимые версии feature schema по задачам**: classification/normalization; deterministic eligibility; seller-response ranking; buyer offer selection; all-in deal ranking; category demand radar; contextual product-service bundle. Сохранять существующие FeatureRegistry, FeatureVectorEncoder, CanonicalFeatureAdapter, ML-R2 trade event journal.

Для каждого snapshot/run хранить: catalogRevision, attributeSchemaRevision, vocabularyRevision, searchDictionaryRevision (при использовании поиска), featureSchemaVersion, encoderVersion, modelVersion, asOf, source event time, schema hash. Точный состав версии в DTO/таблицах — S2 contract. Никаких категорий как ordinal continuous HCC/BIGINT; использовать namespaced one-hot/hashed categorical при валидации словаря, numeric с canonical unit, missing indicators.

Кандидат выбирается сервером: eligibility (owner/active/visibility/stock-or-service-capacity/geo/unit/must-haves/consent where applicable) → feature projection → ML score только eligible → explainable ordering. **REJECT** model schema mismatch и back to deterministic. Не использовать outcome после момента решения в features; не считать отсутствие показа отрицательным label; обучение только на lawful/consented данных, с retention/deletion/replay.

Базовые измеримые ML KPI: Precision@K/Recall@K/nDCG@K для поиска/matching; calibration/PR-AUC для response; категории без достаточного числа labels — rule-based fallback. Количественные пороги устанавливать после baseline и независимого offline validation, не выдумывать точность.

## 9. Внешние классификаторы: ссылки, не источники внутренних ID

- **UN CPC** — общий мировой охват товаров и услуг; использовать как reference crosswalk с edition и многозначными соответствиями.
- **UNSPSC** — goods/services в цепочках поставок и закупок.
- **GS1 GPC** — отраслевая структура товарных классов, особенно продуктовые группы/пищевые товары; GTIN идентифицирует trade item, не категорию.
- **ТН ВЭД ЕАЭС** — таможенная классификация **товаров**, не универсальное дерево услуг; код требует правильного правового контекста и актуальной редакции.
- **КАТО Казахстана** — географические административные идентификаторы, не категории товаров/услуг.
- **ОКЭД** — классификация видов деятельности предприятий, не автоматический код конкретного товара.

ExternalClassificationMap: internalSubjectId, scheme, externalCode, schemeEdition, relation EXACT/BROADER/NARROWER/RELATED, reviewedBy, evidence, effective dates, license/source. Импорт внешних данных только после проверки прав использования, версий и юридических ограничений. Запрещено молча подменять внутреннюю таксономию импортируемой редакцией.

Официальные справочные ссылки:
- https://unstats.un.org/unsd/classifications/Econ/CPC
- https://www.undp.org/unspsc
- https://ref.gs1.org/standards/gpc/
- https://www.gov.kz/org/kgd/documents/details/28675?lang=en

## 10. Предлагаемые изменения данных и границы миграций

**Не список немедленно создаваемых таблиц.** На CAT-R1 утвердить минимальный relational schema diff для существующих Category/AttributeDefinition/Rule/Product/Listing/Sku/SearchTerm/FeatureRegistry:
- category_translation, attribute_translation/enum_translation — если не покрываются существующими полями;
- catalog_revision + catalog_change_audit; ограниченный lifecycle/status;
- catalog_relation с authoritative FK/approval/version;
- category_schema_revision + применимость/наследование атрибутов;
- external_classification_map;
- catalog_import_job + staging/proposal/rejection/audit;
- service_spec/offer_terms только при утверждении service order semantics;
- inventory_lot только после отдельного inventory allocation/expiry ADR.

Каждое новое поле имеет owner, FK/UNIQUE/CHECK/INDEX, referential deletion policy и compatibility mode. Новые Flyway версии брать **от HEAD на момент исполнения** (не выбирать V28/V29 по памяти). Исторические V1–V27 не переписывать. Никакого auto ddl-update в release, не изменять опубликованные SKU/заказанные line snapshots. Скрипты seed — versioned content data, не random auto-generated HCC.

Исключить «универсальную таблицу всех объектов» с polymorphic foreign key без гарантии целостности: типизированные таблицы/owner enforcement предпочтительнее.

## 11. Административный процесс расширения: Taxonomy Governance

Роли (целевые, пока не объявлять реализованными):
- PRODUCT_OWNER — утверждает охват и приоритет новых доменов.
- TAXONOMY_EDITOR — готовит category / attributes / translations / relations и источники.
- TAXONOMY_REVIEWER — независимая проверка семантики, языков, кросс-классификации, конфликтов.
- COMPLIANCE_REVIEWER — отдельно утверждает restricted/regulated cases.
- RELEASE_OPERATOR — выкладывает утверждённую ревизию с audit/backfill.
- S2_REVIEWER — оценивает SQL, API, security, ML impact и миграции.

Workflow: PROPOSED → VALIDATED → APPROVED → STAGED → PUBLISHED → DEPRECATED → RETIRED. Переходы по явной авторизации; описание, reason, version, source checksum, author, reviewer, timestamp, diff. Для существующих PUBLIC объектов «DEPRECATED» не означает удаление исторической идентичности; пересвязка по versioned migration/alias и с анализом всех зависимостей.

Batch import: UTF-8 CSV/JSON с manifest (source, locale, schemaVersion, proposed revision, checksum) → read-only dry-run/diff/conflict report → reject ambiguous/bad units/duplicate synonym/wrong parent/missing translation/license → review → транзакционный approved import + revision bump → search reindex/invalidation → validation queries → release gates. Импорт повторяем/idempotent; rollback через отдельную компенсирующую ревизию, не удаление исторических заказов.

Публичные Seller/Buyer/LLM предложения о новой категории уходят в moderated proposal; никакой прямой записи в ACTIVE dictionary.

## 12. REST и интерфейсы (CANDIDATE, не активные endpoints)

Сначала аудит существующих routes и OpenAPI; не ломать /api/v1/public/catalog, /api/v1/public/catalog/search, /api/v1/public/search/suggest, /q/{token}:
- GET /api/v1/public/taxonomy/tree?domain=&locale=&revision=
- GET /api/v1/public/taxonomy/categories/{id}/schema?revision=
- GET /api/v1/public/catalog/search?q=&type=&categoryCode=&...typedFilters&cursor=
- GET /api/v1/public/taxonomy/relations?subjectId=&relationType=
- POST /api/v1/admin/taxonomy/imports/validate — admin; dry-run, никакой публикации.
- POST /api/v1/admin/taxonomy/revisions/{id}/approve — privileged; two-person review.
- POST /api/v1/admin/taxonomy/revisions/{id}/publish — audited, feature flag OFF until verified.
- POST /api/v1/sellers/{sellerId}/classification-proposals — authenticated, moderated.
- Service-specific demand/booking routes определять только после отдельного service lifecycle ADR.

React/Flutter:
- дерево и breadcrumb, productType, multi-locale, фасеты по authoritative category schema;
- Seller Studio: выбор класса через поиск/дерево, не ручной UUID; seller может предложить недостающую категорию;
- карточка продукта отличается от seller listing; QR сохраняет привязку к конкретному продавцу;
- товар, услуга и цифровой продукт получают разные формы (qty/lot vs scope/time vs license);
- explainable «связанные услуги» отдельно от основного соответствия; отображать неопределённость цены/срока/сертификатов.
- mobile и web используют одни серверные contract tests и не содержат собственных копий taxonomy truth.

Security: sellerId из auth membership; admin RBAC scopes, rate limit и audit; denyAll по умолчанию и адресное разрешение новых URL; PII/частные документы не в публичном поиске и ML.

## 13. Первые утверждаемые вертикали и объём

Приоритет **не весь мировой справочник сразу**, а проверяемое расширение четырёх разнородных доменов:

| Pilot | Representative cases | Проверяемая сложность |
|---|---|---|
| A — овощи/фрукты | яблоки (сорт, кг, ящик), картофель (кг), помидоры (кг), сезонность | typed attributes, units, lots, expiry |
| B — продовольствие | молоко (л/пакет), хлеб (шт/масса), мёд (кг/банка), мука (кг/мешок) | composition, packaging, seller claims |
| C — товары + услуги | розетки, кабель, кондиционер; установка и ремонт | relations, service vs product, quote and coverage |
| D — электроника/быт | смартфон с памятью/цветом, бытовая техника | canonical product, SKU variants, hard matching |

Стартовые объёмы — **целевые ограничители P0, не уже созданные данные**: 100–200 category nodes после schema review; 150–300 утверждённых типизированных атрибутов/значений (не обязательно атрибутов на класс); 500–1 000 RU/KK/EN/ZH терминов с human review; 30–50 предметных relationships; 100+ golden query cases. Финальные числа подтверждаются после учета редакторской нагрузки и существующих каталогов. Допускать неполный охват отрасли, но не недоказанные auto-generated HCC labels.

## 14. Roadmap CAT-R0…CAT-R7 с зависимостями

| Stage | Artifact and scope | Gate / definition of done |
|---|---|---|
| CAT-R0 | HEAD audit, existing contract inventory, конфликтная матрица Master/DEC/SEARCH/ML, ADR список | нет новых сущностей; S2 одобрил non-breaking strategy |
| CAT-R1 | Taxonomy design: HCC capacity + category revision + attribute scopes, sample data 4 pilots | PostgreSQL RED tests + stable UUID/HCC, no duplicate hierarchy |
| CAT-R2 | PostgreSQL migrations + typed dictionary + revisions + translations + review imports | Testcontainers: FK/UNIQUE/CHECK, upgrade/rollback, audit/permissions |
| CAT-R3 | Operator import + admin moderation Studio + approved seeds | dry-run, conflicts, 4 locales, idempotency, no silent PUBLISH |
| CAT-R4 | Agriculture/food typed SKU + units/packaging + lot contract (only when approved) | deterministic price/unit, food safety provenance, stock/race tests |
| CAT-R5 | Service spec, availability/scope and buyer demand/seller bid pilot | service end-to-end no fake inventory; React/Flutter/HTTP acceptance |
| CAT-R6 | Taxonomy relations + Search typed facets + consumer UX | golden queries, no false equivalence, visibility/security, p95 profiling |
| CAT-R7 | Versioned ML feature projection + datasets + offline evaluation and gate | schema-consistent train/infer, no leakage, fallback, consent, S2 |

No independent branches. Each slice in docs/adr-002-jpa-architecture-review with PR #2 Draft, reviewer may request separate commits but not branch proliferation. main unchanged until an explicitly authorized merge.

## 15. Обязательные исполняемые тесты и acceptance matrix

Статусы доказательств раздельно: CODE_PRESENT → UNIT_VERIFIED → DB_VERIFIED → API_VERIFIED → USER_E2E_VERIFIED → PERF_VERIFIED → S2_APPROVED → RELEASE_AUTHORIZED. CAT-001 creation is DOCUMENTED only.

- CAT-T01: 20-digit HCC retains leading zero/depth, child parent/type, no cycle; no duplicate code, root and capacity bounds tested.
- CAT-T02: category/attribute version changes do not change historical demand/bid/order snapshots or classifier replay.
- CAT-T03: «яблоки» RU/KK/EN/ZH resolve to one approved class; «яблочный сок» does NOT match fresh apples as identical; tests ambiguity.
- CAT-T04: «масло» resolves disambiguation (food vs car oil); category/intent fallback, no unconditional cross-vertical hits.
- CAT-T05: apples KG vs BOX cannot be compared without verified package net mass; missing vs 0 distinct; exact KG conversions BigDecimal.
- CAT-T06: seller cannot spoof organic quality, origin, certificate or batch verification; proof must be checked server-side.
- CAT-T07: expired/unknown provenance lots are rejected when restricted by offer/request policy; physical stock remains authoritative, no unreviewed lot allocation.
- CAT-T08: «установка кондиционера» resolves SERVICE, not physical кондиционер; related product displayed separately.
- CAT-T09: service bid materials included vs excluded is never treated as exact equal unless explicit acceptance.
- CAT-T10: DRAFT/UNLISTED/ARCHIVED do not leak to public search/media; PUBLIC only with active Category and valid QR.
- CAT-T11: typed facets + HCC ancestors, cursor stable/rejected after revision; SQL injection and giant/unbounded queries rejected.
- CAT-T12: Seller A cannot edit Seller B catalogue; admin import requires RBAC/multi-person approval; audit and no unapproved ACTIVE writes.
- CAT-T13: repeat import is idempotent; invalid row/ambiguous translation fails with no partially published revision; restore/replay reproducible.
- CAT-T14: model-specific FeatureRegistry strict schema; unknown/unsupported vocab fallback; no ordinal numeric HCC; offline/inference parity.
- CAT-T15: future event/outcome not included in features at asOf; buyer consent revocation honored at projection, training and delivery.
- CAT-T16: Search ML candidate cannot override deterministic seller eligibility, area, stock, unit, publication, expiry, safety.
- CAT-T17: React web + Flutter perform real publish → catalog/search → buyer demand → seller bid → explicit acceptance for physical goods.
- CAT-T18: standalone service request/bid → documented completion path passes actual UI/API + DB, but only after Service ADR.
- CAT-T19: PostgreSQL search EXPLAIN ANALYZE and load on 10k/100k/1m offers: p50/p95/p99, RPS, errors, CPU/memory, index size; explicit threshold after baseline.
- CAT-T20: no regression of QR URL, existing SKU publication, order snapshots, inventory/outbox, keyset cursor and ML event delivery.
- CAT-T21: external mapping edition changes do not rewrite internal category IDs; ambiguous crosswalk cannot silently become EXACT.
- CAT-T22: restricted categories remain disabled until compliance approval; valid retail listings unaffected.

Testing approach: first true RED unit/PostgreSQL/Testcontainers + HTTP security & integration, then minimal GREEN, golden data fixtures, independent S2, genuine browser/device E2E and performance. Test data contains no real PII or fabricated certified status.

## 16. Operational SLO and качество данных

SLO proposals (not release promises): search p95 ≤ 400 ms and suggestion p95 ≤ 150 ms under **declared staging HW, test fixture, concurrent profile and query mix**; baseline and index stats measured before final thresholds. No claim 5k-VU commerce test proves 5k concurrent catalog search. Track search zero-result rate, false match rate, approved category coverage, completeness of required facts, ambiguous aliases, revision publish lag, ML schema mismatches, eligible match false positives, catalog import rejections and stale projections.

Safety gates:
- single source-of-truth correctness over search latency;
- freeze/revert revision on unexpected false matches or PII/visibility leakage;
- preserve historical feature schema/models;
- auditable backfill, migration rehearsal, backup/restore and controlled activation via OFF-by-default feature flags.

## 17. Открытые ADR и решения для утверждения

1. **CAT-DEC-01:** UUID Category/Attribute как действующая persistence identity vs отдельный BIGINT DictionaryNode из conceptual UCE. Не менять ID в CAT-R0.
2. **CAT-DEC-02:** HCC capacity/5th segment semantics, category move/alias/version rules; не генерировать тысячи кодов до proof.
3. **CAT-DEC-03:** authoritative attr fact scopes PRODUCT/SKU/LOT/LISTING/SERVICE; inheritance and rule revisions.
4. **CAT-DEC-04:** упаковка vs selling unit, variable-weight checkout, actual weight and immutable OrderLine consent.
5. **CAT-DEC-05:** lot/expiry/quality evidence/verified origin and stock allocation.
6. **CAT-DEC-06:** canonical service specification and exact ServiceOrder lifecycle/reuse of CustomerOrder and SellerBid.
7. **CAT-DEC-07:** prohibited/restricted categories and policy for regulated product advertising/sales in KZ.
8. **CAT-DEC-08:** semantics/licensing/version of GS1, UNSPSC, CPC, ТН ВЭД mapping.
9. **CAT-DEC-09:** typed filter storage, index strategy, performance threshold and potential SearchDocument.
10. **CAT-DEC-10:** canonicalization policy, multi-translation review and ambiguous aliases.
11. **CAT-DEC-11:** versioned feature schemas, label horizons, consent boundaries and reprocessing.
12. **CAT-DEC-12:** where/how future cross-seller/mixed goods+services basket may operate; MVP one order/one seller invariant remains.

Каждое решение — explicit owner, review status, alternatives, migration impact, test oracle, rollback/feature flag и approved revision. Отсутствие решения = HOLD для затрагиваемого production изменения.

## 18. Ближайший исполнимый этап: CAT-R0

1. Зафиксировать SHA рабочей ветки, перечень entity/repositories/controllers/Flyway, флаги и реально пройденные тесты.
2. Подготовить compatibility matrix «существующая сущность → расширение → DB/API/UI/Search/ML/Commerce → migration/tests».
3. Подготовить подробные примеры четырёх пилотов с утверждаемой категорией, canonical entity, typed schema, unit, seller offer, search terms, feature projection, hard constraints и опасными ложными совпадениями.
4. Разобрать CAT-DEC-01…12 и получить техническое решение S2 без обязательного создания всех предложенных сущностей.
5. Написать RED-тесты CAT-T01…T06 для PostgreSQL и search семантики — только после утверждения модели данных.
6. Реализовать минимальный вертикальный срез фруктов/овощей и продуктов питания, затем доказать совместимость service/physical и начать расширение.

**Запрещено на CAT-R0:** массовый импорт случайных внешних категорий, создание второй HCC-иерархии, изменение действующих публичных SKU/OrderLine/ML snapshots, автоматическое включение новых endpoints и merge в main.

## 19. Definition of Done этого ТЗ и критерий перехода в разработку

ТЗ считается подготовленным, когда документ хранится в единственной ветке и является источником CAT-R0 review. **Стратегия считается согласованной**, только когда PRODUCT_OWNER утвердил приоритеты и модель продуктов/услуг, S2 одобрил schema/identity/compatibility decisions, Security/QA зафиксировали RED-oracles, а compliance-review завершён для регулируемых доменов. До этого статус DRAFT.

Функционал считается реализованным только после миграций и реальных тестов по evidence matrix; статус PROPOSED не является IMPLEMENTED или VERIFIED.

---
**Change log**
- 2026-11-14, v0.1: создано отдельное мастер-ТЗ для согласованного, расширяемого каталога агро-/продовольственных товаров, потребительских товаров, услуг и DIGITAL; сохранены HCC/Search/ML/Commerce инварианты, определены CAT-R0…R7, 22 acceptance-теста и 12 открытых решений.
