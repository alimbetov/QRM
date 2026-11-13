# Smart Deal SD-R2-S1 — исторический изолированный набор контрактных тестов

> **LEGACY / НЕ production и НЕ критерий готовности актуального JPA Backend.** Перенесён из ветки docs/smart-deal-r2 для сохранения истории и проверяемости источников. Он моделирует старую абстрактную SQL-схему и **не является** Flyway migration для существующего ZAKAZ. Не добавлять его в основной CI, не применять fixture.sql к рабочей PostgreSQL и не считать прохождение этой схемы S2/GREEN существующего BuyerDemand/SellerBid.
>
> Текущий контракт: ADR-002 Java 21 + MVC/JPA + Flyway V1–V19 и PostgreSQL Testcontainers. Применимые тестовые сценарии нужно портировать в backend/src/test против действующей схемы. См. docs/SMART-DEAL-JPA-CONSOLIDATION.md и docs/SMART-DEAL-R2-S1-DB.md.
>
> Команда из исходной ветки (только для исторического исследования изолированной fixture): cd smartdeal-contract-tests && mvn test. Результат этой команды не подтверждает работу коммерческого контура.

## Исходный контекст

Внутри src/test/resources/db/fixture.sql лежит нерабочая для реального Backend модель demand/bid/conversion. Исторически она использовалась для исследования UNIQUE/FK/CHECK/partial unique в PostgreSQL. Ветку docs/smart-deal-r2 можно после проверки ancestry считать только архивом, не вести в ней новые разработки.
