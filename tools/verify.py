#!/usr/bin/env python3
"""Run a deliberate validation scope; record evidence, never silently cache a pass."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def git(*arguments: str) -> list[str]:
    return ["git", "-c", f"safe.directory={ROOT.as_posix()}", *arguments]


def fingerprint() -> str:
    paths = subprocess.check_output(
        git("ls-files", "--cached", "--others", "--exclude-standard", "-z"), cwd=ROOT
    ).split(b"\0")
    digest = hashlib.sha256()
    for raw in sorted(set(paths) - {b""}):
        path = ROOT / raw.decode("utf-8")
        digest.update(raw + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b"deleted")
    return digest.hexdigest()


def commands(scope: str, corpus: str | None) -> list[list[str]]:
    result = [
        [sys.executable, "tools/check_public_source.py"],
        [sys.executable, "-m", "unittest", "discover", "-s", "tools/tests", "-v"],
        git("diff", "--check"),
        git("diff", "--cached", "--check"),
    ]
    if scope != "source":
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        local = ROOT / ".gradle/gradlew-codex.cmd"
        if os.name == "nt" and local.is_file():
            wrapper = local
        result.append([str(wrapper), "--no-daemon", "testDebugUnitTest", "lintRelease", "assembleDebug"])
    if scope == "stamina":
        result.append([
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            "tools/run_stamina_regression.ps1", "-AnnotationsOnly", "-CorpusRoot", corpus,
        ])
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("source", "android", "stamina"), default="source")
    parser.add_argument("--corpus-root", default=os.environ.get("STAR_JOURNEY_STAMINA_CORPUS"))
    parser.add_argument("--plan", action="store_true", help="Print commands without running or writing anything.")
    args = parser.parse_args(argv)
    if args.scope == "stamina":
        if os.name != "nt":
            parser.error("The stamina runner requires Windows; use android for public CI.")
        if not args.corpus_root or not (Path(args.corpus_root) / "annotated").is_dir():
            parser.error("Supply --corpus-root with an annotated/ directory, or set STAR_JOURNEY_STAMINA_CORPUS.")
        args.corpus_root = str(Path(args.corpus_root).resolve())
    steps = commands(args.scope, args.corpus_root)
    if args.plan:
        for step in steps:
            print(subprocess.list2cmdline(step))
        return 0

    report = {
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "scope": args.scope,
        "head": subprocess.check_output(git("rev-parse", "HEAD"), cwd=ROOT, text=True).strip(),
        "inputSha256": fingerprint(),
        "passed": False,
        "steps": [],
    }
    exit_code = 1
    report_path = ROOT / ".gradle/verification/latest.json"
    try:
        for step in steps:
            print(f"\n> {subprocess.list2cmdline(step)}", flush=True)
            start = time.monotonic()
            try:
                exit_code = subprocess.run(step, cwd=ROOT, check=False).returncode
            except OSError as error:
                print(str(error), file=sys.stderr)
                exit_code = 1
            report["steps"].append({
                "command": step, "exitCode": exit_code,
                "seconds": round(time.monotonic() - start, 3),
            })
            if exit_code:
                return exit_code
        report["passed"] = True
        return 0
    finally:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"\nVerification {'passed' if report['passed'] else 'failed'}: {report_path}")


if __name__ == "__main__":
    raise SystemExit(main())
