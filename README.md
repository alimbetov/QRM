> **Единое мастер-ТЗ:** [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) (ZAKAZ v2.0). Предыдущая редакция хранится в [docs/archive/REQUIREMENTS-v1.4-2026-11-12.md](docs/archive/REQUIREMENTS-v1.4-2026-11-12.md). Принятые продуктовые решения не равны готовой реализации.

# QRM — QR Marketplace

QR Commerce Platform for Kazakhstan: seller listings, QR storefronts, Web purchase leads, Flutter orders, pricing negotiations, favorites, and analytics.

Project requirements are maintained in `docs/` through reviewed pull requests.

**Status (2026-11-13):** R2.3 implementation in Draft PR #2 on `docs/adr-002-jpa-architecture-review`: Java 21 MVC/JPA/PostgreSQL/Flyway Backend, authenticated demand/bid/order APIs and QR/guest-lead HTTP, React QR route with optional seller-set KZT SKU unit price. All public commerce and QR HTTP routes are gated OFF by default. Independent S2 review, external OIDC, browser/mobile end-to-end acceptance and 5000-VU load qualification remain open. `main` is not the implementation baseline.
