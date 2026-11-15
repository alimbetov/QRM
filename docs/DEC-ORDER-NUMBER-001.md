# DEC-ORDER-NUMBER-001 — readable ZAKAZ order number and QR security

**Decision: APPROVED by product owner (2026-11-12).** Document/contract only; implementation and executable tests are not yet in the repository.

## Public number

- Customer-facing format **`ZK-XXXX-XXXX`**, example **`ZK-7M4P-9K2D`**.
- Prefix `ZK` denotes ZAKAZ. The eight characters are generated using `SecureRandom` from alphabet `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (32 characters; excludes ambiguous 0, 1, I and O), uppercase.
- All eight characters are independent uniformly sampled random symbols; **no sequence, date, seller ID, location or identifiable information is encoded**. Human-entered lower-case accepted after normalization; canonical display uses uppercase and two 4-character groups. No check digit in v1; do not claim typo-detection capabilities beyond input shape and existence check.
- A PostgreSQL unique constraint on `customer_order.public_number` is the final uniqueness authority. On a unique-conflict caused specifically by the generated public number, retry **the entire order-creation transaction** with a newly generated number, within a bounded maximum of e.g. 5 attempts. Do not retry arbitrary data/transaction faults, or duplicate an acceptedOffer/order, reservation, idempotency record or outbox event.
- Internal `customer_order.id` is a UUID and never derived from the public number. The public number is immutable, non-recycled and unique across all sellers.
- The visible number is a **public locator only**: knowing/guessing a number never grants access to order details or permits pickup, cancellation, receipt confirmation or any mutation. All detail reads and operations require scoped authorization; public lookup must not reveal whether another user's order exists.
- 32^8 = 1,099,511,627,776 possible suffixes. Collision probability becomes nonzero at scale; random generation is not a substitute for PostgreSQL UNIQUE and bounded retries.

## Receipt and fulfillment QR

- An Order may have multiple FulfillmentGroup (physical shipments, pickup groups, or separately accepted services). Every group gets an **internal UUID**, and optionally a separate non-sensitive public display reference (e.g. `01/02`) rather than reusing the order number as a credential.
- Permanent printed order QR may contain a canonical deep link / public routing token for opening the applicable order/group **after authorization**, but it must not directly mark goods/services as RECEIVED/ACCEPTED or grant access to private addresses.
- Receipt QR for handover is a **separate expiring one-time challenge** bound to group/order, intended buyer, seller and permitted action. Cryptographically random high-entropy token, store only token hash; short TTL and one-time consumed state; enforce actor permission, replay protections, rate limits and atomic consumption in PostgreSQL.
- Buyer must explicitly confirm receipt inside authenticated Flutter flow. Seller cannot confirm on behalf of buyer; scanning or presenting a permanent QR is not proof of delivery.
- Print formatting: `ZK-7M4P-9K2D`, sans-serif font, sufficient contrast, group label `1 из 2`, seller display name and public pickup city where appropriate; no buyer phone, private warehouse address or opaque confirmation secret on an unattended permanent receipt.
- For services, the group-specific acceptance follows a service-specific fulfillment state machine, not physical transport status. Digital flows remain a distinct contract.

## Interfaces (proposed API design; independent S2 review)

- Backend `OrderNumberGenerator.generate()` yields normalized canonical number, persisted in `CustomerOrder.publicNumber`.
- Order REST response returns `publicNumber` to authorized actors. Public React/QR guest endpoints cannot fetch order detail based solely on this number.
- `POST /api/v1/orders/{orderId}/fulfillment-groups/{groupId}/receipt-challenges` is authorized for the permitted seller/operator when fulfillment is READY_FOR_RECEIPT.
- Authenticated buyer redemption requires explicit confirmation; actual endpoint naming and challenge TTL remain pending S2.

## S0 Test-first / RED matrix

| ID | Given / when | Expected |
| --- | --- | --- |
| NUM-01 | Generate 1000 numbers with deterministic injected entropy | Every number matches `^ZK-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$` only if matching implementation alphabet; prefer test membership against shared alphabet constant, not a brittle hand-written regex |
| NUM-02 | Two orders assigned an identical candidate public number | Database unique constraint rejects collision; bounded retry creates a unique number without duplicate business side effects (PostgreSQL) |
| NUM-03 | Buyer or seller lists own order | Returns immutable human-readable publicNumber; correct scope (MockMvc/PostgreSQL) |
| NUM-04 | Unauthenticated/other buyer looks up someone else's publicNumber | No order details, private address or existence leak (MockMvc security) |
| NUM-05 | Retry order submission with same Idempotency-Key | Same Order UUID and publicNumber, no duplicate lines/Outbox (PostgreSQL) |
| NUM-06 | Store and load Order after restart | publicNumber stable; no regeneration (H2/PostgreSQL) |
| QR-01 | Print/open persistent QR | Only routing/authorized view, never receipt transition (API) |
| QR-02 | Seller issues group-specific one-time challenge | Valid status, buyer/order/group binding, hash at rest and expiry (PostgreSQL) |
| QR-03 | Wrong buyer, expired token or replay | Reject, no state change; privacy safe (PostgreSQL) |
| QR-04 | Authorized buyer explicitly confirms receipt for one of two groups | One group completed; whole order remains non-COMPLETED until all required groups qualify (PostgreSQL) |
| QR-05 | Concurrent duplicate confirmation | Exactly one transition and outbox event (PostgreSQL) |
| QR-06 | Service acceptance QR | Applies service-specific state transitions, not physical delivery rules (PostgreSQL) |

**Notes:** Never assume eight random characters guarantee global uniqueness without DB constraints; exact challenge policy, printed QR URL shape and service completion semantics need S2. Current repo remains documentation only.
