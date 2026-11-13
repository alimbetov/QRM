# ZAKAZ R2.3D — isolated staging with real OIDC

**Status:** reproducible infrastructure and E2E scripts committed to Draft PR #2. This is NOT evidence that remote staging has been deployed or that external OIDC/browser/mobile E2E has passed. Only deploy on an isolated, owner-controlled machine; never on production data.

## Architecture and security boundaries

- Two independent PostgreSQL 17 volumes: `app-db` for ZAKAZ/Flyway V1–V18, `iam-db` for Keycloak. Neither DB exposes a host port.
- Keycloak 26 with *real RSA-signed* OIDC access tokens, a fixed HTTPS issuer at `https://<STAGING_DOMAIN>/identity/realms/zakaz-staging` and an access-token `aud=zakaz-api` mapper. Separate public PKCE clients: `zakaz-buyer-web`, `zakaz-buyer-cli`, `zakaz-seller-cli`, `zakaz-mobile`. Password/direct-access grant is disabled.
- Keycloak admin URLs are not publicly proxied; administration runs through `docker compose exec keycloak` and local `kcadm.sh`. There are no hardcoded users, passwords, demo tokens or public seller-membership writes.
- Caddy terminates real public HTTPS, proxies `/identity/*` to Keycloak, `/api/*` to Java and serves the built React SPA. Requires DNS + ACME validation (TCP 80/443). **Do not disable TLS verification to make the backend JWT discovery work.**
- Java 21 Spring MVC/JPA enforces `prod`, `ddl-auto=validate`, managed Flyway, exact issuer/audience, trusted buyer-account links and seller memberships. Flags are ON only in this isolated stack. Separate `app-db` and `iam-db`; real secrets in an ignored file. Default API flags elsewhere remain OFF.
- Do not expose application databases, admin passwords, tokens or test customer data. Use only synthetic staging records. A successful staging test is not production/security approval.

## 1. Prepare private environment

Prerequisites: Docker Engine + Compose plugin, a dedicated host/domain with DNS A/AAAA records pointing to that host, ports 80/443, outbound ACME/JWKS access, minimum ~4 GB available RAM, and permission to deploy containers. The Caddy/Keycloak connection requires a publicly trusted TLS certificate. A localhost-only laptop without public DNS cannot qualify this particular HTTPS stack as-is.

```bash
cp deploy/staging/private.env.example deploy/staging/private.env
# Edit STAGING_DOMAIN to your real dedicated staging DNS name.
# Generate independent password values: openssl rand -base64 36
# Generate lead AES key: openssl rand -base64 32
chmod 600 deploy/staging/private.env

docker compose --env-file deploy/staging/private.env \
  -f deploy/staging/compose.yaml config --quiet
docker compose --env-file deploy/staging/private.env \
  -f deploy/staging/compose.yaml up --build -d
```

Check `https://<domain>/identity/realms/zakaz-staging/.well-known/openid-configuration`. The `issuer` field MUST exactly match the Backend `ZAKAZ_JWT_ISSUER`. Confirm JWKS has signing keys, `https://<domain>/api/v1/public/catalog` returns real JSON (possibly empty), and `https://<domain>/` serves the React catalog. A 502 or TLS error is a failing gate; do not spoof tokens or bypass validation. Keycloak import runs only when realm is absent; do not delete the IAM database to re-import a live realm.

## 2. Provision two isolated Keycloak actors (admin action)

Run these commands **inside the Keycloak container**, using an interactive shell. The initial admin credentials come from the private environment; never commit them. Example commands with illustrative actor usernames, not actual credentials:

```bash
docker compose --env-file deploy/staging/private.env -f deploy/staging/compose.yaml exec keycloak bash
/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080/identity \
  --realm master --user '<YOUR_ADMIN_USER>'
# Enter the admin password when prompted, or use a secure private operator method.
# Create enabled users with UNIQUE strong temporary passwords using the admin console/CLI.
# Do not use a public unauthenticated self-registration endpoint.
# Retrieve UUIDs from the trusted realm administration API:
/opt/keycloak/bin/kcadm.sh get users -r zakaz-staging -q username=staging-buyer --fields id,username
/opt/keycloak/bin/kcadm.sh get users -r zakaz-staging -q username=staging-seller --fields id,username
exit
```

The UUIDs returned as user `id` are the immutable `sub` values. Register passwords/verification in Keycloak under approved IAM policy. Do NOT substitute buyer/seller names, phone numbers or client-supplied UUIDs as trusted identity.

## 3. Create synthetic ZAKAZ database fixture

