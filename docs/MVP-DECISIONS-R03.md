# QRM — MVP resolution pack R0.3 (pricing, receipt, recovery, nightly, privacy)

**Date:** 2026-11-12. **Status:** product preferences recorded, proposed technical defaults subject to S2/security/legal review. No implementation or executed tests.

## Outcome matrix

| Topic | Decision / status |
|---|---|
| OPEN-15: unverified phone login | **DEFERRED PRODUCT CHOICE; SECURITY BLOCKER** for broad public registration. No SMS. Never imply verified ownership. See safe interim restrictions below. |
| OPEN-11: account recovery | **APPROVED direction:** verified optional email recovery. No verified email or trusted authenticated session -> no automated account takeover/reset. Explicit user-facing limitation at onboarding; manual recovery requires defined, audited support procedure. |
| OPEN-03: KZT pricing | **PROPOSED exact policy:** use BigDecimal and calculate commercial KZT totals to 2 decimals; distinguish optional cash-settlement rounding of final amount to whole KZT. QRM does **not** process payments. Exact tax, discount and rounding order to S2/legal. |
| OPEN-13: receipt evidence | **APPROVED direction:** in-app one-time QR/code handover. **PROPOSED MVP implementation:** order-bound expiring QR challenge shown by seller, authenticated buyer scans and **explicitly presses Confirm receipt**. No SMS, seller cannot self-confirm. |
| OPEN-16: nightly | **APPROVED direction:** run after midnight for *previous* business day; **PROPOSED default** 00:30 Asia/Almaty daily, cutoff 00:00. Consistent snapshot as-of cutoff still mandatory. |
| Trading tiers | **APPROVED direction:** sensible initial thresholds, separately for buyer/seller, versioned; values below remain **PROPOSED** for S2 calibration. |
| Data localization | **APPROVED direction:** personal data and storage in Kazakhstan, selective encryption, least privilege, retention. FCM/email providers and backups require cross-border/security inventory before release. |

## Identity without SMS — hard boundary

- User principal is immutable internal `accountId`; phone is a **unique but UNVERIFIED login alias**, not evidence of control.
- There is **no secure way** to prove ownership of a phone purely by asking a password or sending a push to a new, untrusted device. A prior registration can occupy someone else's phone alias (account squatting).
- Rate-limit registration, log alias changes, prevent account enumeration, notify user explicitly that phone is unverified. Do not expose `phoneVerified=true`; prevent phone-based password reset and seller verification badges.
- **Required before broad public release:** pick a safe path: (A) use verified email as primary sign-in identifier, phone optional contact only; or (B) introduce independently verifiable phone ownership through a future approved method. Until then restrict registration to pilot/test cohort where identities/contact are separately checked by operator, and do not treat phone alias as trusted.
- Email verification must use a single-use, short-lived signed/random token stored hashed, rate-limited; sends via email provider or self-hosted SMTP. If email not verified, it cannot be used to reset password.

## KZT monetary arithmetic and offline settlement

**Reference as of 2026-11-12:**
- National Bank Law (Arts. 38–39): one tenge = 100 tiyn, legal tender KZT. https://insecure.zan.kz/rus/docs/Z950002155_
- Presidential Decree No. 549 (Art. 5-1): for cash settlement, arithmetic rounding of **payment total** tiyn < 50 to 0 and >= 50 to 1 KZT. https://old.adilet.zan.kz/rus/docs/U010000549_

