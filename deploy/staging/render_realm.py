"""Render a staging-only Keycloak import without a wildcard web redirect URI."""
import json
import os
from pathlib import Path
import re

host = os.environ["STAGING_DOMAIN"].strip().lower()
if not re.fullmatch(r"(?=.{4,253}$)[a-z0-9][a-z0-9.-]*[a-z0-9]", host):
    raise SystemExit("Invalid STAGING_DOMAIN")
if host.endswith(".invalid") or host == "localhost":
    raise SystemExit("Use a real TLS-capable staging DNS name")
root = Path("/staging/realm-template.json").read_text()
realm = json.loads(root.replace("__STAGING_DOMAIN__", host))
Path("/out/realm.json").write_text(json.dumps(realm, indent=2) + "\n")
print("Prepared Keycloak realm configuration for trusted HTTPS origin")
