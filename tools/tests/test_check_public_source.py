import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_public_source as check


class PublicSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.root_patch = patch.object(check, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def write(self, name, content="fixture"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def run_check(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            result = check.main()
        return result, output.getvalue()

    def test_prunes_build_and_sdk_before_descent(self):
        self.write(".gradle/sdk/secret.jks")
        self.write("app/build/generated/journey_choices.json")
        expected = self.write("app/src/main/Example.java")
        with patch.object(check.os, "scandir", wraps=check.os.scandir) as scan:
            self.assertEqual([expected], list(check.source_files()))
        visited = [Path(call.args[0]) for call in scan.call_args_list]
        self.assertNotIn(self.root / ".gradle", visited)
        self.assertNotIn(self.root / "app/build", visited)

    def test_untracked_key_and_android_database_still_fail(self):
        self.write("signing/release.jks")
        self.write("app/src/internal/assets/journey_choices.json", "{}")
        self.write("app/src/main/assets/journey_choices.example.json", "{}")
        result, output = self.run_check()
        self.assertEqual(1, result)
        self.assertIn("private key container", output)
        self.assertIn("production database", output)
        self.assertNotIn("journey_choices.example.json", output)

    def test_public_markdown_patterns(self):
        cases = [
            ("local drive path", "Z:" + "/sample/readme.md"),
            ("local home path", "/" + "Users/sample/readme.md"),
            ("local home path", "/" + "home/sample/readme.md"),
            ("local file URI", "file" + ":///sample/readme.md"),
            ("private share link", "https://quickshare" + ".samsungcloud.com/example"),
        ]
        for description, value in cases:
            with self.subTest(description=description, value=value):
                self.write("README.md", value)
                result, output = self.run_check()
                self.assertEqual(1, result)
                self.assertIn(description, output)

    def test_new_publishable_markdown_is_checked_but_local_ignored_notes_are_not(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write(".gitignore", "AGENTS.md\n")
        self.write("AGENTS.md", "file" + ":///local-note")
        self.write("NEW.md", "https://quickshare" + ".samsungcloud.com/example")
        result, output = self.run_check()
        self.assertEqual(1, result)
        self.assertIn("NEW.md", output)
        self.assertNotIn("AGENTS.md", output)

    def test_source_archive_fallback_accepts_relative_documentation(self):
        self.write("README.md", "See app/src/main and tools/check_public_source.py.")
        self.assertEqual(0, self.run_check()[0])

    def test_signing_literals_remain_blocked_without_echoing_the_value(self):
        self.write("app/build.gradle", "store" + "Password 'fixture-only-value'")
        result, output = self.run_check()
        self.assertEqual(1, result)
        self.assertIn("literal signing password", output)
        self.assertNotIn("fixture-only-value", output)


if __name__ == "__main__":
    unittest.main()
