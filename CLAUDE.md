# EconomicDashboard — agent working rules

The Android app. The operating system that governs how work here gets planned,
verified and recorded lives in `../econ-os`, reached with `--add-dir`.

**Read `../econ-os/CLAUDE.md` first.** It is the router. This file only carries
what is specific to working *in this repo* as an agent.

## Where the rules actually live

Not here. `.claude/settings.json` and `.claude/hooks/` carry them, because an
instruction in a markdown file is advisory and a hook is not. If this file and a
hook disagree, the hook is right and this file is stale — fix this file.

The full reasoning, the ten-gate merge contract, and the autonomy tiers are in
`../econ-os/docs/plans/2026-08-12-002-feat-agent-autonomy-plan.md`.

## What you may not do here

Enforced by `.claude/hooks/deny_paths.py`, which fires before any permission
check in every mode. Listed so you know why you were blocked, not so you can
comply voluntarily.

- **`app/src/test/**`** — not editable. Lifted only by a human setting
  `ECON_AGENT_ALLOW_TESTS=1` for a scoped test task. Editing a test to make a
  build pass is the most commonly measured failure mode for models like you.
- **`.claude/**`, `.githooks/**`, `scripts/check_badges.sh`,
  `scripts/diff_gates.py`, `scripts/.test_baseline`** — your own guardrails.
- **`local.properties`** — 7 API keys, gitignored, stays that way.
- **`../econ-os` Tier C** — `CLAUDE.md`, `skills/`, `knowledge/frameworks/`,
  `knowledge/audience/`, `knowledge/customers/`, `knowledge/usage/`,
  `knowledge/me/`. Propose in a dated file; never write.
- **No force-push, amend, rebase, `reset --hard`, or `git add -A`.** The last one
  because this tree is shared: a commit race on 2026-08-11 swept another
  session's uncommitted work into a commit that does not mention it. Stage by
  name, always.

## What you may do

`app/src/main/**`, `app/build.gradle`, and in econ-os: `subprojects/**`,
`docs/plans/**`, `docs/solutions/**`.

## Before you stop

The `Stop` hook runs `os_check.py` and `gradlew check` and will not let the turn
end while either is red. It caches the gradle result against a content hash of
the source tree — a cold `check` is about 3 minutes here.

Every run appends to `.claude/gate.log`. That log is what a human reads instead
of re-running your work, so do not treat it as noise.

## When you need a decision you are not allowed to make

Inside your tier: decide it from the plan's "should NOT do" and "success looks
like", and log the call.

Outside your tier: **stop.** Write `BLOCKED-<date>.md` naming the decision and
what you would have chosen, and exit 0. Blocked is a valid outcome, not a
failure. Do not work around a hook.

## Environment

- `JAVA_HOME` must point at Android Studio's bundled JBR. The Stop hook
  auto-detects it under `C:/Program Files/Android/*/jbr`.
- `bash` is required by `checkBadges` and is resolved by `app/build.gradle` from
  PATH or the standard Git for Windows locations.
- Enable the commit gate once per clone: `git config core.hooksPath .githooks`
