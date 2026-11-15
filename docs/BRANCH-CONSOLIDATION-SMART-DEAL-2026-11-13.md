# Consolidation record — Smart Deal branch → единственная ветка разработки

**Дата:** 2026-11-13. **Target:** docs/adr-002-jpa-architecture-review (Draft PR #2). **Source:** docs/smart-deal-r2. **main:** без изменения.

Источник и целевая ветки diverged. Перед объединением target 5fb07f47d3c793b3bb28e24be83c6e04c9b709fb, source bb3123634fda7edddf69bfb45eb63be41e1f80cc, merge-base 7ac4e986a7e9b52c77a1942eef98710626d0be7c; у source 27 уникальных commits, у target 316. История source должна сохраняться вторым родителем merge commit.

## Разрешение конфликтов

- Не переписывать docs/REQUIREMENTS.md v2.0 старым v1.4 из source; ADR-002 MVC/JPA, Flyway V1–V19, действующий React/Flutter и security checks приоритетны.
- docs/SMART-DEAL-R2.md и docs/SMART-DEAL-R2-AUDIT.md уже имеются в target со специальной JPA интеграционной оговоркой. Не откатывать их к старой редакции.
- Перенести отсутствующий docs/SMART-DEAL-R2-S1-DB.md (внутри него есть ADR-002 correction) и isolated contract test harness с явной пометкой LEGACY. Старые Liquibase/WebFlux/R2DBC подходы НЕ вносить в production.
- Прежние предложения source по BuyerDemand/SellerBid/Outbox остаются историческими design oracles, а не новыми runtime contracts.
- Изменения по поиску фиксировать в docs/SEARCH-001-UNIFIED-DISCOVERY.md. Словарь/галерея/визуальный поиск ещё не реализованы.

## Gates

После объединения: убедиться, что docs/smart-deal-r2 ancestor рабочей ветки; проверить точный HEAD, отсутствие сбоя CI, S2 и внешние UAT/5K gates. Не сливать PR #2 в main автоматически; не удалять источник, пока нет подтверждённого merge и возможности безопасно удалить branch.
