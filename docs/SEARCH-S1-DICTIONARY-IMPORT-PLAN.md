# SEARCH-S1 — подготовка словаря и правила загрузки

**Статус:** CANDIDATE DATA ONLY, НЕ подтверждённый справочник и НЕ применён к PostgreSQL.

Источник: [кандидаты словаря](SEARCH-S1-DICTIONARY-CANDIDATES.csv). Приведены 5 концептов (УШМ, дрель, диван, плитка и ремонт сантехники) и примеры ru/kk/en/zh. Переводы предварительные, проходят языковое и товарно-предметное утверждение.

## Почему CSV пока не является seed-миграцией
В `catalog_category` при запуске ZAKAZ **нет** гарантированно присвоенных кодов HCC этим концептам. Коммитить тестовые `0102...` как официальные категории или автоматически создавать новые категории — ошибка. Колонка `approved_hcc_code` намеренно пустая; `approval_status=CANDIDATE`.

## Порядок утверждения и импорта
1. Ответственный за каталог утверждает существующий `catalog_category.hcc_code`, `product_type`, активность, parent/depth для каждого `concept_key`. Для конкретного предмета (болгарка) выбирать узкую категорию УШМ, не широкую родительскую «Инструменты».
2. Проверить человеко-понятность/переводы RU/KK/EN/ZH и правильное различение синонима и связанного товара; не смешивать PHYSICAL/SERVICE.
3. В staging подготовить отдельный CSV только со строками `approval_status=APPROVED` и 20-значным `approved_hcc_code`. Должны быть уникальны нормализованные `(locale,term)`; если слово многозначно — отдельное продуктово утверждённое решение, не silent overwrite.
4. Проверить SQL: `SELECT hcc_code,product_type,active FROM catalog_category WHERE hcc_code IN (...)`; любое отсутствие, inactive, неверный type или broad alias — fail.
5. В транзакции записать `catalog_search_term`, `catalog_term_category` со ссылкой на найденные `category.id`; versioned checksum и audit log. `catalog_search_dictionary_revision` меняется триггерами.
6. Запустить на настоящем PostgreSQL golden-query пакет и проверить отсутствие false positives; только затем staging preview и production rollout после S2.

**Запрещено:** запускать CSV напрямую через Flyway; писать alias со статусом ACTIVE без соответствующей категории и approve; использовать пользовательские вводы как новый словарь без модерации; рассматривать перевод автоматически как точную совместимость SKU.

## Открытые данные
Owner taxonomy, реальные HCC значения, полная категория/описание товара, роли редакторов, production словарный corpus, provenance и versioning пока не подтверждены. Поэтому подготовлен **кандидат**, а не production import. Импортёр и модерация — следующий отдельный slice.

## Operator scripts (only after product-owner approval)

These files are present in the working branch; they are **not** migrations and never run automatically.

Use **one psql session** and an independently approved CSV (all rows `APPROVED`, existing 20-digit HCC):

```psql
\i scripts/search/prepare-dictionary-import.sql
\copy search_dictionary_stage FROM '/absolute/path/approved.csv' WITH (FORMAT csv, HEADER true)
\i scripts/search/apply-approved-dictionary.sql
```

The SQL validation rejects missing/inactive categories, mismatched ProductType, duplicate normalized terms and unapproved rows before commit. If it fails, execute `ROLLBACK`, correct and review source data, then repeat the transaction. The candidate CSV with empty HCC codes is intentionally rejected. Before production migration, separately verify Unicode NFC/case-fold consistency with the Java normalizer and category-depth semantics: SQL cannot certify product-language meaning.
