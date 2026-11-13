#!/usr/bin/env python3
"""Fail-closed public HTTPS/issuer/catalog smoke test; no secrets or JWT needed."""
import json
import os
import ssl
from urllib.request import Request, urlopen

domain = os.environ.get("STAGING_DOMAIN", "").strip()
if not domain or "/" in domain or ":" in domain:
    raise SystemExit("Set STAGING_DOMAIN to the DNS host (no scheme or path)")
base = "https://" + domain
issuer = base + "/identity/realms/zakaz-staging"

def get_json(url):
    req = Request(url, headers={"Accept": "application/json"})
    # urllib default verification uses system CA; never disable verification.
    with urlopen(req, timeout=15, context=ssl.create_default_context()) as response:
        if response.status != 200:
            raise AssertionError("Unexpected HTTP status")
        return json.load(response)

discovery = get_json(issuer + "/.well-known/openid-configuration")
assert discovery.get("issuer") == issuer, "Issuer mismatch (proxy/hostname)"
jwks_uri = discovery.get("jwks_uri")
assert isinstance(jwks_uri, str) and jwks_uri.startswith(issuer + "/"), "Untrusted JWKS URI"
assert any(key.get("kty") == "RSA" and key.get("use", "sig") == "sig"
           for key in get_json(jwks_uri).get("keys", [])), "No real IdP RSA signing key"
catalog = get_json(base + "/api/v1/public/catalog?page=0&size=12")
assert isinstance(catalog.get("items"), list) and isinstance(catalog.get("total"), int)
assert catalog["total"] >= 0
print("PASS: verified HTTPS issuer + JWKS + live public catalog endpoint")
print("This is configuration smoke testing, not authenticated E2E or S2 approval.")
