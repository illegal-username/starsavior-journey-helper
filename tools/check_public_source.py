#!/usr/bin/env python3
"""Fail when private release material is present in the public source tree."""

from __future__ import annotations

import re
import subprocess
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
PRIVATE_WORKSPACE_MARKER = "starsavior-journey-helper" + "-private"
PRIVATE_FILENAME_MARKER = "KEEP" + "_PRIVATE"

LITERAL_SECRET_PATTERNS = (
    re.compile(r"\bstorePassword\s+['\"][^'\"]+['\"]"),
    re.compile(r"\bkeyPassword\s+['\"][^'\"]+['\"]"),
)
TRACKED_MARKDOWN_PRIVATE_PATTERNS = (
    ("local drive path", re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\\\/]")),
    ("local home path", re.compile(r"(?i)(?<![A-Za-z0-9])/(?:Users|home)/[^/\\s`\"')]+/")),
    ("local file URI", re.compile(r"(?i)\\bfile://")),
    ("private workspace reference", re.compile(
        rf"(?i){re.escape(PRIVATE_WORKSPACE_MARKER)}|{PRIVATE_FILENAME_MARKER}"
    )),
    ("private share link", re.compile(r"(?i)quickshare\\.samsungcloud\\.com")),
)


def skipped(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    return any(part in SKIP_DIRECTORIES for part in relative.parts)


def tracked_markdown_files() -> list[Path]:
    """Return only Markdown files that can be published from this checkout."""
    command = [
        "git", "-c", f"safe.directory={ROOT.as_posix()}",
        "ls-files", "-z", "--", "*.md",
    ]
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=False,
        )
    except (OSError, subprocess.CalledProcessError):
        return [path for path in ROOT.rglob("*.md") if not skipped(path)]
    return [ROOT / Path(raw.decode("utf-8"))
            for raw in completed.stdout.split(b"\0") if raw]


def main() -> int:
    failures: list[str] = []

    for path in tracked_markdown_files():
        relative = path.relative_to(ROOT)
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for description, pattern in TRACKED_MARKDOWN_PRIVATE_PATTERNS:
            if pattern.search(content):
                failures.append(
                    f"{description} is present in published Markdown: {relative}"
                )

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

    print("Public source check passed: no private release material or local paths found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
