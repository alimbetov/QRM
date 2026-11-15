#!/usr/bin/env python3
"""Interactive Authorization Code + PKCE against real Keycloak; no password grant."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import stat
import urllib.parse
import urllib.request
import webbrowser

parser = argparse.ArgumentParser()
parser.add_argument("--domain", required=True)
parser.add_argument("--role", choices=["buyer", "seller"], required=True)
parser.add_argument("--out", required=True, help="Private bearer-token file (0600)")
args = parser.parse_args()
if not args.domain or any(ch in args.domain for ch in "/:@"):
    parser.error("Provide a DNS host, not a URL")
if not Path(args.out).parent.is_dir():
    parser.error("Create the private output directory first")
issuer = f"https://{args.domain}/identity/realms/zakaz-staging"
client_id = "zakaz-buyer-cli" if args.role == "buyer" else "zakaz-seller-cli"
scopes = ("openid profile deal:write order:read" if args.role == "buyer"
          else "openid profile catalog:write deal:bid order:confirm leads:read")
verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode()
state = secrets.token_urlsafe(24)
result = {}

class Callback(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        if parsed.path != "/callback" or params.get("state", [None])[0] != state:
            self.send_error(400, "Invalid OAuth callback state")
            return
        if "error" in params:
            result["error"] = params["error"][0]
        else:
            result["code"] = params.get("code", [None])[0]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b"Authentication returned to the local terminal. Close this tab.")

    def log_message(self, *_):
        pass

with HTTPServer(("127.0.0.1", 0), Callback) as server:
    server.timeout = 240
    port = server.server_port
    redirect = f"http://127.0.0.1:{port}/callback"
    authorize = issuer + "/protocol/openid-connect/auth?" + urllib.parse.urlencode({
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
        "scope": scopes, "state": state, "code_challenge": challenge,
        "code_challenge_method": "S256", "prompt": "login"
    })
    print(f"Open Keycloak and sign in as the {args.role} test actor.")
    print("Authentication URL:", authorize)
    webbrowser.open(authorize)
    server.handle_request()

if result.get("error"):
    raise SystemExit("Identity provider denied login: " + result["error"])
if not result.get("code"):
    raise SystemExit("No valid PKCE callback was received")
data = urllib.parse.urlencode({
    "grant_type": "authorization_code", "client_id": client_id,
    "code": result["code"], "redirect_uri": redirect, "code_verifier": verifier
}).encode()
req = urllib.request.Request(issuer + "/protocol/openid-connect/token",
                             data=data, headers={"Content-Type": "application/x-www-form-urlencoded"})
with urllib.request.urlopen(req, timeout=20) as response:
    token = json.load(response)
if token.get("token_type", "").lower() != "bearer" or not token.get("access_token"):
    raise SystemExit("Provider did not return a bearer access token")
destination = Path(args.out)
fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, stat.S_IRUSR | stat.S_IWUSR)
with os.fdopen(fd, "w") as output:
    output.write(token["access_token"])
print("Bearer access token stored with permissions 0600:", destination)
print("Never commit, print, or transmit this token outside private staging.")
