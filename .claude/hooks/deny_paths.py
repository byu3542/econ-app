#!/usr/bin/env python3
"""PreToolUse hook — the agent's write tiers, enforced outside the prompt.

Wired in .claude/settings.json for Write | Edit | NotebookEdit | MultiEdit.

Why this exists in addition to permissions.deny rules: a PreToolUse hook fires
before any permission-mode check, in every mode. A deny rule is the readable
statement of intent; this is the control a change of permission mode cannot get
around. Both carry the same list on purpose.

Why the list includes this file's own directory: an agent that can edit its
guardrails has none. `.claude/**` and `.githooks/**` are denied to the agent.

Fails CLOSED. If stdin will not parse, or the path cannot be resolved, the tool
call is blocked rather than allowed — a hook that errors open is not a hook.

Plan: docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md (U4)
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import PurePosixPath

# Paths are matched as prefixes against a normalised, forward-slashed path.
# Repo-relative entries match in either repo; absolute entries pin econ-os,
# which the work agent reaches through --add-dir.
DENY = [
    # ---- the agent's own guardrails: never editable by the agent -------------
    (".claude/", "the agent's own permission layer"),
    (".githooks/", "the agent's own commit gate"),
    (".git/hooks/", "the agent's own commit gate"),
    ("scripts/check_badges.sh", "a build gate"),
    ("scripts/diff_gates.py", "a build gate"),
    ("scripts/merge_gate.py", "a build gate"),
    ("scripts/.test_baseline", "the test-count floor; only a human raises it"),
    ("econ-os/scripts/", "the OS drift checker"),
    # ---- secrets -------------------------------------------------------------
    ("local.properties", "holds 7 API keys"),
    (".gitattributes", "line-ending policy; changing it rewrites the tree"),
    # ---- Tier C: the Update Protocol. Propose, get a yes, then write ---------
    ("econ-os/CLAUDE.md", "Tier C — propose, do not write"),
    ("econ-os/skills/", "Tier C — propose, do not write"),
    ("econ-os/knowledge/frameworks/", "Tier C — propose, do not write"),
    ("econ-os/knowledge/audience/", "Tier C — propose, do not write"),
    ("econ-os/knowledge/customers/", "Tier C — only Aaron writes here, after a real conversation"),
    ("econ-os/knowledge/usage/", "Tier C — only Aaron writes here, after a real session"),
    ("econ-os/knowledge/me/", "Tier C — propose, do not write"),
    # ---- frozen folders: CLAUDE.md Working Rule 7 ----------------------------
    ("ProjectDocs/", "frozen folder — Working Rule 7"),
]

# Lifted only by a human setting the env var deliberately. The agent must never
# be able to set this for itself, which is why .claude/ is on the deny list.
TESTS = ("app/src/test/", "test files — reward-hacking countermeasure; "
                          "set ECON_AGENT_ALLOW_TESTS=1 only for a scoped test task")


def normalise(p: str) -> str:
    if not p:
        return ""
    s = p.replace("\\", "/")
    # Collapse a Windows drive prefix and any absolute root to a comparable tail.
    for marker in ("/EconomicDashboard/", "/econ-os/"):
        i = s.find(marker)
        if i != -1:
            # keep econ-os/ prefixed so its Tier C rules stay distinguishable
            return ("econ-os/" + s[i + len(marker):]) if "econ-os" in marker \
                   else s[i + len(marker):]
    return s.lstrip("/")


def decide(tool_name: str, tool_input: dict):
    path = (tool_input.get("file_path") or tool_input.get("path")
            or tool_input.get("notebook_path") or "")
    if not path:
        return None                      # nothing path-shaped; not ours to judge
    norm = normalise(str(path))

    if norm.startswith(TESTS[0]):
        if os.environ.get("ECON_AGENT_ALLOW_TESTS") == "1":
            return None
        return f"BLOCKED: {norm} is {TESTS[1]}"

    for prefix, why in DENY:
        if norm == prefix.rstrip("/") or norm.startswith(prefix):
            return (f"BLOCKED: {norm} is {why}. This is a hook, not a preference — "
                    f"changing permission mode will not lift it. If the change is "
                    f"genuinely needed, stop and write a BLOCKED-<date>.md naming "
                    f"the decision, then exit.")
    return None


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception as exc:
        # Fail closed. A hook that cannot read its input must not wave the call through.
        print(f"deny_paths: could not parse hook input ({exc!r}); blocking.",
              file=sys.stderr)
        return 2

    reason = decide(payload.get("tool_name", ""), payload.get("tool_input") or {})
    if reason is None:
        return 0

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    print(reason, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
