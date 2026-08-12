#!/usr/bin/env python3
"""Stop hook — the turn cannot end red.

Wired in .claude/settings.json on the Stop event.

"Claude stops when the work looks done. Without a check it can run, 'looks done'
is the only signal available, and you become the verification loop." This closes
that loop: on every turn end it runs os_check.py and `gradlew check`, and blocks
the turn from ending while either is failing, feeding the reason back so the
agent keeps working.

Three things that are not optional:

1. stop_hook_active is honoured FIRST. Claude Code overrides a Stop hook after 8
   consecutive blocks; without this bail-out the agent burns all eight on the
   same unfixable failure and then ends the turn red anyway.
2. The gradle result is CACHED against a content hash of the source tree. A cold
   `gradlew check` measured 3m00s on this repo. Running that on every turn end
   makes the loop unusable, and an unusable guardrail gets switched off.
3. Every invocation appends one line to .claude/gate.log. That log is the
   evidence a human reads instead of re-running the checks — which is the whole
   point of an unattended run.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U6)
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

CACHE = ".claude/.gate_cache.json"
LOG = ".claude/gate.log"
GRADLE_TIMEOUT = 900          # 15 min; a cold check is ~3 min here
OS_CHECK_TIMEOUT = 120

HASH_TARGETS = ["app/src", "app/build.gradle", "build.gradle",
                "gradle.properties", "settings.gradle", "scripts"]


def tree_hash(repo: Path) -> str:
    h = hashlib.sha256()
    for target in HASH_TARGETS:
        p = repo / target
        if p.is_file():
            st = p.stat()
            h.update(f"{target}:{st.st_size}:{st.st_mtime_ns}\n".encode())
        elif p.is_dir():
            for f in sorted(p.rglob("*")):
                if f.is_file():
                    st = f.stat()
                    rel = f.relative_to(repo).as_posix()
                    h.update(f"{rel}:{st.st_size}:{st.st_mtime_ns}\n".encode())
    return h.hexdigest()


def find_java_home() -> str | None:
    if os.environ.get("JAVA_HOME") and (
            Path(os.environ["JAVA_HOME"]) / "bin").is_dir():
        return os.environ["JAVA_HOME"]
    base = Path("C:/Program Files/Android")
    if base.is_dir():
        for d in sorted(base.iterdir()):
            jbr = d / "jbr"
            if (jbr / "bin" / "java.exe").is_file():
                return str(jbr)
    return None


def run(cmd, cwd, timeout, env=None):
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                           timeout=timeout, errors="replace", env=env)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout}s"
    except FileNotFoundError as exc:
        return 127, f"command not found: {exc}"


def first_failure_line(text: str, limit: int = 400) -> str:
    for line in text.splitlines():
        s = line.strip()
        if any(k in s for k in ("FAIL", "error:", "e: ", "What went wrong",
                                "FAILURE:", "Execution failed")):
            return s[:limit]
    return (text.strip().splitlines() or ["(no output)"])[-1][:limit]


def log(repo: Path, line: str):
    p = repo / LOG
    p.parent.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S")
    with p.open("a", encoding="utf-8") as fh:
        fh.write(f"{stamp} {line}\n")


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        payload = {}

    # 1. Never fight the 8-block cap.
    if payload.get("stop_hook_active"):
        return 0

    repo = Path(os.environ.get("CLAUDE_PROJECT_DIR") or ".").resolve()
    econ_os = Path(os.environ.get("ECON_OS_DIR")
                   or (repo.parent / "econ-os")).resolve()

    failures = []

    # --- os_check: cheap, always run ----------------------------------------
    # A4 (stale-evidence streak) is reported but NEVER blocks the turn. Aaron's
    # settled decision (plan R11/KTD5): the evidence gate stays a warning — and
    # A4 measures the state of the OS's evidence base, which no single turn can
    # fix. Wiring it as a hard Stop-block made every agent turn in this repo
    # unfinishable until Aaron files a usage note; the U8 dry-run agent hit
    # exactly that, correctly refused to falsify an idea.md to clear it, and
    # escalated. This is the fix it proposed.
    checker = econ_os / "scripts" / "os_check.py"
    if checker.is_file():
        code, out = run([sys.executable, str(checker), "--root", str(econ_os),
                         "--dash-root", str(repo), "--json"],
                        econ_os, OS_CHECK_TIMEOUT)
        hard, warn = [], []
        try:
            for r in json.loads(out):
                if r.get("status") == "fail":
                    (warn if r.get("id") == "A4" else hard).append(r)
        except (json.JSONDecodeError, TypeError):
            if code != 0:      # unparseable AND red — treat as a hard failure
                hard.append({"id": "?", "findings": [first_failure_line(out)]})
        for r in hard:
            first = (r.get("findings") or ["(no detail)"])[0]
            failures.append(f"os_check {r['id']}: {first}")
        for r in warn:
            first = (r.get("findings") or ["(no detail)"])[0]
            log(repo, f"os_check WARN (non-blocking) {r['id']}: {first}")
        log(repo, f"os_check exit={code} hard={len(hard)} warn={len(warn)}")
    else:
        log(repo, f"os_check SKIPPED (not found at {checker})")

    # --- gradlew check: expensive, cached against the tree hash --------------
    th = tree_hash(repo)
    cache_path = repo / CACHE
    cached = {}
    if cache_path.is_file():
        try:
            cached = json.loads(cache_path.read_text(encoding="utf-8"))
        except Exception:
            cached = {}

    if cached.get("tree_hash") == th and cached.get("gradle_exit") == 0:
        log(repo, f"gradlew check CACHED green tree={th[:12]}")
    else:
        java_home = find_java_home()
        if java_home is None:
            failures.append("gradlew check: no JDK found. Set JAVA_HOME, or install "
                            "Android Studio (its bundled JBR is auto-detected).")
            log(repo, "gradlew check SKIPPED (no JDK)")
        else:
            env = dict(os.environ)
            env["JAVA_HOME"] = java_home
            env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
            wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
            t0 = time.time()
            code, out = run([str(repo / wrapper), "check"], repo,
                            GRADLE_TIMEOUT, env=env)
            dt = time.time() - t0
            log(repo, f"gradlew check exit={code} in {dt:.0f}s tree={th[:12]}")
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps(
                {"tree_hash": th, "gradle_exit": code,
                 "at": time.strftime("%Y-%m-%dT%H:%M:%S")}, indent=2),
                encoding="utf-8")
            if code != 0:
                failures.append(f"gradlew check: {first_failure_line(out)}")

    if not failures:
        log(repo, "STOP allowed — all gates green")
        return 0

    reason = ("The turn cannot end while a gate is red. Fix these, then stop:\n  - "
              + "\n  - ".join(failures))
    log(repo, f"STOP blocked — {len(failures)} failing")
    print(reason, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
