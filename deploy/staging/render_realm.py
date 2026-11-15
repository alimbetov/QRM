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
root = Path(os.environ.get("STAGING_TEMPLATE_DIR", "/staging"))
out = Path(os.environ.get("STAGING_OUTPUT_DIR", "/out"))
realm = json.loads((root / "realm-template.json").read_text().replace("__STAGING_DOMAIN__", host))
out.mkdir(parents=True, exist_ok=True)
(out / "realm.json").write_text(json.dumps(realm, indent=2) + "\n")
print("Prepared Keycloak realm configuration for trusted HTTPS origin")
