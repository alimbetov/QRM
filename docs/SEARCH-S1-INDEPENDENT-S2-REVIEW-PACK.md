# SEARCH-S1 — пакет для независимого S2 Review
Дата: 2026-11-14. Базовый SHA: `6ca25812c619e1a108c880a43bb7d22ad0d7e229`.
Статус: **REVIEW REQUESTED / NOT APPROVED**. Автор этой проверки не является независимым ревьюером. Независимый человек/агент должен проверить и дать GitHub APPROVE на конкретный SHA после исправления блокеров.

## Проверенное
- Java 21, Spring Boot 3.5.16, PostgreSQL 17, Flyway V1–V21, `PublicSearchReadService`, `SearchCursorCodec`, `ApiSecurityConfig`, `SearchDictionaryPostgresIT`.
- Ранее GitHub Actions: `SearchDictionaryPostgresIT` 6/6 PASS; все четыре workflow PASS на `3f4c08e`.
- `docs/SEARCH-001-IMPLEMENTATION-CONTRACT.md` и SEARCH-001-UNIFIED-DISCOVERY.md — целевые требования; наличие CI PASS не заменяет S2.

## Обязательные замечания для независимой оценки
| ID | Серьёзность | Наблюдение | Требуемая проверка |
| --- | --- | --- | --- |
| S2-S1-01 | P0 семантика | `SYNONYM` ссылается на категорию и рекурсивно расширяется на всех потомков. Тест «болгарка» привязан к родителю «Инструменты»: в реальном справочнике это может вернуть шуруповёрты и дрели как болгарки | При импорте требовать утверждённый узкий HCC concept (или чётко маркированный CATEGORY_TERM), reject broad synonym для конкретного SKU/предмета |
| S2-S1-02 | P0 данные | Нет production dictionary seed, нет проверенного полного дерева HCC; GitHub test fixtures искусственные и не эквивалент реальной таксономии | Owner review реальные category IDs/HCC/type/термины; публикация только после signature/checksum/approval |
| S2-S1-03 | P0 безопасность | Public suggest может возвращать слова из активной категории, где нет PUBLIC Listing; security allowlist GET добавлен, но отдельные denied-method, feature-OFF, IDOR и SSRF/non-UTF fuzz не проверены | Dedicated security MockMvc/PostgreSQL tests, max response/length, default-OFF |
| S2-S1-04 | P1 SQL | Рекурсивный CTE, `LIKE '%term%'`, FTS, trigram OR-критерии могут вести к полному сканированию; timeout=5s не доказательство p95 | EXPLAIN (ANALYZE, BUFFERS) на 10k/100k/1M, short-term, all-script, high-cardinality, 5k concurrent separate qualification |
| S2-S1-05 | P1 нормализация | `normalized_term` в DB проверяется только длиной; admin SQL может записать смешанный регистр/NFD и сделать alias недоступным; нет согласованной tokenization kk/zh | Unicode NFC/casefold и QA при импорте; повторяемые fixtures RU/KK/EN/ZH |
| S2-S1-06 | P1 cursor | Глобальная ревизия словаря инвалидирует все курсоры; публикации/скрытия Listings не «замораживаются» в снимок страницы | Проверить контракт concurrent visibility и stability; поведение при 409/400, secret rotation/multi-pod shared key |
| S2-S1-07 | P1 API | Вход `q` обязателен; просмотр нулевого запроса остаётся в старом каталоге. ProductType и HCC validation есть; UX-переход на новый API ещё отсутствует | Обратная совместимость GET /catalog, Flutter, единое scroll state |
| S2-S1-08 | P1 resilience | pg_trgm расширение ставится Flyway; права создания extension и миграция production DB отдельно не проверены | Staging migration with least-privileged schema-owner; release rollback |
| S2-S1-09 | P1 relevance | Нет независимого набора размеченных поисковых запросов (exact/related/false positive), reranking не имеет контрольной метрики | Golden queries ≥40, Success@10, no RELATED-as-SYNONYM; false positives annotated |

## RED acceptance для независимого ревью
1. Словарь: «болгарка» попадает строго в утверждённую категорию УШМ, не в общий электроинструмент; «ремонт болгарки» — отдельная SERVICE_INTENT.
2. Вся публичная выдача повторно фильтрует PUBLIC+active HCC+QR на SQL, включая pagination/suggestions.
3. Все GET fail closed при `zakaz.search.http.enabled=false` (ожидаемый 404 при отсутствии контроллера, не 200); POST/DELETE GET paths — deny.
4. HCC 20 цифр/2-2-3-5-8, товары/услуги не смешиваются, рус/каз/англ/кит примеры согласованы с ответственным редактором.
5. Нет утечки Seller private данных, нет динамического SQL из `q`, version mismatch → 409, подписанный tampered cursor → 400.
6. Не допускается merge в `main` до независимого review exact SHA, staging OIDC/SMTP & browser/device E2E и отдельных 5k gates.

## Результат этой работы
Это **self-review + checklist**, не независимое утверждение. PR #2 остаётся Draft. Для решения S2 попросить независимого ревьюера проверить приведённые инварианты и оформить APPROVE/REQUEST_CHANGES в GitHub. Не назначать ревьюером автора изменений.
