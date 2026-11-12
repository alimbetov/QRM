# QRM — API contract design v1.4

**Статус:** API-First draft. Фактическая OpenAPI 3.1 схема и примеры контрактных тестов — следующий артефакт R0. Внешний REST API версионируется `/api/v1`. Внутренние domain services отделены от транспортных DTO.

## 1. Единые соглашения

- HTTP JSON UTF-8, даты RFC3339 UTC, ISO currency `KZT`. Decimal деньги и количество передаются **строкой** (`"12000.00"`), не JSON floating point.
- Ошибки как RFC 9457 Problem Details (`type,title,status,detail,instance,code,traceId,fieldErrors?`), без PII и внутреннего стека.
- Paging: `limit` default 30, max 100, непрозрачный `cursor`, `items,nextCursor,hasNext`; стабильная сортировка `lastActivityAt DESC,id DESC`; cursor привязан к seller/user, фильтрам и версии сортировки.
- Mutation: `Idempotency-Key` для заказа, конвертации оффера, публикации события и существенных переходов.
- `If-Match` либо `expectedVersion` для состязательных изменений; `409 CONFLICT` при проигрыше гонки.
- `401` нет аутентификации, `403` нет права, `404` скрытый чужой ресурс, `422` нарушение бизнес-инварианта, `429` rate limit.
- `GET` и чтение списков не должны менять бизнес-статус; mark read — отдельная команда с ожидаемой activityVersion.

## 2. Identity, Seller, QR Login

| Method | Path | Actor | Purpose |
|---|---|---|---|
| POST | `/auth/otp/request` | guest | request OTP |
| POST | `/auth/otp/verify` | guest | verify and create/login |
| GET | `/me` | authenticated | current identity, roles |
| GET | `/sellers/{slug}` | public | seller profile, logo, rating |
| PATCH | `/me/seller-profile` | seller | update profile |
| PUT | `/me/seller-profile/logo` | seller | attach verified media object |
| POST | `/seller-login/challenges` | browser | create short-lived QR challenge |
| POST | `/seller-login/challenges/{id}/approve` | authenticated Flutter seller | approve with explicit device details |
| POST | `/seller-login/challenges/{id}/reject` | authenticated Flutter seller | deny |
| GET | `/seller-login/challenges/{id}/status` | bound browser | poll/SSE result only |
| POST | `/seller-login/challenges/{id}/consume` | bound browser | one-time session establishment |
| GET | `/me/sessions` | authenticated seller | list active Web sessions |
| DELETE | `/me/sessions/{id}` | authenticated seller | revoke session |

QR challenge contains only opaque random challenge reference + safe public origin; **never** access/refresh token. Bind challenge to browser by secret cookie; short TTL ~90s as proposal. Approving from Flutter must display origin/browser details and require explicit user interaction; idempotent consume and replay protection. Web seller session via Secure HttpOnly SameSite cookies, CSRF defense.

## 3. Catalog, Listing, Pricing

| Method | Path | Actor | Purpose |
|---|---|---|---|
| GET | `/categories` | public | localized category tree |
| POST | `/category-suggestions` | seller | request missing category |
| POST | `/listings` | seller | create DRAFT |
| PATCH | `/listings/{id}` | owner | edit listing |
| POST | `/listings/{id}/publish` | owner | publish PUBLIC or UNLISTED |
| POST | `/listings/{id}/archive` | owner | archive |
| GET | `/public/listings/{publicId}` | public | storefront projection |
| GET | `/sellers/{slug}/listings` | public | active seller listings, paged |
| POST | `/media/uploads` | owner | validate/upload workflow |
| PUT | `/listings/{id}/pricing-tiers` | owner | replace versioned tier set |
| POST | `/listings/{id}/discounts` | owner | schedule campaign |
| GET | `/public/listings/{publicId}/quote?quantity=...` | public | effective price quote |
| POST | `/listings/{id}/qr-codes` | owner | create QR source |
| GET | `/listings/{id}/qr-codes` | owner | list QR sources |

