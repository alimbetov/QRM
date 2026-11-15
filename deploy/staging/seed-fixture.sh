#!/usr/bin/env bash
# Explicitly admin-provisioned identity subjects; never trust a client-provided seller ID.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${STAGING_BUYER_SUB:?Keycloak user's immutable sub UUID required}"
: "${STAGING_SELLER_SUB:?Keycloak seller user's immutable sub UUID required}"
envfile="${STAGING_ENV_FILE:-deploy/staging/private.env}"
test -f "$envfile" || { echo "Missing private env: $envfile" >&2; exit 1; }
domain="$(sed -n 's/^STAGING_DOMAIN=//p' "$envfile" | tail -n1)"
[[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "Invalid staging hostname" >&2; exit 1; }
issuer="https://$domain/identity/realms/zakaz-staging"
mkdir -p deploy/staging/evidence
umask 077
# Deliberately fail if the deterministic fixture category already exists.
docker compose --env-file "$envfile" -f deploy/staging/compose.yaml exec -T app-db \
  psql -v ON_ERROR_STOP=1 -U zakaz -d zakaz \
    -v "issuer=$issuer" -v "buyer_sub=$STAGING_BUYER_SUB" \
    -v "seller_sub=$STAGING_SELLER_SUB" \
  < deploy/staging/seed.sql | tee deploy/staging/evidence/fixture.env
echo "Saved synthetic fixture IDs (no token/password) under ignored evidence directory."
