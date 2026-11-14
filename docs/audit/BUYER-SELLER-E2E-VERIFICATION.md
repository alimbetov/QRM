# ZAKAZ — Buyer/Seller Functional Proof & Regression Qualification

**Status: HOLD / NOT USER_E2E_VERIFIED.** Repository audit, not a real staging acceptance run or independent S2 approval.
**Repo:** alimbetov/QRM
**Only branch:** docs/adr-002-jpa-architecture-review; main remains unchanged.
**Starting exact SHA:** 51c4125a2d053b767e6318de26df89d710542273 (2026-11-14).
**Final qualifying SHA:** NOT ASSIGNED. HEAD has been advancing concurrently; verify all mandatory CI against one final commit.

## Verification model

| Level | Required evidence |
| --- | --- |
| CODE_PRESENT | Source exists; execution is not implied |
| UNIT_VERIFIED | Actual exact-SHA unit/widget run PASS |
| DB_VERIFIED | PostgreSQL 17 transaction/invariant tests executed PASS |
| API_VERIFIED | Real HTTP with verified Keycloak/JWKS and authorized actors PASS |
| USER_E2E_VERIFIED | Two independent browser identities, React business actions, autonomous invitation, independent PostgreSQL verification |
| RELEASE_VERIFIED | Exact-SHA mandatory regression + negative tests + independent S2 APPROVE |

Never count Playwright --list, a test file, a mock JWT, or a green unrelated workflow as full user acceptance.

## End-to-end matrix

| Step | Observed artifacts | Current qualification |
| --- | --- | --- |
| Seller publishes stocked DRAFT Listing as PUBLIC | SellerWorkspace; staging-seller.e2e.ts | CODE_PRESENT, live browser unverified |
| Buyer discovers Listing and creates BuyerDemand | BuyerFlow; AuthenticatedDealHttpPostgresIT | Existing PostgreSQL tests, exact-SHA recheck required |
| Buyer/seller explicit invitation consent | BuyerInvitationSharing, SellerInvitationPreference | Web unit checks; live auth unverified |
| Matching and invitation generation | MlInvitationCandidateScanner + MlInvitationCandidateScanConfiguration schedule + ML dispatcher | Candidate scan is CODE_PRESENT and covered by PostgreSQL source tests; autonomous *real staging* delivery still UNVERIFIED |
| Seller submits compatible own SKU quote | SellerInvitationBidWorkspacePostgresIT, React/Flutter composers | DB tests exist; live seller UI unverified |
| Buyer accepts one bid to PLACED | BidAcceptancePostgresIT | DB test exists; live two-browser unverified |
| Seller confirms to one reservation | OrderConfirmationPostgresIT; enhanced PostgreSQL test | Additional assertions committed, exact-SHA CI pending |
| Buyer and seller see CONFIRMED | BuyerFlow, SellerDealsPanel | Real dual-session observation pending |
| Outbox delivery under retries | OutboxWorkerPostgresIT | Multi-worker staging and new exact-SHA evidence pending |
| Real Android/iOS AppAuth | Flutter unit/widget sources | Physical-device E2E NOT RUN |

## Code/test changes in this qualification slice

- 66a5913dd56346e6b9939664fa714c8132a9ea14: added full two-actor Playwright journey in web/e2e/staging-dual-user-commerce.e2e.ts.
- 5fe29f189d77ccce424f14e679538258b07f0313: browser contexts now explicitly receive HTTPS baseURL.
- 89c26da7635b636b693a40eef7313d80f1a41b63: enhanced SellerInvitationBidWorkspacePostgresIT with zero reservations/outbox before confirmation, exact reserved balance, exactly one movement and outbox on confirmation, and no duplicate side effects on retry.
- 85a31b81ca9a6b68da04b9afdf947237d1a15cd3: staging Compose exposes seller studio and internal invitation evaluator switches, default OFF.
- 6b45fadebd8b1bd1bbde94d9280dec49bd7339fc: repaired broken idempotency-key regex and duplicated Dart class tail in mobile/lib/zakaz_api.dart.
- cb1b61162bafc119ef26dbeef835d21829920d6b: private staging env template declares fail-closed dual-user feature switches.

The new E2E scenario does not create a bid or invitation through direct SQL, hidden Service calls, or operator-authored HTTP posts. Seller publishes a stocked DRAFT fixture using React, buyer creates demand and consents through React, seller opts in and awaits a naturally delivered invitation, responds through React, buyer accepts through React and seller confirms through React. After both see CONFIRMED, read-only deploy/staging/check-order.sql is invoked through the isolated Docker PostgreSQL service. Failure to receive an invitation raises an explicit BLOCKED diagnostic; it is never counted as PASS. No test credentials are committed.

The SQL checks exact synthetic 125000.50 KZT amount, status CONFIRMED, one RESERVED line of 1.000, one reservation movement, one ORDER_CONFIRMED outbox event and no oversold balance. It is a fixture-specific oracle, not a general proof for arbitrary baskets.

## Recorded CI observations (historical; NOT final-SHA approval)

