"""Regression for the duplicate-V28 catalog/ML migration collision."""
import tempfile
import unittest
from pathlib import Path

from check_flyway_versions import validate


class FlywayVersionContractTest(unittest.TestCase):
    def test_distinct_catalog_and_ml_versions_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "V28__catalog_r1a.sql").write_text("-- catalog\n")
            (root / "V29__ml_invitation_parity.sql").write_text("-- ML\n")
            self.assertEqual(set(validate(root)), {28, 29})

    def test_duplicate_version_is_release_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "V28__catalog_r1a.sql").write_text("-- catalog\n")
            (root / "V28__ml_invitation_parity.sql").write_text("-- ML\n")
            with self.assertRaisesRegex(ValueError, "Duplicate Flyway migration V28"):
                validate(root)

    def test_gaps_are_legal_and_not_renumbered(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "V1__baseline.sql").write_text("-- start\n")
            (root / "V29__parity.sql").write_text("-- later\n")
            self.assertEqual(set(validate(root)), {1, 29})

    def test_malformed_migration_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Vx__wrong.sql").write_text("-- malformed\n")
            with self.assertRaisesRegex(ValueError, "Invalid versioned Flyway"):
                validate(root)


if __name__ == "__main__":
    unittest.main()
