# ZAKAZ — Matching Trade-Rule Closure → Seller Response ML Proof

**Branch:** \`docs/adr-002-jpa-architecture-review\`. **Release: HOLD.** No new branch or merge to main.
**Purpose:** Close a business function with executable evidence, not a count of tests or code files.

## Stage 1: ensure invitations are actionable

Canonical rule: A seller may be invited for a BuyerDemand only when the *same seller* is able to submit a valid SellerBid for **every requested line**, in the *requested unit*, with sufficient *free stock at one eligible active origin*. Seller must have the role required by SellerBid HTTP (\`SELLER_ADMIN\`), an active published catalog and both buyer/seller opt-in. UI quote limits cannot conceal the only executable stock candidate. No model or score can override these hard gates.

| ID | Violation an executable PostgreSQL test detects | Oracle |
| --- | --- | --- |
| MATCH-001 | Candidate finder selects tracked SERVICE/LICENSE stock, but SellerBidSubmissionService rejects those units | No invitation, inbox row or SellerBid |
| MATCH-002 | Candidate finder selects a stocked SKU after first 20 UUID-sorted SKUs, while quote context hides it | If invited, quote context exposes a sufficient SKU and POST can submit |
| MATCH-003 | Internal invitation evaluator marks CATALOG_EDITOR or unquotable sales unit eligible even though SellerBid requires SELLER_ADMIN and supported physical unit | Decision SUPPRESSED; no externally usable invitation |
| MATCH-004 | Multiple demand lines are split across distinct seller locations | No eligible invitation unless a single origin can execute the entire basket |
| MATCH-005 | Seller stock falls between preview and bid POST | POST 409 and zero bid/receipt/order/reserve/outbox side effects |
| MATCH-006 | Seller listing, category, seller membership or buyer/seller consent revoked between scan and bid | No successful unauthorized bid; no PII exposure |
| MATCH-007 | 41+ seller locations; the only stocked origin is outside quote-context page | Either expose eligible origin or suppress unusable invitation |
| MATCH-008 | Concurrent demand matching/dispatch; previous decision/inbox already exists | Exactly one immutable decision and one delivered inbox item |
| MATCH-009 | SellerBid expires, buyer accepts another bid, or last unit is reserved concurrently | State/stock constraints enforced at accept and confirmation; no oversell |
| MATCH-010 | Browser buyer/seller actions from Listing publication through CONFIRMED | Real HTTPS + two Keycloak contexts + SQL assertions; no internal service calls |

**RED evidence for MATCH-001/002** at \`e774f4d3e0480c63eeec418c66e5bd7364d8fb50\`: [Backend PostgreSQL CI](https://github.com/alimbetov/QRM/actions/runs/43560780199), 113 PostgreSQL integration tests, 2 failures and 0 errors. Additional MATCH-003 and full-basket parity RED was reproduced in [CI](https://github.com/alimbetov/QRM/actions/runs/43561138000). **GREEN on exact SHA \`9c163e1fd8078c4dc9687205d42dc453278ba8a8\`:** [PostgreSQL verification](https://github.com/alimbetov/QRM/actions/runs/43561775076) **PASS, 123 integration tests, 0 failures/errors/skips;** Web, Flutter and staging workflow checks also PASS on this SHA. This is DB_VERIFIED for the measured scope, not production browser qualification. Earlier overlapping Flyway V28 numbers were reconciled as Catalog V28 and ML V29.

**Do not confuse** internal \`MlSellerInvitationService.evaluate\` with an end-to-end invitation. Real USER_E2E_VERIFIED requires the scheduled scanner, event queue, private inbox, real seller UI and valid bid.

## Stage 2: reproducible seller_response_24h dataset, NOT a fabricated response rate

First bounded ML task: predict whether an **actually exposed, qualified seller** responds with a valid SellerBid within 24 hours. This is not a prediction of payment, completed sale or guaranteed best price. Eligibility remains rules-only.

Source evidence (PostgreSQL): \`ml_seller_invitation_inbox.delivered_at\` is in-app availability; \`ml_seller_invitation\` is historical eligibility, not delivery; \`seller_bid_snapshot.submitted_at\` is response evidence; \`ml_trade_event\` provides committed fact/event ingestion time; consent and membership source state must be tracked through each label window. No SMS, push or external email may be assumed.

Required immutable dataset manifest: dataset revision, cutoff instant UTC, source transaction/source watermark, event schema and feature schema version, inclusion/exclusion reason counts, canonical feature dictionary revision, row count, SHA-256 digest, build git SHA and code/config version. Rebuilding the same snapshot must produce byte-identical rows/digest; new late events create a new revision, never a silently modified historical dataset.

**Temporal label contract**:
- \`1\`: a valid bid submitted after in-app availability and before availability + 24h, provided the event was ingested by the permitted observation cutoff;
- \`0\`: 24h elapsed, full observation watermark reached window end, invitation and seller eligibility remained observable and consent was not revoked throughout, and no valid bid exists;
- \`EXCLUDED/CENSORED\`: undelivered candidate, missing consent history, incomplete observation, insufficient eligibility history, lost seller access, expiry before 24h, or any untrustworthy outcome.
- A seller who was not invited is **never** a negative training example.
- All input features must be materialized **as of the invitation availability time**, not from current catalog, eventual order status or a later seller bid.
- Multiple lines, revisions and delivery replays must not duplicate one observation.
- Preserve UTC instants and document Asia/Almaty reporting normalization. Store no phone/email/GPS/PII in ML datasets.

**R3 acceptance tests**: point-in-time cutoff and future-leakage rejection; repeat-build SHA equality; changed source watermark => revision change; never-exposed seller excluded; 24h boundary; consent revocation and incomplete observation censored; one label per invitation; no cross-seller/buyer information leakage; no temporal joining to post-outcome facts. These must run on PostgreSQL 17 Testcontainers, not H2 mocks.

A synthetically seeded dataset is useful for RED/GREEN correctness, **not** proof of predictive efficacy. Without enough genuine historical exposure plus completed observation windows, dataset is \`BLOCKED_FOR_TRAINING\`.

## Stage 3: one model versus a defensible baseline

Use a single \`seller_response_24h\` logistic-classification candidate with fixed Tribuo version, seed, schema and training artifact hash. Train offline only. Compare to deterministic eligibility + pre-invitation historical response-rate ranking, not to a weak random selection. Both rank **the same strictly eligible seller set**; neither can change a price or skip human order acceptance.

- Strict chronological training/validation/final holdout, with at least the full 24h observation-window embargo and demand/cohort de-duplication. Freeze holdout prior to model selection; avoid seller/demand information across split boundaries where it leaks.
- Primary target: stronger *per-demand response capture at top K invited sellers*, with confidence interval across independent buyer demands. Report Precision@K/Recall@K, PR-AUC, calibration/Brier, model coverage, invitation volume and time-to-first-response. Maintain total cost transparency; no misleading best-deal assertion.
- Required model promotion test: evaluated on real held-out exposure data with matching eligible pools; positive lift over baseline with pre-registered confidence/uncertainty; acceptable subgroup harms; fixed artifact and dataset hashes; fail-safe deterministic fallback.
- A \`PASS\` on synthetic examples is \`TEST_INFRA_VERIFIED\`, **never ML_UPLIFT_VERIFIED**. A poor model is \`REJECTED\`, not promoted by adjusting assertions.
- Without a verified external holdout and independent S2 reviewer, set \`MODEL_RESEARCH_ONLY\`, keep model flags OFF, and return the deterministic baseline.

## Release board

| Gate | State |
| --- | --- |
| Source-level matching / quote tests | GREEN at exact SHA 9c163e1; targeted PostgreSQL regression passed |
| PostgreSQL matching regression final GREEN | DB_VERIFIED on 9c163e1 (123 IT, zero failures); newer HEAD requires own CI |
| Real two-user trade E2E | BLOCKED / not proven |
| Point-in-time dataset versioned/verified | NOT IMPLEMENTED |
| Real 24h outcome cohort available | NOT ESTABLISHED |
| Tribuo trained seller-response candidate | NOT IMPLEMENTED |
| Independent offline model uplift demonstrated | NOT ESTABLISHED |
| Independent S2 approval / production model | NOT APPROVED |

**Non-negotiable order:** MATCH-001…MATCH-010 GREEN → authorized real delivery E2E → R3 dataset with genuinely qualified labels → R4 one candidate with honest out-of-time baseline comparison → S2 → optional model activation. This sequence prevents a high-scoring model from ranking suppliers who cannot actually place a valid bid.