- Former React failure at d154af943b8d814f100a9c14097e5d0243a5501c: 59 PASS / 1 FAIL, async consent error contract; https://github.com/alimbetov/QRM/actions/runs/43558207910
- Web CI at 5fe29f189d77ccce424f14e679538258b07f0313: SUCCESS, including listing of real-staging Playwright declarations; https://github.com/alimbetov/QRM/actions/runs/43558922282
- Flutter CI at 85a31b81ca9a6b68da04b9afdf947237d1a15cd3: FAIL, Dart syntax cascade; https://github.com/alimbetov/QRM/actions/runs/43559023806. After syntax, widget test, and visible-receipt fixes, Flutter CI at c7deeaccf414b713ea49c93ddf26fe13295f0b7f reported SUCCESS: https://github.com/alimbetov/QRM/actions/runs/43559594140. This is NOT physical-device qualification.
- Backend exact-SHA test run and every final current branch check: RECHECK REQUIRED.
- Real Keycloak + PostgreSQL two-browser E2E: NOT RUN. No private staging instance, operator credentials, or physical devices were available in this execution.

## How to qualify on isolated staging

Provision a fresh synthetic fixture as described in deploy/staging/README.md and real verified Keycloak buyer + seller. Keep the feature flags OFF outside explicitly authorized private staging. Required flags for the backend and UI are seller workspace, seller studio, seller bid workspace, ML-R2 consent API, ML internal delivery, and internal invitation evaluation. Enable both evaluator and candidate-scanner flags only on authorized staging. A scheduler class and PostgreSQL tests now exist; this does not prove the complete real UI delivery journey.

Set these private operator-only environment variables on the staging machine:

    STAGING_BASE_URL=https://your-authorized-staging-domain
    STAGING_FIXTURE_FILE=/abs/path/deploy/staging/evidence/fixture.env
    STAGING_PRIVATE_ENV_FILE=/abs/path/deploy/staging/private.env
    STAGING_SELLER_BROWSER_USERNAME=<synthetic-seller>
    STAGING_SELLER_BROWSER_PASSWORD=<secret>
    STAGING_BROWSER_BUYER_USERNAME=<synthetic-buyer>
    STAGING_BROWSER_BUYER_PASSWORD=<secret>
    STAGING_DUAL_USER_UAT=true

Then, from the web directory, run:
    
    npm ci
    npx playwright install chromium
    npm run e2e:staging -- staging-dual-user-commerce.e2e.ts

The script requires Docker Compose access on the staging operator host for independent SQL verification. Do not run this against production or any real customer data. Do not publish browser storage, access tokens, passwords, traces, or private.env.

## Negative/concurrency matrix

| Risk | Existing evidence / mandatory next check | Gate |
| --- | --- | --- |
| Foreign seller confirming another seller order | Seller ownership PostgreSQL tests | Exact-SHA 403 + zero side effects |
| Buyer accepting a foreign bid | AuthenticatedDealHttpPostgresIT | Exact-SHA negative DB test |
| Two concurrent bid acceptances | twoConcurrentHttpBidAcceptancesCreateExactlyOnePlacedOrder | One PLACED order, never duplicate |
| Two orders on final stock | OrderConfirmationPostgresIT | No overselling / race tests |
| Repeated confirm | enhanced SellerInvitationBidWorkspacePostgresIT | Exactly one reservation/movement/outbox |
| Revoked consent | nonConsentedOrRevokedInvitationNeverExposesDemandOrCreatesBid and legacy endpoint regression | Fail closed, no bid persisted |
| Expired offer / stock changed between preview and confirm | Additional targeted PostgreSQL tests if missing | OPEN |
| Network timeout after committed POST | Idempotency receipt and uncertain-network test | OPEN |
| Two outbox workers deliver/retry | OutboxWorkerPostgresIT + staging two-worker execution | OPEN |
| Real Flutter native device flow | Android/iOS real OIDC + API tests | BLOCKED |

## Defect register

- **P0-INVITE-AUTO-E2E:** autonomous discovery/scheduling has been added and PostgreSQL tests exist, but real buyer-to-seller inbox delivery through Keycloak/React remains UNVERIFIED. OPEN as an acceptance gate.
- **P0-DEVICE-E2E:** real Android/iOS Keycloak and buyer/seller lifecycle not demonstrated. BLOCKED.
- **P0-NETWORK-RETRY:** guarantee on an uncertain seller bid POST must be proven with durable idempotency and replay tests. OPEN until verified.
- **FIX-FLUTTER-SYNTAX:** malformed regexp and duplicated class content; patch committed, verify new SHA CI.
- **FIX-REACT-CONSENT:** sync/async rejection mismatch from earlier failure; later code changed, verify new SHA CI.
- **FIX-STAGING-GATES:** missing seller studio and evaluator passthrough added; both remain default OFF.

## S2 decision

**INDEPENDENT S2 APPROVE: NOT OBTAINED. RELEASE: HOLD.**
Author self-review cannot substitute for independent approval. Do not merge Draft PR #2 into main until all mandatory exact-SHA checks, genuine two-actor browser journey, stock/outbox evidence, negative cases and independent review are complete.