This step uses a privileged **private staging** DB connection to provision the trust links. It is deliberately outside public HTTP and **fails on repeated execution**; use a new empty staging DB for each qualification.

```bash
export STAGING_BUYER_SUB='<KEYCLOAK_BUYER_SUB_UUID>'
export STAGING_SELLER_SUB='<KEYCLOAK_SELLER_SUB_UUID>'
bash deploy/staging/seed-fixture.sh
cat deploy/staging/evidence/fixture.env
```

The fixture includes a DRAFT listing, canonical product/SKU (PCS), price 125000.50 KZT, one synthetic pickup location, `on_hand=10`, seller admin membership and buyer link. The seller must publish the listing through its authorized HTTP API during the test. The seed does NOT auto-create an order or reserve inventory.

## 4. Obtain **real** signed buyer and seller access tokens

Run the loopback native Authorization Code + S256 PKCE helper twice, sign in as the corresponding Keycloak actor in the browser; never use password grant.

```bash
mkdir -p deploy/staging/evidence
chmod 700 deploy/staging/evidence
python3 deploy/staging/oidc-pkce-login.py \
  --domain <STAGING_DOMAIN> --role buyer --out deploy/staging/evidence/buyer.token
python3 deploy/staging/oidc-pkce-login.py \
  --domain <STAGING_DOMAIN> --role seller --out deploy/staging/evidence/seller.token
```

This writes bearer tokens using mode 0600; never print or commit them. Re-authenticate if expired; delete old token files before repeating. Client scopes and `aud` must be issued by Keycloak, not added to fabricated JWT payloads.

## 5. Run the end-to-end HTTP scenario and verify the database

```bash
export STAGING_BASE_URL='https://<STAGING_DOMAIN>'
export STAGING_FIXTURE_FILE=deploy/staging/evidence/fixture.env
export STAGING_BUYER_TOKEN_FILE=deploy/staging/evidence/buyer.token
export STAGING_SELLER_TOKEN_FILE=deploy/staging/evidence/seller.token
export STAGING_ORDER_ID_FILE=deploy/staging/evidence/order-id.txt
node deploy/staging/e2e-real-oidc.mjs
```

The test asserts: DRAFT not public → seller JWT publishes → public catalog/QR exposes only safe fields → anonymous order denied 401 → buyer JWT creates demand → seller JWT submits immutable all-in quote → seller cannot read buyer's bid list → buyer accepts exactly once (idempotency replay) → order PLACED → seller confirmation (including safe retry) → order CONFIRMED → corrupted JWT denied 401. It does **not** claim payment, delivery, fulfillment or a real mobile device test.

After reading the generated `order-id.txt`, run the database qualification with the actual order ID:

```bash
OID=$(cat deploy/staging/evidence/order-id.txt)
docker compose --env-file deploy/staging/private.env -f deploy/staging/compose.yaml exec -T app-db \
  psql -v ON_ERROR_STOP=1 -U zakaz -d zakaz -v "order_id=$OID" \
  < deploy/staging/check-order.sql
```

The SQL raises an exception unless one CONFIRMED order has exactly one RESERVED line, matching immutable movement, one `ORDER_CONFIRMED` Outbox event and no negative/oversold balance. Capture exact SHA, UTC timestamp, CLI output, DB query results and Keycloak issuer claim **without exposing access tokens**.

## Remaining release blockers

1. **Independent S2 Issue #3:** authorized reviewer must verify final exact SHA, IdP client scope and audience mapper, PKCE and user lifecycle, known proxy/forwarded-IP trust, TLS, legal data retention, migration/backup rehearsal, and permissions. No approval is implied here.
2. **Browser E2E:** live SPA login/logout/refresh/retry and seller-side real UI still require Playwright or equivalent qualification against staging. Current script proves server HTTP flow with real IdP tokens, not UI clicks.
3. **Flutter:** generate native runner files, configure appAuthRedirectScheme / iOS URL scheme, run signed-device/AppAuth UAT; previous `flutter analyze/test` is not device acceptance.
4. **Performance:** 5000 distinct paid? **No payment**; only PLACED confirmed order fixtures via legitimate seller membership. Run isolated k6 and post-run integrity scripts after security gate; never infer 5000 capacity from a single-transaction test.
5. **Operations:** external Outbox sink, metrics/alerts, controlled DLQ replay, lead key rotation, restore drills and production-specific capacity controls.

**Stop if real OIDC issuer/audience cannot be validated, if staging is accessible without HTTPS, or if any post-run invariant fails.** Do not merge PR #2 to `main` until independent approvals and E2E evidence exist.