**Proposed pricing contract, not a claim that legislation prescribes DB schema:**
1. Catalog unit price and quantity are `NUMERIC` / Java `BigDecimal`, never float; unit price may have 2-decimal KZT scale or higher internal precision for quantity-based units only under explicit policy.
2. Apply one eligible tier, then no more than one scheduled campaign, then negotiated offer if applicable (offer replaces, rather than compounds with, effective public unit price; snapshot of accepted terms is authoritative).
3. Compute `quantity × effectiveUnitPrice` with intermediate precision and round monetary **order total to 2 decimals** using deterministic `HALF_UP` proposal. Distinguish unit rounding, VAT/taxes and campaign percentage calculation under S2 before public charging; discount can't make price negative.
4. Order snapshot keeps full agreed `unitPrice, quantity, total, currency=KZT, sourceVersion, discount/offer, roundingVersion`. Seller/buyer cannot alter a confirmed snapshot.
5. For an **optional cash settlement estimate**, round **final total** separately to whole KZT using cash rule above; do not overwrite original negotiated value or mislabel estimate as proof of payment. For bank transfer, display exact two-decimal total unless another applicable payment rule is confirmed.
6. Since QRM is not a payment processor, neither cash rounding nor Order status establishes actual payment.

Examples: `1234.49 KZT` cash estimate `1234 KZT`; `1234.50 KZT` -> `1235 KZT`. These apply to **overall payment**, not to each unit.

## Receipt confirmation — QR challenge with explicit buyer consent

```mermaid
sequenceDiagram
 actor S as Seller
 participant API as QRM
 actor B as Buyer in authenticated Flutter
 S->>API: POST /orders/{id}/receipt-challenge
 API-->>S: One-time QR (order bound, 2-min TTL proposal)
 B->>API: Scan QR + GET challenge details
 API-->>B: Correct order, listing, quantity, seller
 B->>API: Explicit Confirm Receipt
 API->>API: Verify buyer owns Order, challenge TTL, order fulfillment READY
 API->>API: Atomic RECEIVED + Order COMPLETED + outbox
 API-->>B: Receipt confirmed; rating unlocked
 API-->>S: Completed order notification
```

- QR contains **only a random opaque reference**, not phone, address, payment, refresh token or unrestricted order ID.
- Seller creates challenge only for their Order in `READY_FOR_RECEIPT`. Buyer must be authenticated, must own that Order, must explicitly confirm. Seller's scan/code entry alone is **not** proof of receipt.
- Single-use with time-to-live 2 minutes (proposal); redeem uses database conditional update/unique constraint. Replays fail; competing confirmations yield one state change, safe idempotent result to same buyer.
- Buyer can alternatively open own order and click `Confirm receipt` without QR if the handover was remote; require explicit user action and identical ownership checks. For digital/service fulfillment, type-specific delivery/acceptance must be approved before enablement.
- Buyer may refuse, report `Not received` or open dispute. Expired code reissued by seller, not reused. If buyer never responds, do **not** auto-award rating/tier or mark payment successful. Dispute/support policy remains release dependency.
- This is a **transactional app challenge**, not a login OTP and does not contradict DEC-27 no SMS.

## Nightly profile calculations

- **Interpreted 'после 12' as after local midnight**: scheduler starts **00:30 Asia/Almaty**, processes *completed previous local business day* with upper bound exactly prior/current midnight (exclusive).
- `windowEnd` = start of current local business day, `windowStart = windowEnd - 9 calendar months` in Almaty timezone, convert both to UTC; use `[windowStart,windowEnd)`.
- Future alternative run at 12:30 local noon only changes execution time, **not** cutoff; require explicit change before scheduling if 'после 12' meant noon.
- Order/Review source extraction must be transactionally coherent: e.g., R2DBC `REPEATABLE READ` read extraction or proven committed event-journal cursor, not merely event timestamp. Late corrections in next run; publish staged buyer/seller/relationship snapshots atomically after consistency validation. Previous snapshot remains visible on failure.
- No Kafka, separate analytics DB, or default monthly partitioning in MVP. An indexed source projection plus single nightly job is enough until profiling proves otherwise.

## Initial tier thresholds (PROPOSED; 9-month rolling window)

| Level | Buyer qualifying received Orders | Seller qualifying fulfilled/received Orders |
|---|---:|---:|
| Путник | 0–2 | 0–4 |
| Исследователь | 3–9 | 5–19 |
| Купец | 10–24 | 20–49 |
| Мастер | 25–49 | 50–149 |
| Легенда | 50+ | 150+ |

