# CAT-R1A — Implementation evidence, compatibility matrix and independent S2 review pack

**Дата:** 2026-11-14
**Статус:** IMPLEMENTATION CANDIDATE / INDEPENDENT S2 NOT APPROVED.
**Репозиторий:** alimbetov/QRM. **Ветка:** docs/adr-002-jpa-architecture-review; PR #2 Draft; main untouched.
**Основания:** [CAT-001](CAT-001-UNIVERSAL-CATALOG-EXPANSION-MASTER-SPEC.md), [CAT-R0 audit](CAT-R0-CONSISTENCY-AUDIT-2026-11-14.md), [pilot contract](CAT-R0-PILOT-ATTRIBUTE-CONTRACT.md).

## 1. Точный состав реализации

- \`backend/src/test/java/kz/zakaz/catalog/CatalogR1ASchemaPostgresIT.java\`: пять PostgreSQL 17/Testcontainers тестов, введённых в RED-коммите \`448d6bda3fd400ef2a2bdd19b4f7e6f274e797f1\`.
- \`backend/src/test/java/kz/zakaz/catalog/CatalogR1AAttributePolicyTest.java\`: четыре Java unit теста на десятичные bounds, ISO dates, version pinning.
- \`backend/src/main/resources/db/migration/V28__catalog_r1a_typed_attribute_revisions_and_hcc.sql\`: только добавочные schema изменения; никакие старые Flyway V1–V27 не переписаны. Параллельная ML-миграция существует отдельно как V29, нумерация должна проверяться на каждом execution HEAD.
- \`backend/src/main/java/kz/zakaz/catalog/AttributeDefinition.java\`: введены decimal min/max, \`schemaRevision\`, \`factScope\`, тип DATE, фабрика bounded DECIMAL. По умолчанию legacy DECIMAL min=0.
- \`backend/src/main/java/kz/zakaz/catalog/SkuAttributeValue.java\`: \`definitionRevision\` pinned при создании и замене draft-факта.
- \`backend/src/main/java/kz/zakaz/catalog/CatalogPublicationPolicy.java\`: canonical syntax check pinned historic facts без повторного применения нынешних numeric bounds.
- PostgreSQL direct SQL constraints/triggers: append-only history, at-write verification SKU facts, HCC immediate parent + immutable identity, explicit UOM dimension registry.

**Изменения намеренно НЕ включают:** new ServiceOrder, InventoryLot, автоматические упаковки BOX↔KG, массовый import HCC, nullable mutable repricing, UI/REST для администрирования политик, новая production ML модель.

## 2. RED evidence

Целевой исходный коммит: \`448d6bda3fd400ef2a2bdd19b4f7e6f274e797f1\`.
Workflow Java Backend \`43561342501\`: fast succeeded; PostgreSQL failed. Прямое подтверждение отсутствующих схем:
- \`CatalogR1ASchemaPostgresIT\`: \`column "min_decimal" ... does not exist\`;
- \`CatalogR1ASchemaPostgresIT\`: \`relation "catalog_unit" does not exist\`;
- HCC parent check не защищён в старой DB-структуре.

**ВНИМАНИЕ:** на том же SHA параллельно упали некоторые существующие SellerInvitation / ML PostgreSQL тесты по ограничениям приглашений. RED не является чистым единственным падением только CAT-R1A. Нельзя утверждать, что все ранее существовавшие PostgreSQL тесты были GREEN до добавления CAT-R1A; regression baseline требует нового clean verification.

## 3. GREEN evidence

**PENDING:** здесь должен быть указан exact tested SHA, GitHub Workflow URL, postgres job, число тестов, failures/errors, JAVA fast, Flyway V28/V29, Web/Flutter/Staging workflows. До подтверждения здесь не писать PASS.

## 4. DB migration contract / safety

| Object | DDL change | Risk + compatibility |
|---|---|---|
| \`catalog_attribute_definition\` | min_decimal, max_decimal, schema_revision default1, fact_scope SKU | существующие DECIMAL получают min=0: старый запрет отрицательных сохраняется |
| \`catalog_attribute_schema_revision\` | immutable \`(attribute_id,revision)\` + snapshot validation policy | исторические ревизии не UPDATE/DELETE; change to bounds requires revision+1 |
| \`catalog_sku_attribute_value\` | \`definition_revision\` default1, composite FK, direct SQL write validation | старые значения сохраняются с версией1, каждый новый write сверяет текущую |
| \`catalog_category\` | trigger validates supplied parent type/prefix/immediate depth, forbids identity updates | legacy rows with NULL parent remain untouched; historical HCC immutable |
| \`catalog_unit\` | immutable dimension, factor_to_base, explicit current enum seed | только единицы существующего MeasurementUnit; не автоматическая замена checkout |

No changes in schema of \`customer_order\`, \`order_line\`, \`buyer_demand_line_snapshot\`, \`seller_bid_line_snapshot\`, \`inventory_balance\`, \`inventory_reservation\`, \`order_outbox_event\`, \`ml_trade_event\`.

For new/existing Postgres deployment: Flyway validate, controlled backup/restore rehearsal, data preflight on attribute kinds, canonical values, HCC parent and negative values, rollback strategy through forward-only compensating migration (not history deletion). New columns live on existing tables; use explicit explain lock/size impact for production-sized data. No user-facing feature flag needed for additive schema, but new write contracts/admin endpoints stay disabled.

## 5. S2 independent reviewer requirements

The reviewer **must not** be the implementation author. Request formal GitHub APPROVE referencing exact SHA and test evidence. Checklist:

1. Flyway V28 version uniqueness and ordering alongside ML V29; no changes to old Flyway checksums.
2. Root HCC semantics: intentionally permits NULL parent for legacy deep codes; is this permitted for future strict taxonomy? Test cycle/type/prefix and immutable recoding; verify concurrency and hierarchy enforcement.
3. Attribute revision trigger: append-only integrity; revision monotonicity, no UPDATE/DELETE; DB role permissions and who may change policy. Current \`reviseDecimalBounds\` is package-private, no admin HTTP.
4. Signed numeric rules: min=-40/max=30 permits -18 for temperature, no negative for default STORAGE_GB; BigDecimal normalization and PG regex/scale agree, Unicode/Infinity/NaN/out-of-range fail-closed.
5. DATE: strict ISO parsing, invalid leap dates, no locale dependence, DB and Java parity.
6. fact_scope: only SKU mutable facts are available; PRODUCT/LOT/SERVICE_* is **not** implemented via current catalog_sku_attribute_value. Prevent user from treating schema metadata as commercial fulfilment support.
7. Historic facts after new policy: existing SKU row pinned version1; no reassignment on policy change. \`CatalogPublicationPolicy\` checks canonical syntax but does not have full point-in-time attribute-rule/enum/Category snapshots; **full historical ML replay still NOT guaranteed**. Need CAT-R1B scoped revision and model snapshots.
8. PostgreSQL trigger safety: row locks, transactionality, recursive HCC parent vs root; performance and potential deadlocks on bulk write.
9. SQL direct writes: bypass through raw SQL blocked by triggers/FK; verify non-superuser DB roles have no permission to disable triggers or mutate history.
10. UOM: dimensions and factors correct; M3→L = 1000, G→KG=.001, do not compare mass to volume or assume BOX/PKG quantity; no implicit checkout conversion.
11. Test safety: CAT-R1A RED proves absent contracts; GREEN must include complete prior suite, no unrelated ML/Seller regression, no non-deterministic test fixtures.
12. Security/release: don't enable admin write/API/import/ML training without explicit S2 and product-owner approval.

## 6. Open S2 findings and limits (HOLD for full CAT-001 rollout)

- **RISK-01 / P0:** CategoryAttributeRule \`required/filterable\` not historically versioned. Changing it can requalify/invalid historic SKU and matching behavior. Need follow-up table/revision before production ML replay.
- **RISK-02 / P0:** \`Category.label\` lacks translations/revision history; HCC validator protects structural identity, not full taxonomy lifecycle.
- **RISK-03 / P0:** Actual cross-vertical LOT/expiry/cold-chain is absent: \`DATE\` type support ≠ expiry enforcement.
- **RISK-04 / P0:** Service-specific typed scope/availability and SellerDemand order flow absent; don't misrepresent a service as SKU or inventory.
- **RISK-05 / P1:** Direct SQL \`catalog_sku_attribute_value\` validation new writes only; legacy data needs preflight before migration and independent integrity audit.
- **RISK-06 / P1:** Search/ML do not yet attach full catalog/schema revision to every training/replay sample; no production model released.
- **RISK-07 / P1:** UOM comparison registry is informational; checkout exact unit matching remains, price normalization must be proven separately.
- **RISK-08 / P1:** The signed-bounds Java/DML pathway is safe for SKU only. Changing policy should be authorized, audited at actor level and exposed via future controlled admin workflow, not generic JPA save.
- **RISK-09 / P1:** Existing ML invitation test failures in initial RED baseline require independent closure; don't automatically attribute them to CAT-R1A.

## 7. Release gate

Until (a) actual GREEN complete suite, (b) independent S2 formal APPROVE, (c) migration rehearsal on PostgreSQL with historical data, and (d) product-owner acceptance, the only warranted status is **CANDIDATE / HOLD**. PR #2 stays Draft; no merge to main. Next slice is CAT-R1B — category attribute rule revisions + four-pilot typed fixtures and real relational tests, then Search facets and ML snapshots after S2.
