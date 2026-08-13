# queue_runner.ps1 -- U10 scheduled queue-runner (agent-autonomy Phase E)
#
# Takes the TOP unchecked line of econ-os QUEUE.md, runs the work agent on it
# headless, and ends in exactly one RESULT (see econ-os logs/agent-runs/README):
# MERGED, BLOCKED (valid, exit 0), FAIL (counts toward 2-attempt cap),
# STOPPED, ENV-FAIL (does not count), NOOP.
#
# One unit per run -- METR horizon data says short units with a gate each, not
# a long run with a gate at the end. Retries capped at 2 -- multiple
# submissions measurably raise cheating rates. The runner trusts the REPO
# (two-parent merge on main), never the transcript.

$ErrorActionPreference = "Continue"
$dash = Split-Path -Parent $PSScriptRoot
$econ = "C:\Android App\econ-os"
$runs = Join-Path $econ "logs\agent-runs"
New-Item -ItemType Directory -Force -Path $runs | Out-Null
$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$log = Join-Path $runs "$stamp.md"
$n = 1
while (Test-Path $log) { $log = Join-Path $runs ("{0}_{1}.md" -f $stamp, $n); $n++ }
function L($m) { Add-Content $log $m }

L "# Queue run $stamp"
L "EVIDENCE: the stale-evidence warning stands; this run records it and proceeds (KTD5/R11)."

# -- overlap lock -------------------------------------------------------------
$lock = Join-Path $runs ".lock"
if (Test-Path $lock) {
  $age = (Get-Date) - (Get-Item $lock).LastWriteTime
  if ($age.TotalHours -lt 6) { L "RESULT: ENV-FAIL -- lock present (another run in progress); exiting."; exit 0 }
  L "stale lock ($([int]$age.TotalHours)h old) replaced"
}
Set-Content $lock "$PID $stamp"

try {
  Set-Location $dash

  # -- machine sanity: never run over human WIP -------------------------------
  $dirty = git status --porcelain
  if ($LASTEXITCODE -ne 0) { L "RESULT: ENV-FAIL -- git unavailable"; exit 1 }
  if ($dirty) {
    L "RESULT: ENV-FAIL -- working tree dirty; refusing to run over human WIP (the G1-contamination lesson):"
    $dirty | ForEach-Object { L "  $_" }
    exit 0
  }
  $branch = git rev-parse --abbrev-ref HEAD
  if ($branch -ne "main") { L "RESULT: ENV-FAIL -- HEAD is '$branch', not main; a human or a failed run left the tree mid-flight."; exit 0 }

  # -- JAVA_HOME (same detect block as stop_gate.py) --------------------------
  if (-not ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin")))) {
    $jbr = Get-ChildItem "C:\Program Files\Android" -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name | ForEach-Object { Join-Path $_.FullName "jbr" } |
      Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
    if ($jbr) { $env:JAVA_HOME = $jbr; $env:Path = "$jbr\bin;" + $env:Path; L "JAVA_HOME auto-set: $jbr" }
    else { L "RESULT: ENV-FAIL -- no JDK found (set JAVA_HOME or install Android Studio)"; exit 0 }
  }

  # -- take the top unchecked queue line --------------------------------------
  $queuePath = Join-Path $econ "subprojects\agent-autonomy\QUEUE.md"
  if (-not (Test-Path $queuePath)) { L "RESULT: ENV-FAIL -- QUEUE.md missing at $queuePath"; exit 0 }
  $qlines = Get-Content $queuePath
  $top = $qlines | Where-Object { $_ -match '^\s*-\s\[ \]\s' } | Select-Object -First 1
  if (-not $top) { L "queue empty -- nothing to do. RESULT: NOOP"; exit 0 }
  if ($top -notmatch '`([^`]+)`') { L "RESULT: ENV-FAIL -- top queue line has no backticked plan path: $top"; exit 0 }
  $plan = $Matches[1]
  if (-not (Test-Path $plan)) { L "RESULT: ENV-FAIL -- plan file not found: $plan"; exit 0 }
  L "UNIT: $plan"

  # -- 2-attempt cap ----------------------------------------------------------
  $failCount = 0
  Get-ChildItem $runs -Filter "*.md" | Where-Object { $_.FullName -ne $log } | ForEach-Object {
    $t = Get-Content $_.FullName -Raw
    if (($t -match [regex]::Escape("UNIT: $plan")) -and ($t -match "RESULT: FAIL")) { $failCount++ }
  }
  if ($failCount -ge 2) {
    L "RESULT: STOPPED -- $failCount failed attempts already exist for this unit; human decision needed. Not retrying."
    exit 0
  }

  # -- run the work agent -----------------------------------------------------
  $slug = [IO.Path]::GetFileNameWithoutExtension($plan) -replace '-plan$',''
  if ($slug.Length -gt 40) { $slug = $slug.Substring(0, 40) }
  $branchName = "agent/$slug"
  $mainBefore = git rev-parse main
  L "BASE: $mainBefore"
  L "BRANCH: $branchName"
  $tmpl = Get-Content (Join-Path $econ "subprojects\agent-autonomy\RUNNER_PROMPT.md") -Raw
  $prompt = $tmpl.Replace("{{PLAN}}", $plan).Replace("{{BRANCH}}", $branchName)
  $t0 = Get-Date
  $out = & claude -p $prompt --add-dir $econ --allowedTools "Read,Grep,Glob,Edit,Write,Bash" 2>&1
  $arc = $LASTEXITCODE
  $mins = [int]((Get-Date) - $t0).TotalMinutes
  L "agent exit=$arc after ${mins}m"
  L "## Agent output"
  L '~~~'
  $out | ForEach-Object { L "$_" }
  L '~~~'

  # -- verdict: trust the repo, not the transcript ----------------------------
  $mainAfter = git rev-parse main
  $parents = (git log -1 --format=%P main).Trim().Split(" ")
  if (($mainAfter -ne $mainBefore) -and ($parents.Count -eq 2)) {
    $sha7 = $mainAfter.Substring(0, 7)
    $qlines = $qlines | ForEach-Object {
      if ($_ -eq $top) { $_.Replace("- [ ]", "- [x]") + " (merged $sha7, $stamp)" } else { $_ }
    }
    Set-Content $queuePath $qlines
    L "RESULT: MERGED -- $sha7 on main; queue line marked done."
    exit 0
  }

  # not merged: put the tree back on main if the agent left it clean elsewhere
  $dirtyAfter = git status --porcelain
  if (-not $dirtyAfter) { git checkout -q main 2>&1 | Out-Null; L "returned checkout to main (branch left for inspection)" }
  else { L "tree left dirty by agent -- left as-is for inspection" }

  $blocked = Get-ChildItem $dash -Filter "BLOCKED-*.md" -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $t0 }
  if ($blocked) {
    L ("RESULT: BLOCKED -- " + $blocked[0].Name + " written; valid outcome, human decision needed.")
    exit 0
  }
  L ("RESULT: FAIL -- no merge on main and no BLOCKED file. Attempt " + ($failCount + 1) + " of 2.")
  exit 0
}
finally {
  Remove-Item $lock -ErrorAction SilentlyContinue
}
