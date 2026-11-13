#!/usr/bin/env python3
"""Operator-only Keycloak SMTP + registration gate. Run inside isolated Docker network.

Uses a short-lived Keycloak admin token via admin-cli credentials in a private
container (never from browser/mobile). No logs of passwords, mail or token.
Registration remains OFF until SMTP test and explicit mailbox proof.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://keycloak:8080/identity"
REALM = "zakaz-staging"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def required(name):
    value = os.environ.get(name, "").strip()
    if not value or value.startswith("REPLACE_"):
        raise ValueError("Missing private setting " + name)
    return value

def http_json(path, method="GET", payload=None, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=20) as response:
            if response.status not in (200, 201, 204):
                raise RuntimeError("Unexpected Keycloak status")
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as exc:
        raise RuntimeError("Keycloak operation rejected with HTTP " + str(exc.code)) from None

def access_token():
    file = os.environ.get("STAGING_ADMIN_TOKEN_FILE")
    if file:
        token = Path(file).read_text().strip()
        if token.count(".") != 2:
            raise ValueError("Invalid private admin token file")
        return token
    # Operator-admin tooling only, inside Docker network. No password grant
    # is enabled for ZAKAZ buyer/seller clients. Prefer token file with admin MFA.
    user = required("KC_ADMIN_USER")
    password = required("KC_ADMIN_PASSWORD")
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "admin-cli",
        "username": user, "password": password
    }).encode("ascii")
    request = urllib.request.Request(
        BASE + "/realms/master/protocol/openid-connect/token",
        data=data, headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST")
    try:
        with OPENER.open(request, timeout=20) as response:
            return json.load(response)["access_token"]
    except urllib.error.HTTPError as exc:
        raise RuntimeError("Operator authentication failed with HTTP " + str(exc.code)) from None

def smtp_settings(env):
    def take(name):
        value = env.get(name, "").strip()
        if not value or value.startswith("REPLACE_"):
            raise ValueError("Missing private setting " + name)
        return value
    host = take("STAGING_SMTP_HOST")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{2,252}", host) or host.endswith(".invalid"):
        raise ValueError("Invalid SMTP host")
    mode = take("STAGING_SMTP_MODE").lower()
    if mode not in ("starttls", "ssl"):
        raise ValueError("SMTP mode must be starttls or ssl; plaintext prohibited")
    port = int(take("STAGING_SMTP_PORT"))
    if port not in (465, 587):
        raise ValueError("Use a TLS-capable SMTP port (465 or 587)")
    if mode == "starttls" and port != 587 or mode == "ssl" and port != 465:
        raise ValueError("SMTP TLS mode and port mismatch")
    sender = take("STAGING_SMTP_FROM")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", sender):
        raise ValueError("Invalid sender")
    return {
        "host": host, "port": str(port), "from": sender,
        "auth": "true", "user": take("STAGING_SMTP_USER"),
        "password": take("STAGING_SMTP_PASSWORD"),
        "starttls": str(mode == "starttls").lower(),
        "ssl": str(mode == "ssl").lower()
    }

def fingerprint(settings):
    # NEVER include SMTP password in public evidence or digest.
    return hashlib.sha256(json.dumps(
        {k: v for k, v in settings.items() if k not in ("password", "user")},
        sort_keys=True).encode()).hexdigest()

def update_realm(token, desired):
    current = http_json("/admin/realms/" + REALM, token=token)
    if current.get("realm") != REALM:
        raise RuntimeError("Refusing to mutate another realm")
    # Preserve other realm settings; never turn off email proof.
    current.update(desired)
    http_json("/admin/realms/" + REALM, "PUT", current, token)
    return http_json("/admin/realms/" + REALM, token=token)

def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("configure", "enable", "disable", "status"):
        raise ValueError("Usage: keycloak_smtp_ops.py configure|enable|disable|status")
    mode = sys.argv[1]
    token = access_token()
    realm = http_json("/admin/realms/" + REALM, token=token)
    if realm.get("realm") != REALM:
        raise RuntimeError("Unexpected IAM realm")
    if mode == "configure":
        smtp = smtp_settings(os.environ)
        updated = update_realm(token, {
            "smtpServer": smtp, "verifyEmail": True, "registrationAllowed": False
        })
        if updated.get("registrationAllowed") is not False:
            raise RuntimeError("Registration was not kept disabled")
        # Run real Keycloak SMTP connection test. It may deliver a test email
        # to the currently authenticated admin; verify admin email in Keycloak.
        http_json("/admin/realms/" + REALM + "/testSMTPConnection",
                  "POST", {}, token)
        proof_file = Path("/evidence/smtp-test-proof.json")
        proof_file.parent.mkdir(parents=True, exist_ok=True)
        proof = {
            "realm": REALM, "smtpConfigDigest": fingerprint(smtp),
            "testedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat()
        }
        with proof_file.open("x") as handle:
            json.dump(proof, handle)
        proof_file.chmod(0o600)
        print("SMTP test succeeded; REGISTRATION IS STILL DISABLED.")
        print("Check that an actual test message reached the approved mailbox before enable.")
    elif mode == "enable":
        if required("STAGING_ALLOW_SELF_REGISTRATION") != "YES_AFTER_REAL_MAILBOX_PROOF":
            raise ValueError("Explicit operator approval required")
        if not realm.get("smtpServer") or realm.get("verifyEmail") is not True:
            raise RuntimeError("No verified-email SMTP configuration")
        proof = json.loads(Path("/evidence/smtp-test-proof.json").read_text())
        elapsed = dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(proof["testedAtUtc"])
        if (proof.get("realm") != REALM or elapsed.total_seconds() < 0
                or elapsed.total_seconds() > 3600
                or proof.get("smtpConfigDigest") != fingerprint(realm["smtpServer"])):
            raise RuntimeError("Expired/mismatched SMTP test evidence")
        latest = update_realm(token, {"registrationAllowed": True, "verifyEmail": True})
        if not latest.get("registrationAllowed") or not latest.get("verifyEmail"):
            raise RuntimeError("Unable to confirm registration and email verification")
        print("Staging self-registration enabled with mandatory Keycloak email verification.")
    elif mode == "disable":
        updated = update_realm(token, {"registrationAllowed": False, "verifyEmail": True})
        if updated.get("registrationAllowed"):
            raise RuntimeError("Could not close self-registration")
        print("Staging self-registration disabled.")
    else:
        print("realm:", REALM)
        print("verifyEmail:", bool(realm.get("verifyEmail")))
        print("registrationAllowed:", bool(realm.get("registrationAllowed")))
        print("smtpConfigured:", bool(realm.get("smtpServer", {}).get("host")))
        # Do not expose encrypted/saved SMTP credentials from admin API.

if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, KeyError, OSError) as exc:
        # Keycloak may echo secrets in remote errors; report class only.
        print("FAIL: SMTP registration gate did not pass. Registration not qualified.",
              file=sys.stderr)
        sys.exit(1)
