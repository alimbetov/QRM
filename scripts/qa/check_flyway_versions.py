"""Fail CI before Flyway if two immutable migrations reuse one version.

Flyway PostgreSQL version uniqueness is a release invariant, not just a
filename style rule. Gaps are allowed; renumbering already-applied database
migrations requires a controlled migration plan.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

MIGRATION = re.compile(r"^V([0-9]+)__[A-Za-z0-9][A-Za-z0-9_]*\.sql$")


def validate(directory: Path) -> dict[int, str]:
    if not directory.is_dir():
        raise ValueError(f"Migration directory missing: {directory}")
    versions: dict[int, str] = {}
    for file in sorted(directory.glob("V*.sql")):
        match = MIGRATION.fullmatch(file.name)
        if match is None:
            raise ValueError(f"Invalid versioned Flyway migration filename: {file.name}")
        number = int(match.group(1))
        if number < 1:
            raise ValueError(f"Migration version must be positive: {file.name}")
        previous = versions.get(number)
        if previous is not None:
            raise ValueError(
                f"Duplicate Flyway migration V{number}: {previous} and {file.name}"
            )
        versions[number] = file.name
    if not versions:
        raise ValueError(f"No versioned Flyway migrations: {directory}")
    return versions


def main() -> int:
    default = Path(__file__).resolve().parents[2] / (
        "backend/src/main/resources/db/migration"
    )
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else default
    try:
        versions = validate(directory)
    except ValueError as error:
        print(f"FAIL Flyway migration uniqueness: {error}", file=sys.stderr)
        return 1
    print(f"PASS {len(versions)} unique Flyway migration versions: "
          f"V{min(versions)}...V{max(versions)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
