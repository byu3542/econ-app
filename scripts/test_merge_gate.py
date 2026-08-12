#!/usr/bin/env python3
"""Tests for scripts/merge_gate.py.

Usage:
    python3 scripts/test_merge_gate.py

Fixture repos in temp dirs, per the plan's U7 verification: one clean branch,
one with a test edit, one with a moved head. G1/G2 (gradle, badges) are patched
in-process — the module itself carries no skip flag, deliberately, so these
tests monkeypatch rather than pass a bypass that production would then carry.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U7)
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_gate  # noqa: E402

PLAN = """# a unit plan

### U1. Change Foo
**Files:**
- `app/src/main/java/com/economic/dashboard/ui/Foo.kt`
- `app/build.gradle`

**Approach:** whatever.
"""


def git(repo, *args):
    r = subprocess.run(["git"] + list(args), cwd=repo, capture_output=True, text=True)
    assert r.returncode == 0, f"git {' '.join(args)}: {r.stderr}"
    return r.stdout.strip()


def _green(gate_id, name):
    return lambda *a, **k: merge_gate.Gate(gate_id, name, "pass", ["patched green"])


class RepoCase(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.repo = Path(self._td.name)
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.email", "t@t")
        git(self.repo, "config", "user.name", "t")
        self.write(".gitignore", "local.properties\n")
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo\n")
        self.write("scripts/.test_baseline", "0  # baseline\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "base")
        self.base = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "checkout", "-q", "-b", "work")
        self.plan = self.repo / "plan.md"
        self.plan.write_text(PLAN, encoding="utf-8")
        # G1/G2 are build-machine gates; patch green unless a test says otherwise
        self._orig = (merge_gate.gate_g1, merge_gate.gate_g2)
        merge_gate.gate_g1 = _green("G1", "gradlew check exits 0")
        merge_gate.gate_g2 = _green("G2", "check_badges.sh exits 0")
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def tearDown(self):
        merge_gate.gate_g1, merge_gate.gate_g2 = self._orig
        self._td.cleanup()
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def write(self, rel, text):
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def commit_change(self, rel="app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                      text="package com.economic.dashboard.ui\nclass Foo { fun x() {} }\n",
                      msg="work"):
        self.write(rel, text)
        git(self.repo, "add", rel)
        git(self.repo, "commit", "-q", "-m", msg)
        return git(self.repo, "rev-parse", "HEAD")

    def gates(self, head=None):
        head = head or git(self.repo, "rev-parse", "HEAD")
        return merge_gate.run_gates(self.repo, self.base, head, self.plan)


class TestCleanBranch(RepoCase):
    def test_all_green_on_a_clean_planned_change(self):
        self.commit_change()
        gates, ok = self.gates()
        self.assertTrue(ok, [(g.id, g.findings) for g in gates if g.status == "fail"])
        ids = [g.id for g in gates]
        for gid in ("G8", "G7", "G3", "G4", "G5", "G9", "G6", "G2", "G1", "G10"):
            self.assertIn(gid, ids)

    def test_g10_absent_verdict_is_skip_not_fail(self):
        self.commit_change()
        gates, ok = self.gates()
        g10 = next(g for g in gates if g.id == "G10")
        self.assertEqual(g10.status, "skip")
        self.assertTrue(ok)


class TestG8(RepoCase):
    def test_moved_head_fails_and_nothing_later_runs(self):
        head = self.commit_change()
        self.commit_change(text="package com.economic.dashboard.ui\nclass Foo { fun y() {} }\n",
                           msg="another session")
        gates, ok = self.gates(head=head)
        self.assertFalse(ok)
        self.assertEqual(gates[0].id, "G8")
        self.assertEqual(gates[0].status, "fail")
        self.assertNotIn("G1", [g.id for g in gates])  # expensive gates never ran


class TestG7(RepoCase):
    def test_running_on_main_fails(self):
        self.commit_change()
        git(self.repo, "checkout", "-q", "main")
        git(self.repo, "merge", "-q", "--ff-only", "work")
        head = git(self.repo, "rev-parse", "HEAD")
        gates, ok = merge_gate.run_gates(self.repo, self.base, head, self.plan)
        self.assertFalse(ok)
        g7 = next(g for g in gates if g.id == "G7")
        self.assertEqual(g7.status, "fail")

    def test_amend_BEFORE_base_does_not_fail_g7(self):
        # The dry-run agent hit this as a false positive: an amend that happened
        # before the run's base commit must not count against this run.
        git(self.repo, "checkout", "-q", "main")
        (self.repo / "pre.txt").write_text("x", encoding="utf-8")
        git(self.repo, "add", "pre.txt")
        git(self.repo, "commit", "-q", "-m", "pre-run work")
        git(self.repo, "commit", "-q", "--amend", "-m", "pre-run work, amended")
        self.base = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "checkout", "-q", "-B", "work2")
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo { fun z() {} }\n")
        git(self.repo, "add", "app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        git(self.repo, "commit", "-q", "-m", "clean work after the amend")
        head = git(self.repo, "rev-parse", "HEAD")
        gates, ok = merge_gate.run_gates(self.repo, self.base, head, self.plan)
        g7 = next(g for g in gates if g.id == "G7")
        self.assertEqual(g7.status, "pass", g7.findings)

    def test_amend_in_reflog_fails(self):
        self.commit_change()
        git(self.repo, "commit", "-q", "--amend", "-m", "sneaky rewrite")
        gates, ok = self.gates()
        self.assertFalse(ok)
        g7 = next(g for g in gates if g.id == "G7")
        self.assertTrue(any("amend" in f for f in g7.findings))


class TestDiffShape(RepoCase):
    def test_test_file_edit_fails_g3(self):
        self.commit_change()
        self.commit_change(rel="app/src/test/java/X.kt", text="@Test fun a() {}\n",
                           msg="touch tests")
        gates, ok = self.gates()
        self.assertFalse(ok)
        self.assertEqual(next(g for g in gates if g.id == "G3").status, "fail")


class TestG6(RepoCase):
    def test_unplanned_file_fails_naming_it(self):
        self.commit_change()
        self.commit_change(rel="app/src/main/java/com/economic/dashboard/ui/Rogue.kt",
                           text="class Rogue\n", msg="unplanned")
        gates, ok = self.gates()
        self.assertFalse(ok)
        g6 = next(g for g in gates if g.id == "G6")
        self.assertTrue(any("Rogue.kt" in f for f in g6.findings))

    def test_planned_directory_covers_children(self):
        self.plan.write_text(PLAN.replace(
            "- `app/src/main/java/com/economic/dashboard/ui/Foo.kt`",
            "- `app/src/main/java/com/economic/dashboard/ui/`"), encoding="utf-8")
        self.commit_change()
        gates, ok = self.gates()
        self.assertTrue(ok, [(g.id, g.findings) for g in gates if g.status == "fail"])

    def test_unparseable_files_field_fails_not_passes(self):
        self.plan.write_text("# plan with no Files field\n\njust prose\n",
                             encoding="utf-8")
        self.commit_change()
        gates, ok = self.gates()
        self.assertFalse(ok)
        g6 = next(g for g in gates if g.id == "G6")
        self.assertTrue(any("cannot compute" in f or "refusing" in f
                            for f in g6.findings))

    def test_dash_prefix_in_plan_is_normalised(self):
        self.plan.write_text(PLAN.replace(
            "`app/src/main/java/com/economic/dashboard/ui/Foo.kt`",
            "`dash/app/src/main/java/com/economic/dashboard/ui/Foo.kt`"),
            encoding="utf-8")
        self.commit_change()
        gates, ok = self.gates()
        self.assertTrue(ok, [(g.id, g.findings) for g in gates if g.status == "fail"])


class TestG10(RepoCase):
    def test_reject_verdict_blocks(self):
        head = self.commit_change()
        self.write(f".claude/REVIEW-{head[:12]}.md",
                   "reviewed the diff\n\nVERDICT: reject — off-plan behavior\n")
        gates, ok = self.gates(head=head)
        self.assertFalse(ok)
        self.assertEqual(next(g for g in gates if g.id == "G10").status, "fail")

    def test_accept_verdict_passes(self):
        head = self.commit_change()
        self.write(f".claude/REVIEW-{head[:12]}.md", "VERDICT: accept\n")
        gates, ok = self.gates(head=head)
        self.assertTrue(ok)


class TestMerge(RepoCase):
    def test_merge_refuses_when_a_gate_is_red(self):
        self.commit_change()
        self.commit_change(rel="app/src/main/java/com/economic/dashboard/ui/Rogue.kt",
                           text="class Rogue\n", msg="unplanned")
        head = git(self.repo, "rev-parse", "HEAD")
        rc = merge_gate.main(["--repo", str(self.repo), "--base", self.base,
                              "--head", head, "--plan", str(self.plan), "--merge"])
        self.assertEqual(rc, 1)
        git(self.repo, "checkout", "-q", "main")
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.base)  # unmoved

    def test_merge_is_no_ff_and_leaves_a_merge_commit(self):
        head = self.commit_change()
        rc = merge_gate.main(["--repo", str(self.repo), "--base", self.base,
                              "--head", head, "--plan", str(self.plan),
                              "--merge", "--branch", "work"])
        self.assertEqual(rc, 0)
        git(self.repo, "checkout", "-q", "main")
        parents = git(self.repo, "log", "-1", "--format=%P").split()
        self.assertEqual(len(parents), 2, "expected a two-parent merge commit")

    def test_merge_rechecks_g8_at_the_last_moment(self):
        head = self.commit_change()
        ok, notes = merge_gate.do_merge(self.repo, "0" * 40, "work")
        self.assertFalse(ok)
        self.assertTrue(any("G8" in n for n in notes))


class TestPlanParsing(unittest.TestCase):
    def test_annotations_are_stripped(self):
        paths, errs = merge_gate._parse_plan_files(
            "**Files:**\n- `scripts/os_check.py` (create)\n- `app/build.gradle` (modify)\n")
        self.assertEqual(errs, [])
        self.assertIn("scripts/os_check.py", paths)
        self.assertIn("app/build.gradle", paths)

    def test_files_field_with_no_backticks_errors(self):
        paths, errs = merge_gate._parse_plan_files("**Files:** whatever prose\n")
        self.assertTrue(errs)


if __name__ == "__main__":
    unittest.main(verbosity=2)
