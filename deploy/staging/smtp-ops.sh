#!/usr/bin/env bash
# Operator-only staging tool. Do not run against public networks or production.
set -euo pipefail
cd "$(dirname "$0")/../.."
envfile="${STAGING_ENV_FILE:-deploy/staging/private.env}"
test -f "$envfile" || { echo "Missing private staging env" >&2; exit 1; }
mkdir -p deploy/staging/evidence
chmod 700 deploy/staging/evidence
case "${1:-}" in configure|enable|disable|status) ;; *)
  echo "Usage: bash deploy/staging/smtp-ops.sh configure|enable|disable|status" >&2
  exit 2;; esac
# Admin credential never sent via public Keycloak proxy; only isolated Docker
# network. Container has no host port. Private config is never committed.
docker run --rm --network zakaz-staging_private \
  --env-file "$envfile" \
  -v "$PWD/deploy/staging/keycloak_smtp_ops.py:/work/keycloak_smtp_ops.py:ro" \
  -v "$PWD/deploy/staging/evidence:/evidence" \
  python:3.13-alpine python /work/keycloak_smtp_ops.py "$1"
