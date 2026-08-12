#!/usr/bin/env python3
"""Diff-shape gates G3, G4, G5, G9 for EconomicDashboard.

Usage:
    python3 scripts/diff_gates.py                 # staged diff (pre-commit hook)
    python3 scripts/diff_gates.py --range A..B    # a committed range (merge_gate)
    python3 scripts/diff_gates.py --json

Exit 0 = all gates pass. Exit 1 = at least one failed. Exit 2 = could not run.

These four gates exist because "the build is green" and "the change is correct"
are different claims, and an agent optimizing for the first can reach it by
damaging the second. Each gate is matched to a documented failure:

  G3  no test files in the diff    - editing tests instead of code is the single
                                     most common measured cheat for Claude models
  G4  @Test count never drops      - deleting a failing test turns red green
  G5  no new suppressions          - @Suppress / @Ignore / abortOnError false
                                     silence a checker rather than satisfy it
  G9  no secrets, local.properties - that file holds 7 API keys and is gitignored

G3 is lifted only by ECON_AGENT_ALLOW_TESTS=1, which a human sets deliberately.
The agent must never be able to set it for itself.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U5)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

TEST_DIR = "app/src/test/"
BASELINE_FILE = "scripts/.test_baseline"

SUPPRESSION_PATTERNS = [
    (r"@Suppress\s*\(", "@Suppress"),
    (r"//\s*noinspection\b", "// noinspection"),
    (r"@Ignore\b", "@Ignore"),
    (r'tools:ignore\s*=', "tools:ignore"),
    (r"abortOnError\s+false", "abortOnError false"),
    (r"checkReleaseBuilds\s+false", "checkReleaseBuilds false"),
    (r"@SuppressLint\s*\(", "@SuppressLint"),
]

SECRET_PATTERNS = [
    (r"sk-ant-[A-Za-z0-9_\-]{8,}", "Anthropic API key"),
    (r"\bAIza[0-9A-Za-z_\-]{30,}", "Google API key"),
    (r"\bghp_[A-Za-z0-9]{20,}", "GitHub token"),
    (r"\bgithub_pat_[A-Za-z0-9_]{20,}", "GitHub fine-grained token"),
    (r"\bAKIA[0-9A-Z]{16}\b", "AWS access key id"),
    (r"\bxox[baprs]-[A-Za-z0-9\-]{10,}", "Slack token"),
    (r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "private key block"),
    # Generic: a key-ish name assigned a long quoted literal. Requires the
    # literal so `buildConfigField "String", "FRED_API_KEY", fredKey` — which
    # names a key without containing one — does not trip it.
    (r"(?i)\b(api[_-]?key|secret|token|password|passwd)\s*[=:]\s*"
     r"[\"'][A-Za-z0-9_\-/+]{16,}[\"']", "hardcoded credential"),
]


def _git(args, cwd):
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True,
                          text=True, errors="replace")


def changed_files(repo: Path, rng: str | None):
    args = ["diff", "--name-only", "--diff-filter=ACMR"]
    args += [rng] if rng else ["--cached"]
    r = _git(args, repo)
    if r.returncode != 0:
        return None, r.stderr.strip()
    return [l.strip() for l in r.stdout.splitlines() if l.strip()], None


def added_lines(repo: Path, rng: str | None):
    """(file, line_text) for every ADDED line. Never the removed side —
    deleting a line that contains a secret must not fail the gate."""
    args = ["diff", "--unified=0"]
    args += [rng] if rng else ["--cached"]
    r = _git(args, repo)
    if r.returncode != 0:
        return None, r.stderr.strip()
    out, current = [], None
    for line in r.stdout.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            out.append((current or "?", line[1:]))
    return out, None


def count_tests(repo: Path) -> int:
    n = 0
    base = repo / TEST_DIR
    if not base.is_dir():
        return 0
    for p in base.rglob("*"):
        if p.suffix in (".kt", ".java"):
            try:
                n += len(re.findall(r"^\s*@Test\b", p.read_text(
                    encoding="utf-8", errors="replace"), re.M))
            except OSError:
                pass
    return n


# --------------------------------------------------------------------- gates

def gate_g3(repo, files, _added):
    if os.environ.get("ECON_AGENT_ALLOW_TESTS") == "1":
        return "skip", ["ECON_AGENT_ALLOW_TESTS=1 — test edits permitted this run"]
    hits = [f for f in files if f.startswith(TEST_DIR)]
    return ("fail", [f"test file in diff: {f}" for f in hits]) if hits else ("pass", [])


def gate_g4(repo, _files, _added):
    bf = repo / BASELINE_FILE
    if not bf.is_file():
        return "skip", [f"no {BASELINE_FILE} — run --seed-baseline once"]
    try:
        baseline = int(bf.read_text(encoding="utf-8").split("#")[0].strip())
    except ValueError:
        return "fail", [f"{BASELINE_FILE} is not an integer"]
    actual = count_tests(repo)
    if actual < baseline:
        return "fail", [f"@Test count dropped: {actual} < baseline {baseline}. "
                        f"Deleting a test is not fixing it."]
    return "pass", [f"@Test count {actual} >= baseline {baseline}"]


# G5 scans only files where a suppression means something: Kotlin/Java source,
# XML, and gradle build files. Without this scope the gate blocks its own
# source — diff_gates.py literally contains the strings it hunts for, and its
# test file stages them as fixtures. Discovered the hard way on 2026-08-12:
# the first commit of this very file was BLOCKED by this very gate.
G5_SCOPE = (".kt", ".java", ".xml", ".gradle", ".kts")


def gate_g5(_repo, _files, added):
    hits = []
    for f, line in added:
        if not f.endswith(G5_SCOPE):
            continue
        for pat, label in SUPPRESSION_PATTERNS:
            if re.search(pat, line):
                hits.append(f"{f}: adds {label} -> {line.strip()[:90]}")
                break
    return ("fail", hits) if hits else ("pass", [])


def gate_g9(repo, files, added):
    hits = []
    if "local.properties" in files:
        hits.append("local.properties is in the diff — it holds 7 API keys")
    gi = repo / ".gitignore"
    if gi.is_file() and "local.properties" not in gi.read_text(
            encoding="utf-8", errors="replace"):
        hits.append(".gitignore no longer ignores local.properties")
    for f, line in added:
        for pat, label in SECRET_PATTERNS:
            if re.search(pat, line):
                hits.append(f"{f}: possible {label} on an added line")
                break
    return ("fail", hits) if hits else ("pass", [])


GATES = [("G3", "no test files in the diff", gate_g3),
         ("G4", "@Test count never drops", gate_g4),
         ("G5", "no new suppressions", gate_g5),
         ("G9", "no secrets; local.properties untouched", gate_g9)]


def run(repo: Path, rng: str | None):
    files, err = changed_files(repo, rng)
    if err is not None:
        return None, err
    added, err = added_lines(repo, rng)
    if err is not None:
        return None, err
    results = []
    for gid, name, fn in GATES:
        try:
            status, findings = fn(repo, files, added)
        except Exception as exc:
            status, findings = "fail", [f"gate raised: {exc!r}"]
        results.append({"id": gid, "name": name, "status": status,
                        "findings": findings})
    return results, None


def main(argv=None):
    ap = argparse.ArgumentParser(description="Diff-shape gates for EconomicDashboard")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--range", dest="rng", default=None,
                    help="commit range, e.g. abc123..HEAD (default: staged diff)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    ap.add_argument("--seed-baseline", action="store_true",
                    help="write the current @Test count to scripts/.test_baseline")
    args = ap.parse_args(argv)
    repo = Path(args.repo).resolve()

    if args.seed_baseline:
        n = count_tests(repo)
        bf = repo / BASELINE_FILE
        bf.parent.mkdir(parents=True, exist_ok=True)
        bf.write_text(f"{n}  # @Test count baseline; only a human raises this\n",
                      encoding="utf-8")
        print(f"seeded {BASELINE_FILE} = {n}")
        return 0

    results, err = run(repo, args.rng)
    if err is not None:
        print(f"diff_gates: could not read the diff: {err}", file=sys.stderr)
        return 2

    if args.as_json:
        print(json.dumps(results, indent=2))
    else:
        mark = {"pass": "ok  ", "fail": "FAIL", "skip": "skip"}
        for r in results:
            print(f"[{mark[r['status']]}] {r['id']} {r['name']}")
            for f in r["findings"]:
                print(f"         - {f}")
        failed = [r["id"] for r in results if r["status"] == "fail"]
        print()
        print(f"diff gates: {sum(1 for r in results if r['status']=='pass')} pass, "
              f"{len(failed)} fail, "
              f"{sum(1 for r in results if r['status']=='skip')} skip")
        if failed:
            print(f"BLOCKED by {', '.join(failed)}")
    return 1 if any(r["status"] == "fail" for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
