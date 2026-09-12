import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify


class VerifyTest(unittest.TestCase):
    def test_failed_step_stops_and_replaces_old_success_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / ".gradle/verification/latest.json"
            report.parent.mkdir(parents=True)
            report.write_text('{"passed": true}', encoding="utf-8")
            with patch.object(verify, "ROOT", root), \
                    patch.object(verify, "fingerprint", return_value="fixture"), \
                    patch.object(verify.subprocess, "check_output", return_value="head\n"), \
                    patch.object(verify.subprocess, "run", return_value=subprocess.CompletedProcess([], 7)) as run, \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(7, verify.main(["--scope", "android"]))
            self.assertEqual(1, run.call_count)
            evidence = json.loads(report.read_text(encoding="utf-8"))
            self.assertFalse(evidence["passed"])
            self.assertEqual(7, evidence["steps"][0]["exitCode"])

    def test_explicit_report_preserves_previous_evidence_on_success_and_failure(self):
        for code in (0, 7):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                old = root / ".gradle/verification/latest.json"
                old.parent.mkdir(parents=True)
                old.write_text('{"passed": true}', encoding="utf-8")
                report = root / "evidence/run.json"
                with patch.object(verify, "ROOT", root), \
                        patch.object(verify, "fingerprint", return_value="fixture"), \
                        patch.object(verify.subprocess, "check_output", return_value="head\n"), \
                        patch.object(verify.subprocess, "run", return_value=subprocess.CompletedProcess([], code)), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(code, verify.main(["--scope", "android", "--report", str(report)]))
                self.assertEqual('{"passed": true}', old.read_text(encoding="utf-8"))
                evidence = json.loads(report.read_text(encoding="utf-8"))
                self.assertEqual(code == 0, evidence["passed"])
                self.assertEqual(code, evidence["steps"][-1]["exitCode"])

    def test_existing_explicit_report_is_rejected_before_any_subprocess(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "existing.json"
            report.write_text("retained evidence", encoding="utf-8")
            with patch.object(verify.subprocess, "run") as run, \
                    patch.object(verify.subprocess, "check_output") as read, \
                    contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit) as error:
                verify.main(["--report", str(report)])
            self.assertEqual(2, error.exception.code)
            self.assertEqual("retained evidence", report.read_text(encoding="utf-8"))
            run.assert_not_called()
            read.assert_not_called()

    def test_plan_has_no_subprocess_or_report_side_effects(self):
        with patch.object(verify.subprocess, "run") as run, \
                patch.object(verify.subprocess, "check_output") as read, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, verify.main(["--scope", "android", "--plan"]))
        run.assert_not_called()
        read.assert_not_called()

    def test_fingerprint_changes_for_new_edited_and_deleted_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "new.py"
            with patch.object(verify, "ROOT", root), \
                    patch.object(verify.subprocess, "check_output", return_value=b"new.py\0"):
                absent = verify.fingerprint()
                source.write_text("first", encoding="utf-8")
                first = verify.fingerprint()
                source.write_text("second", encoding="utf-8")
                second = verify.fingerprint()
                source.unlink()
                self.assertEqual(absent, verify.fingerprint())
        self.assertEqual(3, len({absent, first, second}))


if __name__ == "__main__":
    unittest.main()
