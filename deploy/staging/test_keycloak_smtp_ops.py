"""Offline tests for operator-only SMTP safety gates; no Keycloak/SMTP network."""
import unittest
from keycloak_smtp_ops import smtp_settings, fingerprint

def fixture():
    return {
        "STAGING_SMTP_HOST": "smtp.operator.example",
        "STAGING_SMTP_PORT": "587",
        "STAGING_SMTP_MODE": "starttls",
        "STAGING_SMTP_FROM": "no-reply@operator.example",
        "STAGING_SMTP_USER": "staging-user",
        "STAGING_SMTP_PASSWORD": "not-a-real-password",
    }

class SafetyTests(unittest.TestCase):
    def test_strong_tls_required(self):
        config = smtp_settings(fixture())
        self.assertEqual(config["starttls"], "true")
        self.assertEqual(config["ssl"], "false")
        self.assertEqual(config["auth"], "true")
        for bad in ("none", "opportunistic", "plain"):
            env = fixture()
            env["STAGING_SMTP_MODE"] = bad
            with self.assertRaises(ValueError):
                smtp_settings(env)

    def test_tls_mode_port_pair_must_match(self):
        env = fixture()
        env["STAGING_SMTP_MODE"] = "ssl"
        with self.assertRaises(ValueError):
            smtp_settings(env)
        env["STAGING_SMTP_PORT"] = "465"
        self.assertEqual(smtp_settings(env)["ssl"], "true")

    def test_missing_secret_and_bad_sender_fail_closed(self):
        env = fixture()
        env["STAGING_SMTP_PASSWORD"] = ""
        with self.assertRaises(ValueError):
            smtp_settings(env)
        env = fixture()
        env["STAGING_SMTP_FROM"] = "sender-without-domain"
        with self.assertRaises(ValueError):
            smtp_settings(env)

    def test_evidence_never_contains_password_or_username(self):
        first = smtp_settings(fixture())
        changed = dict(first, password="new-unrelated-password", user="different-login")
        self.assertEqual(fingerprint(first), fingerprint(changed))
        self.assertNotIn("not-a-real-password", fingerprint(first))
        changed["host"] = "smtp.other.example"
        self.assertNotEqual(fingerprint(first), fingerprint(changed))

if __name__ == "__main__":
    unittest.main()
