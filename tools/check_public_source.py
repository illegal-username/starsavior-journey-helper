#!/usr/bin/env python3
"""Fail when private release material is present in the public source tree."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRECTORIES = {".git", ".gradle", ".idea", "build"}
PRIVATE_KEY_SUFFIXES = {".jks", ".keystore", ".p12"}
TEXT_SUFFIXES = {
    ".gradle", ".java", ".json", ".kt", ".kts", ".md", ".mjs", ".properties",
    ".py", ".txt", ".toml", ".xml", ".yaml", ".yml",
}
PRODUCTION_DB = Path("app/src/main/assets/journey_choices.json")
LEGACY_APP_ID = ".".join(("dev", "starjourney", "overlay"))
LEGACY_KEY_NAME = "star-journey" + "-dev"

LITERAL_SECRET_PATTERNS = (
    re.compile(r"\bstorePassword\s+['\"][^'\"]+['\"]"),
    re.compile(r"\bkeyPassword\s+['\"][^'\"]+['\"]"),
)


def skipped(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    return any(part in SKIP_DIRECTORIES for part in relative.parts)


def main() -> int:
    failures: list[str] = []

    for path in ROOT.rglob("*"):
        if not path.is_file() or skipped(path):
            continue
        relative = path.relative_to(ROOT)

        if relative == PRODUCTION_DB:
            failures.append(f"production database is present: {relative}")
        if path.name == "keystore.properties":
            failures.append(f"local signing properties are present: {relative}")
        if path.suffix.lower() in PRIVATE_KEY_SUFFIXES:
            failures.append(f"private key container is present: {relative}")

        if path.suffix.lower() not in TEXT_SUFFIXES or path.name.endswith(".example"):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if LEGACY_APP_ID in content:
            failures.append(f"legacy application ID remains in build source: {relative}")
        if LEGACY_KEY_NAME in content:
            failures.append(f"legacy signing key reference remains: {relative}")
        for pattern in LITERAL_SECRET_PATTERNS:
            if pattern.search(content):
                failures.append(f"literal signing password remains: {relative}")
                break

    if failures:
        print("Public source check failed:", file=sys.stderr)
        for failure in sorted(set(failures)):
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Public source check passed: no production DB, private key container, or literal signing password found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
