#!/usr/bin/env python3
"""The merge contract as one command. Nothing merges to main without it.

Usage:
    python3 scripts/merge_gate.py --base <sha> --head <sha> --plan <path>
    python3 scripts/merge_gate.py ... --merge        # merge only if all green
    python3 scripts/merge_gate.py ... --json

Exit 0 = all gates green (and merged, if --merge). Exit 1 = a gate failed.
Exit 2 = could not run.

--base is the commit the work started from (the diff range is base..head).
--head is the tip the caller believes it produced. G8 exists because a second
live session has committed to this repo mid-run before; a moved head means the
diff under judgment is not the diff on disk.

Gate order is cheap-first, first failure stops the run:

  G8   branch head unmoved          (instant — a moved head invalidates the rest)
  G7   on a branch; no force/amend/rebase in this run's reflog
  G3/G4/G5/G9  diff-shape gates     (delegated to diff_gates.py over base..head)
  G6   every changed file appears in the plan's **Files:** lists
  G2   scripts/check_badges.sh exits 0            (~4s)
  G1   gradlew check exits 0                      (~3 min cold on this machine)
  G10  adversarial review verdict   (ADVISORY — reported, never decisive alone)

--merge then re-checks G8 immediately before `git merge --no-ff`, because the
head can move between the gate run and the merge. Every merge is --no-ff so an
overnight mistake reverts as one commit.

There is deliberately NO skip flag for G1/G2. Tests exercise this module by
patching the build functions in-process; production has no bypass, because a
bypass an agent can reach is a gate that does not exist.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U7)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import diff_gates  # noqa: E402  (same scripts/ directory)

GRADLE_TIMEOUT = 1200
BADGES_TIMEOUT = 120

BASH_CANDIDATES = ["C:/Program Files/Git/bin/bash.exe",
                   "C:/Program Files/Git/usr/bin/bash.exe",
                   "C:/Program Files (x86)/Git/bin/bash.exe"]


class Gate:
    def __init__(self, gid, name, status, findings=None):
        self.id, self.name, self.status = gid, name, status
        self.findings = findings or []

    def as_dict(self):
        return {"id": self.id, "name": self.name, "status": self.status,
                "findings": self.findings}


def _git(repo, *args, timeout=60):
    r = subprocess.run(["git"] + list(args), cwd=repo, capture_output=True,
                       text=True, errors="replace", timeout=timeout)
    return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()


def _run(cmd, cwd, timeout, env=None):
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                           errors="replace", timeout=timeout, env=env)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout}s"
    except FileNotFoundError as exc:
        return 127, f"not found: {exc}"


# ------------------------------------------------------------------ gates

def gate_g8(repo: Path, head: str) -> Gate:
    code, actual, err = _git(repo, "rev-parse", "HEAD")
    if code != 0:
        return Gate("G8", "branch head unmoved", "fail", [f"rev-parse failed: {err}"])
    if not actual.startswith(head) and not head.startswith(actual):
        return Gate("G8", "branch head unmoved", "fail",
                    [f"HEAD is {actual[:12]}, expected {head[:12]} — another "
                     f"session moved the branch; the judged diff is stale"])
    return Gate("G8", "branch head unmoved", "pass", [f"HEAD {actual[:12]}"])


def gate_g7(repo: Path, base: str) -> Gate:
    findings = []
    code, branch, _ = _git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    if code != 0 or branch == "HEAD":
        findings.append("not on a branch (detached HEAD)")
    elif branch == "main":
        findings.append("HEAD is main — work must arrive on a branch and merge in")
    code, out, _ = _git(repo, "reflog", "--format=%gs", "-40")
    if code == 0:
        base_seen = False
        for entry in out.splitlines():
            if base[:7] in entry:
                base_seen = True
            low = entry.lower()
            for marker, label in (("commit (amend)", "amend"),
                                  ("rebase", "rebase"),
                                  ("reset: moving", "hard reset")):
                if marker in low:
                    findings.append(f"reflog shows a {label} in this run: {entry[:90]}")
            if base_seen:
                break
    return Gate("G7", "on a branch; no force/amend/rebase",
                "fail" if findings else "pass", findings)


def _parse_plan_files(plan_text: str):
    """Every backticked path inside a **Files:** field, across all units.

    Returns (paths, errors). An empty result from a plan that HAS Files fields
    is an error — an unparseable allowlist must fail loudly, never pass
    everything vacuously.
    """
    paths, errors = [], []
    fields = re.findall(r"\*\*Files:?\*\*:?(.*?)(?=\n\*\*[A-Z]|\n###|\n---|\Z)",
                        plan_text, re.S)
    if not fields:
        return [], ["plan contains no **Files:** field — cannot compute G6"]
    for block in fields:
        for cand in re.findall(r"`([^`\n]+)`", block):
            p = cand.strip().split(" ")[0].strip()
            p = re.sub(r"^(dash|EconomicDashboard)/", "", p)
            if p:
                paths.append(p)
    if not paths:
        errors.append("**Files:** fields parsed but yielded no paths — refusing "
                      "to treat an empty allowlist as pass-everything")
    return paths, errors


def gate_g6(repo: Path, base: str, head: str, plan_path: Path) -> Gate:
    if not plan_path.is_file():
        return Gate("G6", "diff within the plan's Files", "fail",
                    [f"plan not found: {plan_path}"])
    allowed, errors = _parse_plan_files(plan_path.read_text(encoding="utf-8",
                                                            errors="replace"))
    if errors:
        return Gate("G6", "diff within the plan's Files", "fail", errors)
    code, out, err = _git(repo, "diff", "--name-only", f"{base}..{head}")
    if code != 0:
        return Gate("G6", "diff within the plan's Files", "fail", [err])
    changed = [l for l in out.splitlines() if l.strip()]
    allowed_set = set(allowed)
    escapees = []
    for f in changed:
        if f in allowed_set:
            continue
        # A plan naming a directory covers files under it.
        if any(f.startswith(a.rstrip("/") + "/") for a in allowed_set):
            continue
        escapees.append(f)
    if escapees:
        return Gate("G6", "diff within the plan's Files", "fail",
                    [f"changed but not in the plan: {f}" for f in escapees])
    return Gate("G6", "diff within the plan's Files", "pass",
                [f"{len(changed)} changed files, all planned"])


def _diff_shape_gates(repo: Path, base: str, head: str):
    results, err = diff_gates.run(repo, f"{base}..{head}")
    if err is not None:
        return [Gate("G3-G9", "diff-shape gates", "fail", [err])]
    return [Gate(r["id"], r["name"], r["status"], r["findings"]) for r in results]


def _find_bash():
    try:
        if subprocess.run(["bash", "-c", "exit 0"], capture_output=True,
                          timeout=10).returncode == 0:
            return "bash"
    except Exception:
        pass
    for c in BASH_CANDIDATES:
        if Path(c).is_file():
            return c
    return None


def gate_g2(repo: Path) -> Gate:
    bash = _find_bash()
    if bash is None:
        return Gate("G2", "check_badges.sh exits 0", "fail",
                    ["no bash found — the gate is not allowed to skip itself"])
    code, out = _run([bash, "scripts/check_badges.sh"], repo, BADGES_TIMEOUT)
    if code != 0:
        tail = out.strip().splitlines()[-3:] if out.strip() else ["(no output)"]
        return Gate("G2", "check_badges.sh exits 0", "fail", tail)
    return Gate("G2", "check_badges.sh exits 0", "pass", [])


def _find_java_home():
    jh = os.environ.get("JAVA_HOME")
    if jh and (Path(jh) / "bin").is_dir():
        return jh
    base = Path("C:/Program Files/Android")
    if base.is_dir():
        for d in sorted(base.iterdir()):
            if (d / "jbr" / "bin" / "java.exe").is_file():
                return str(d / "jbr")
    return None


def gate_g1(repo: Path) -> Gate:
    java_home = _find_java_home()
    if java_home is None:
        return Gate("G1", "gradlew check exits 0", "fail",
                    ["no JDK: set JAVA_HOME or install Android Studio (JBR auto-detected)"])
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    wrapper = str(repo / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    t0 = time.time()
    code, out = _run([wrapper, "check"], repo, GRADLE_TIMEOUT, env=env)
    dt = time.time() - t0
    if code != 0:
        lines = [l.strip() for l in out.splitlines()
                 if any(k in l for k in ("FAILED", "error:", "e: ", "What went wrong"))]
        return Gate("G1", "gradlew check exits 0", "fail",
                    (lines[:5] or ["(no diagnostic line found)"]) + [f"({dt:.0f}s)"])
    return Gate("G1", "gradlew check exits 0", "pass", [f"BUILD SUCCESSFUL in {dt:.0f}s"])


def gate_g10(repo: Path, head: str) -> Gate:
    """Advisory only. A review verdict file, if present, is reported; its absence
    is reported too. It can add a failure but its absence never blocks — and it
    is never the ONLY gate, because a reviewer prompted to find problems will
    find them whether or not they exist."""
    verdict = repo / ".claude" / f"REVIEW-{head[:12]}.md"
    if not verdict.is_file():
        return Gate("G10", "adversarial review (advisory)", "skip",
                    [f"no verdict file at {verdict.name} — advisory, not blocking"])
    text = verdict.read_text(encoding="utf-8", errors="replace")
    if re.search(r"^VERDICT:\s*reject", text, re.M | re.I):
        return Gate("G10", "adversarial review (advisory)", "fail",
                    ["reviewer verdict: reject — see " + verdict.name])
    return Gate("G10", "adversarial review (advisory)", "pass",
                ["reviewer verdict recorded in " + verdict.name])


# ------------------------------------------------------------------ driver

def run_gates(repo: Path, base: str, head: str, plan: Path):
    """Cheap-first; stops at the first hard failure. Returns (gates, all_green)."""
    gates = []

    for g in (gate_g8(repo, head), gate_g7(repo, base)):
        gates.append(g)
        if g.status == "fail":
            return gates, False

    for g in _diff_shape_gates(repo, base, head):
        gates.append(g)
    if any(g.status == "fail" for g in gates):
        return gates, False

    for fn in (lambda: gate_g6(repo, base, head, plan),
               lambda: gate_g2(repo),
               lambda: gate_g1(repo)):
        g = fn()
        gates.append(g)
        if g.status == "fail":
            return gates, False

    gates.append(gate_g10(repo, head))          # advisory: reported last,
    all_green = all(g.status != "fail" for g in gates)
    return gates, all_green


def do_merge(repo: Path, head: str, branch_hint: str | None):
    """checkout main, re-check G8, merge --no-ff."""
    code, branch, _ = _git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    branch = branch_hint or branch
    recheck = gate_g8(repo, head)
    if recheck.status == "fail":
        return False, ["pre-merge G8 recheck failed"] + recheck.findings
    code, _, err = _git(repo, "checkout", "main")
    if code != 0:
        return False, [f"checkout main failed: {err}"]
    code, out, err = _git(repo, "merge", "--no-ff", "--no-edit", branch, timeout=120)
    if code != 0:
        _git(repo, "merge", "--abort")
        _git(repo, "checkout", branch)
        return False, [f"merge --no-ff failed and was aborted: {err or out}"]
    return True, [f"merged {branch} into main (--no-ff)"]


def render(gates, as_json=False):
    if as_json:
        print(json.dumps([g.as_dict() for g in gates], indent=2))
        return
    mark = {"pass": "ok  ", "fail": "FAIL", "skip": "skip"}
    width = max(len(g.name) for g in gates)
    for g in gates:
        print(f"[{mark[g.status]}] {g.id:<5} {g.name:<{width}}")
        for f in g.findings:
            print(f"          - {f}")


def main(argv=None):
    ap = argparse.ArgumentParser(description="The merge contract as one command")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--base", required=True, help="commit the work started from")
    ap.add_argument("--head", required=True, help="expected tip of the work branch")
    ap.add_argument("--plan", required=True, help="path to the unit's plan markdown")
    ap.add_argument("--merge", action="store_true",
                    help="merge --no-ff into main, only if every gate is green")
    ap.add_argument("--branch", default=None, help="branch to merge (default: current)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)

    repo = Path(args.repo).resolve()
    if not (repo / ".git").exists():
        print(f"error: {repo} is not a git repository", file=sys.stderr)
        return 2

    gates, all_green = run_gates(repo, args.base, args.head, Path(args.plan))
    render(gates, args.as_json)

    if not all_green:
        failed = [g.id for g in gates if g.status == "fail"]
        print(f"\nBLOCKED by {', '.join(failed)} — nothing merges", file=sys.stderr)
        return 1

    if not args.merge:
        print("\nall gates green (report mode — pass --merge to act)")
        return 0

    ok, notes = do_merge(repo, args.head, args.branch)
    for n in notes:
        print(n)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
