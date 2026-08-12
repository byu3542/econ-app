#!/usr/bin/env python3
"""Tests for scripts/diff_gates.py.

Usage:
    python3 scripts/test_diff_gates.py

Each test builds a throwaway git repo in a temp dir, stages a specific kind of
change, and asserts the right gate fires. Nothing here touches the real repo.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U5)
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import diff_gates  # noqa: E402

TEST_SRC = """package com.economic.dashboard

import org.junit.Test

class SampleTest {
    @Test
    fun one() {}
    @Test
    fun two() {}
}
"""


def git(repo, *args):
    r = subprocess.run(["git"] + list(args), cwd=repo, capture_output=True, text=True)
    assert r.returncode == 0, f"git {' '.join(args)} failed: {r.stderr}"
    return r.stdout


class RepoCase(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.repo = Path(self._td.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@t")
        git(self.repo, "config", "user.name", "t")
        self.write(".gitignore", "local.properties\nbuild/\n")
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo\n")
        self.write("app/src/test/java/com/economic/dashboard/SampleTest.kt", TEST_SRC)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "base")
        self.write("scripts/.test_baseline", "2  # baseline\n")
        git(self.repo, "add", "scripts/.test_baseline")
        git(self.repo, "commit", "-q", "-m", "baseline")
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def tearDown(self):
        self._td.cleanup()
        os.environ.pop("ECON_AGENT_ALLOW_TESTS", None)

    def write(self, rel, text):
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def stage(self, *paths):
        git(self.repo, "add", *paths)

    def gates(self, rng=None):
        results, err = diff_gates.run(self.repo, rng)
        self.assertIsNone(err, err)
        return {r["id"]: r for r in results}


class TestG3(RepoCase):
    def test_editing_a_test_file_is_rejected(self):
        self.write("app/src/test/java/com/economic/dashboard/SampleTest.kt",
                   TEST_SRC + "\n// tweak\n")
        self.stage("app/src/test/java/com/economic/dashboard/SampleTest.kt")
        g = self.gates()["G3"]
        self.assertEqual(g["status"], "fail")
        self.assertTrue(any("SampleTest.kt" in f for f in g["findings"]))

    def test_env_var_lifts_the_block(self):
        os.environ["ECON_AGENT_ALLOW_TESTS"] = "1"
        self.write("app/src/test/java/com/economic/dashboard/SampleTest.kt",
                   TEST_SRC + "\n// tweak\n")
        self.stage("app/src/test/java/com/economic/dashboard/SampleTest.kt")
        g = self.gates()["G3"]
        self.assertEqual(g["status"], "skip")
        self.assertTrue(any("permitted" in f for f in g["findings"]))

    def test_ordinary_source_change_passes(self):
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo { fun x() {} }\n")
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        self.assertEqual(self.gates()["G3"]["status"], "pass")

    def test_a_new_test_file_is_also_rejected(self):
        self.write("app/src/test/java/com/economic/dashboard/NewTest.kt", TEST_SRC)
        self.stage("app/src/test/java/com/economic/dashboard/NewTest.kt")
        self.assertEqual(self.gates()["G3"]["status"], "fail")


class TestG4(RepoCase):
    def test_deleting_a_test_is_rejected(self):
        os.environ["ECON_AGENT_ALLOW_TESTS"] = "1"   # isolate G4 from G3
        self.write("app/src/test/java/com/economic/dashboard/SampleTest.kt",
                   TEST_SRC.replace("    @Test\n    fun two() {}\n", ""))
        self.stage("app/src/test/java/com/economic/dashboard/SampleTest.kt")
        g = self.gates()["G4"]
        self.assertEqual(g["status"], "fail")
        self.assertTrue(any("dropped" in f for f in g["findings"]))

    def test_adding_a_test_passes(self):
        os.environ["ECON_AGENT_ALLOW_TESTS"] = "1"
        self.write("app/src/test/java/com/economic/dashboard/SampleTest.kt",
                   TEST_SRC.replace("}\n", "    @Test\n    fun three() {}\n}\n", 1))
        self.stage("app/src/test/java/com/economic/dashboard/SampleTest.kt")
        self.assertEqual(self.gates()["G4"]["status"], "pass")

    def test_missing_baseline_reports_skip_not_pass(self):
        (self.repo / "scripts" / ".test_baseline").unlink()
        g = self.gates()["G4"]
        self.assertEqual(g["status"], "skip")

    def test_counts_java_tests_too(self):
        self.write("app/src/test/java/com/economic/dashboard/OldTest.java",
                   "class OldTest {\n  @Test\n  public void a() {}\n}\n")
        self.assertEqual(diff_gates.count_tests(self.repo), 3)


class TestG5(RepoCase):
    def _add_line(self, line):
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\n" + line + "\nclass Foo\n")
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")

    def test_suppress_rejected(self):
        self._add_line('@Suppress("UNCHECKED_CAST")')
        g = self.gates()["G5"]
        self.assertEqual(g["status"], "fail")
        self.assertTrue(any("@Suppress" in f for f in g["findings"]))

    def test_noinspection_rejected(self):
        self._add_line("// noinspection unused")
        self.assertEqual(self.gates()["G5"]["status"], "fail")

    def test_ignore_rejected(self):
        self._add_line("@Ignore")
        self.assertEqual(self.gates()["G5"]["status"], "fail")

    def test_abort_on_error_false_rejected(self):
        self.write("app/build.gradle", "android {\n  lint { abortOnError false }\n}\n")
        self.stage("app/build.gradle")
        self.assertEqual(self.gates()["G5"]["status"], "fail")

    def test_tools_ignore_rejected(self):
        self.write("app/src/main/res/layout/a.xml",
                   '<View tools:ignore="ContentDescription" />\n')
        self.stage("app/src/main/res/layout/a.xml")
        self.assertEqual(self.gates()["G5"]["status"], "fail")

    def test_gate_scripts_do_not_block_themselves(self):
        # The gate must not fire on its own source: a @Suppress regex inside a
        # .py file is a pattern, not a suppression. This commit was blocked by
        # itself on 2026-08-12; this test keeps that fixed.
        self.write("scripts/other_tool.py",
                   'PATTERNS = [r"@Suppress", r"//\\s*noinspection"]\n')
        self.stage("scripts/other_tool.py")
        self.assertEqual(self.gates()["G5"]["status"], "pass")

    def test_removing_a_suppression_is_fine(self):
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   'package com.economic.dashboard.ui\n@Suppress("X")\nclass Foo\n')
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        git(self.repo, "commit", "-q", "-m", "add suppression")
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo\n")
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        self.assertEqual(self.gates()["G5"]["status"], "pass")

    def test_clean_change_passes(self):
        self._add_line("// an ordinary comment")
        self.assertEqual(self.gates()["G5"]["status"], "pass")


class TestG9(RepoCase):
    def test_staging_local_properties_rejected(self):
        self.write("local.properties", "sdk.dir=C\\:/Android/Sdk\n")
        git(self.repo, "add", "-f", "local.properties")
        g = self.gates()["G9"]
        self.assertEqual(g["status"], "fail")
        self.assertTrue(any("local.properties" in f for f in g["findings"]))

    def test_removing_it_from_gitignore_rejected(self):
        self.write(".gitignore", "build/\n")
        self.stage(".gitignore")
        g = self.gates()["G9"]
        self.assertEqual(g["status"], "fail")
        self.assertTrue(any("no longer ignores" in f for f in g["findings"]))

    def test_anthropic_key_rejected(self):
        # assembled at runtime so this test file never contains a key-shaped
        # string at rest — G9 scans every file, including this one
        fake = "sk-" + "ant-" + "api03-" + "abcdefghijklmnop"
        self.write("app/src/main/java/com/economic/dashboard/api/K.kt",
                   f'val k = "{fake}"\n')
        self.stage("app/src/main/java/com/economic/dashboard/api/K.kt")
        self.assertEqual(self.gates()["G9"]["status"], "fail")

    def test_generic_credential_rejected(self):
        fake = "A1b2C3d4" + "E5f6G7h8" + "J9k0"
        self.write("app/src/main/java/com/economic/dashboard/api/K.kt",
                   f'val ' + 'api' + f'Key = "{fake}"\n')
        self.stage("app/src/main/java/com/economic/dashboard/api/K.kt")
        self.assertEqual(self.gates()["G9"]["status"], "fail")

    def test_buildconfigfield_naming_a_key_is_not_a_secret(self):
        self.write("app/build.gradle",
                   'buildConfigField "String", "FRED_API_KEY", fredKey\n')
        self.stage("app/build.gradle")
        self.assertEqual(self.gates()["G9"]["status"], "pass")

    def test_deleting_a_line_holding_a_secret_is_not_a_failure(self):
        fake = "sk-" + "ant-" + "api03-" + "abcdefghijklmnop"
        self.write("app/src/main/java/com/economic/dashboard/api/K.kt",
                   f'val k = "{fake}"\n')
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "oops")
        self.write("app/src/main/java/com/economic/dashboard/api/K.kt",
                   "val k = BuildConfig.ANTHROPIC_API_KEY\n")
        self.stage("app/src/main/java/com/economic/dashboard/api/K.kt")
        self.assertEqual(self.gates()["G9"]["status"], "pass")


class TestRunner(RepoCase):
    def test_reads_the_staged_diff_not_the_working_tree(self):
        # An unstaged violation elsewhere must not block a clean commit.
        self.write("app/src/main/java/com/economic/dashboard/ui/Bad.kt",
                   '@Suppress("X")\nclass Bad\n')          # NOT staged
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo { fun y() {} }\n")
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        self.assertEqual(self.gates()["G5"]["status"], "pass")

    def test_range_mode_reads_a_committed_range(self):
        base = git(self.repo, "rev-parse", "HEAD").strip()
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   'package com.economic.dashboard.ui\n@Suppress("X")\nclass Foo\n')
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "sneaky")
        g = self.gates(rng=f"{base}..HEAD")["G5"]
        self.assertEqual(g["status"], "fail")

    def test_exit_1_on_failure_and_0_on_clean(self):
        import io
        from contextlib import redirect_stdout
        self.write("app/src/main/java/com/economic/dashboard/ui/Foo.kt",
                   "package com.economic.dashboard.ui\nclass Foo { fun z() {} }\n")
        self.stage("app/src/main/java/com/economic/dashboard/ui/Foo.kt")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(diff_gates.main(["--repo", str(self.repo)]), 0)
        self.write("app/src/test/java/com/economic/dashboard/X.kt", TEST_SRC)
        self.stage("app/src/test/java/com/economic/dashboard/X.kt")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(diff_gates.main(["--repo", str(self.repo)]), 1)

    def test_all_four_gates_always_reported(self):
        g = self.gates()
        self.assertEqual(set(g), {"G3", "G4", "G5", "G9"})

    def test_a_crashing_gate_reads_as_fail(self):
        original = diff_gates.GATES[0]
        diff_gates.GATES[0] = ("G3", "boom",
                               lambda r, f, a: (_ for _ in ()).throw(RuntimeError("x")))
        try:
            g = self.gates()["G3"]
            self.assertEqual(g["status"], "fail")
        finally:
            diff_gates.GATES[0] = original


if __name__ == "__main__":
    unittest.main(verbosity=2)
