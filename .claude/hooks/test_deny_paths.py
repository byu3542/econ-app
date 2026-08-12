#!/usr/bin/env python3
"""Tests for .claude/hooks/deny_paths.py.

Usage:
    python3 .claude/hooks/test_deny_paths.py

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U4)
"""

from __future__ import annotations

import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deny_paths  # noqa: E402


def d(path, tool="Write"):
    return deny_paths.decide(tool, {"file_path": path})


class TestDenied(unittest.TestCase):
    def setUp(self):
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def tearDown(self):
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def test_test_files_denied(self):
        r = d("app/src/test/java/com/economic/dashboard/AlertValuesTest.kt")
        self.assertIsNotNone(r)
        self.assertIn("reward-hacking", r)

    def test_test_files_allowed_with_env_var(self):
        os.environ["ECON_AGENT_ALLOW_TESTS"] = "1"
        self.assertIsNone(d("app/src/test/java/com/economic/dashboard/X.kt"))

    def test_cannot_widen_its_own_permissions(self):
        self.assertIsNotNone(d(".claude/settings.json"))
        self.assertIsNotNone(d(".claude/hooks/deny_paths.py"))

    def test_cannot_edit_its_own_commit_gate(self):
        self.assertIsNotNone(d(".githooks/pre-commit"))
        self.assertIsNotNone(d(".git/hooks/pre-commit"))
        self.assertIsNotNone(d("scripts/diff_gates.py"))
        self.assertIsNotNone(d("scripts/check_badges.sh"))

    def test_cannot_raise_the_test_baseline(self):
        self.assertIsNotNone(d("scripts/.test_baseline"))

    def test_secrets_denied(self):
        self.assertIsNotNone(d("local.properties"))

    def test_tier_c_denied(self):
        for p in ["econ-os/CLAUDE.md",
                  "econ-os/skills/econ-ux-audit/skill.md",
                  "econ-os/knowledge/frameworks/README.md",
                  "econ-os/knowledge/audience/positioning.md",
                  "econ-os/knowledge/customers/2026-08-12-someone.md",
                  "econ-os/knowledge/usage/2026-08-12-session.md",
                  "econ-os/knowledge/me/README.md"]:
            self.assertIsNotNone(d(p), f"should be denied: {p}")

    def test_frozen_folder_denied(self):
        self.assertIsNotNone(d("ProjectDocs/anything.md"))

    def test_econ_os_scripts_denied(self):
        self.assertIsNotNone(d("econ-os/scripts/os_check.py"))


class TestAllowed(unittest.TestCase):
    def test_app_source_allowed(self):
        self.assertIsNone(d("app/src/main/java/com/economic/dashboard/ui/Foo.kt"))
        self.assertIsNone(d("app/src/main/res/layout/fragment_news.xml"))

    def test_agent_tier_in_econ_os_allowed(self):
        for p in ["econ-os/subprojects/agent-autonomy/execution.md",
                  "econ-os/docs/plans/2026-08-13-001-x-plan.md",
                  "econ-os/docs/solutions/logic-errors/x.md",
                  "econ-os/subprojects/ux-passes/UX_TICKETS_P9.md"]:
            self.assertIsNone(d(p), f"should be allowed: {p}")

    def test_build_gradle_allowed(self):
        self.assertIsNone(d("app/build.gradle"))


class TestPathNormalisation(unittest.TestCase):
    def test_windows_absolute_path_denied(self):
        self.assertIsNotNone(
            d(r"C:\Android App\econ-os\knowledge\frameworks\README.md"))

    def test_windows_absolute_dash_path_denied(self):
        self.assertIsNotNone(
            d(r"C:\Android App\EconomicDashboard\app\src\test\java\X.kt"))

    def test_windows_absolute_dash_path_allowed(self):
        self.assertIsNone(
            d(r"C:\Android App\EconomicDashboard\app\src\main\java\X.kt"))

    def test_posix_absolute_path_denied(self):
        self.assertIsNotNone(d("/home/x/mnt/econ-os/skills/econ-workflow/skill.md"))

    def test_forward_slashed_windows_path_denied(self):
        self.assertIsNotNone(d("C:/Android App/econ-os/CLAUDE.md"))

    def test_a_lookalike_outside_the_denied_dir_is_allowed(self):
        # 'scripts/check_badges.sh.bak' is not the gate itself... but prefix
        # matching intentionally catches it, because renaming the gate to a
        # sibling is a way to neuter it. Assert the deliberate behaviour.
        self.assertIsNotNone(d("scripts/check_badges.sh.bak"))


class TestFailClosed(unittest.TestCase):
    def _run(self, stdin_text):
        old = sys.stdin
        sys.stdin = io.StringIO(stdin_text)
        try:
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                return deny_paths.main()
        finally:
            sys.stdin = old

    def test_unparseable_input_blocks(self):
        self.assertEqual(self._run("not json"), 2)

    def test_denied_path_exits_2(self):
        self.assertEqual(self._run(json.dumps(
            {"tool_name": "Write",
             "tool_input": {"file_path": "app/src/test/X.kt"}})), 2)

    def test_allowed_path_exits_0(self):
        self.assertEqual(self._run(json.dumps(
            {"tool_name": "Write",
             "tool_input": {"file_path": "app/src/main/X.kt"}})), 0)

    def test_no_path_in_input_is_not_ours_to_judge(self):
        self.assertEqual(self._run(json.dumps(
            {"tool_name": "Bash", "tool_input": {"command": "ls"}})), 0)

    def test_emits_deny_json_on_stdout(self):
        old = sys.stdin
        sys.stdin = io.StringIO(json.dumps(
            {"tool_name": "Edit", "tool_input": {"file_path": "local.properties"}}))
        buf = io.StringIO()
        try:
            with redirect_stdout(buf), redirect_stderr(io.StringIO()):
                deny_paths.main()
        finally:
            sys.stdin = old
        payload = json.loads(buf.getvalue())
        self.assertEqual(
            payload["hookSpecificOutput"]["permissionDecision"], "deny")
        self.assertIn("API keys",
                      payload["hookSpecificOutput"]["permissionDecisionReason"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