These counts intentionally do **not** require a minimum active-month count in MVP, to avoid unexplained 'stuck' progress. New accounts with no evidence show 'Новый/Нет истории' in context, not a negative risk signal. `trade_tier` and lifecycle are independent. Seller star rating and count remain independent of tier. A 'Постоянный клиент' relationship is **>=3 qualified purchases at the same seller in 9 months** (proposed); seller cannot view other sellers' purchases. Anti-self-dealing rules and dispute handling required before using tier for any benefit.

## Kazakhstan data protection design

- Personal data store in Kazakhstan data center and/or digital infrastructure complying with the current Kazakhstan law on personal data and protection, including Art. 12 amended in 2026: https://old.adilet.zan.kz/rus/docs/Z1300000094
- Database, backups/PITR, object storage, full-text search if added, audit/log collection and support exports must be located/assessed for KZ storage; no raw PII in telemetry.
- HTTPS/TLS in transit; field-level encryption or envelope encryption for lead phone, addresses, claim data, optional sensitive metadata; DB/disks and backups encrypted; independent KMS/secret management, RBAC, audit, least privilege and rotation.
- Passwords **hashed Argon2id**, not encrypted reversibly. Session/withdrawal/receipt tokens hashed at rest. Public seller name and published listing images need access control based on public/private state rather than blind encryption of everything.
- Inventory third-party processors: email delivery, FCM, crash analytics, CDN, S3 storage, push tokens. Disclose lawful cross-border transfers and evaluate applicable consent/local processing requirements; do not assume 'KZ database' makes every transfer compliant.
- Purpose-specific consent for Web callbacks, retention, deletion subject to statutory periods, seller access to contact only to fulfill callback, data-subject rights. Legal counsel should review applicable law and rules (including technical protection standards), especially before public release.
- Localization legal reference: https://old.adilet.zan.kz/rus/docs/Z1300000094 ; current Art. 12 amended via Law 326-VIII effective 2026-08-25.

## Executable RED oracles

| ID | Scenario | Oracle |
|---|---|---|
| R03-01 | anonymous registers another person's number | phone remains UNVERIFIED, no identity claims/recovery; public release blocked until policy |
| R03-02 | lost device with verified email | single-use expiring reset; old refresh sessions revoked |
| R03-03 | lost device without verified email or trusted session | no insecure reset; safe support escalation |
| R03-04 | KZT cash 100.49 / 100.50 | 100 / 101 KZT estimates, underlying order precision unchanged |
| R03-05 | percent discount then quantity boundary | deterministic 2-decimal total; no double-discount |
| R03-06 | stale quote vs accepted negotiated offer | order snapshots correct accepted terms or explicit conflict |
| R03-07 | seller issues receipt QR | only for own READY_FOR_RECEIPT Order, expires, no PII in payload |
| R03-08 | different buyer scans receipt QR | cannot confirm another buyer's order |
| R03-09 | valid buyer scans and confirms twice | one RECEIVED/COMPLETED/outbox |
| R03-10 | seller alone submits QR/code | not allowed to mark buyer receipt |
| R03-11 | buyer refuses/not received | order remains unconfirmed, dispute path available |
| R03-12 | daily job at 00:30 | as-of cutoff previous midnight, 9M correct at boundary |
| R03-13 | concurrent modification during extraction | coherent source snapshot or journal cursor |
| R03-14 | buyer/seller threshold boundary | correct independent tiers and rating |
| R03-15 | buyer has no current-month event | retain 9M history |
| R03-16 | KZ backup and log locations | no unintended foreign replica/PII leak |
| R03-17 | disabled FCM/email outage | durable in-app notification, critical Order unaffected |
| R03-18 | versioned consent withdrawn | restrict processing per legal retention policy; audit |

**Ship gate:** Scenario Design → executable RED → independent S2 → GREEN → regression + actual PostgreSQL/Testcontainers + security/privacy review. No runnable code asserted.