Prices returned as `{unitPrice:"9000.00",total:"90000.00",currency:"KZT",quantity:"10",quoteExpiresAt,pricingVersion}`. Never accept client-calculated total as authoritative.

## 4. Lead, Negotiation, Order

| Method | Path | Actor | Purpose |
|---|---|---|---|
| POST | `/public/leads` | guest | phone/callback request, anti-spam |
| POST | `/public/leads/{id}/withdraw` | claimant with secure proof | withdraw open lead |
| GET | `/seller/leads` | seller | paged list |
| POST | `/negotiations` | Flutter buyer | submit initial price |
| POST | `/negotiations/{id}/offers` | authorized buyer/seller | counter within 5 rounds |
| POST | `/negotiations/{id}/accept` | counterparty | agree latest offer |
| POST | `/negotiations/{id}/reject` | counterparty | reject |
| POST | `/negotiations/{id}/withdraw` | buyer | withdraw before agreement |
| POST | `/negotiations/{id}/create-order` | authorized actor | one-time conversion AGREED → Order |
| POST | `/orders` | Flutter buyer | order using current public price |
| GET | `/me/orders` | authenticated | own orders with cursor |
| POST | `/orders/{id}/cancel` | buyer | cancel before confirmation |
| POST | `/orders/{id}/cancellation-requests` | buyer | request cancellation in progress |
| POST | `/orders/{id}/transitions` | authorized actor | guarded named business action |
| POST | `/orders/{id}/claims` | buyer | documented claim/return |
| POST | `/orders/{id}/reviews` | eligible buyer | verified review |

Web lead example:
```json
{"listingPublicId":"x82M","phone":"+7XXXXXXXXXX","quantity":"2","preferredUnitPrice":"11000.00","callbackConsent":true,"comment":"После 18:00"}
```
Response `202 Accepted` with anonymous request reference; never echo seller-only data or promise order.

Flutter agree → order: `POST /negotiations/{id}/create-order` with Idempotency-Key; return `201 Created` with order ID and acceptedOfferId. Repeat with same key returns same result; different request on already converted offer yields 409 or existing reference (specified consistently in OpenAPI).

## 5. Seller Inbox

```http
GET /api/v1/seller/inbox?type=LEAD&read=false&bookmarked=true&limit=30&cursor=...
GET /api/v1/seller/inbox/unread-counts
PUT /api/v1/seller/inbox/{type}/{id}/read-state
PUT /api/v1/seller/inbox/{type}/{id}/bookmark
```

Read-state request: `{"value":true,"observedActivityVersion":7}`; server cannot mark later unseen version read. Manual unread uses `{"value":false}`. Bookmark request `{"value":true|false}`. Items return businessStatus, lastActivityAt, read/unread, isBookmarked, subjectId, latestOffer summary and source; never expose private contact except on authorized detail endpoint.

## 6. Favorites, Notifications, Analytics

```http
POST   /api/v1/me/favorites/{listingId}
DELETE /api/v1/me/favorites/{listingId}
GET    /api/v1/me/favorites
PUT    /api/v1/me/favorites/{listingId}/price-watch
PUT    /api/v1/me/notification-preferences
POST   /api/v1/me/push-devices
DELETE /api/v1/me/push-devices/{id}
POST   /api/v1/analytics/events
GET    /api/v1/listings/{id}/analytics
```

Favorite API belongs to authenticated identity, but Web React hides/disables feature product-wise. Anonymous analytics accepts only schema-constrained events and enforce anti-abuse; never trust supplied seller/user IDs.

## 7. Unresolved contract details

Authentication grant/session exchange, exact constraints/rounding, enum values and legal consent text, quote expiry, image upload design, rate limits, QR attribution vs referrer, status transition commands, seller staff roles and auth boundaries require R0 formal OpenAPI review. No endpoint is treated as shipped until contract tests are green.
