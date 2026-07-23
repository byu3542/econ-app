# push-today.ps1
# Stages, commits, and pushes today's AI Analyst changes.
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File .\push-today.ps1
# (or in a PowerShell window:  .\push-today.ps1 )

$ErrorActionPreference = "Stop"
$repo = "C:\Android App\EconomicDashboard"
Set-Location $repo

# Clear a stale git lock if one was left behind (safe when no commit editor is open).
$lock = Join-Path $repo ".git\index.lock"
if (Test-Path $lock) {
    Write-Host "Removing stale index.lock..."
    Remove-Item $lock -Force
}

# Stage exactly the files we changed (the .idea IDE cache is intentionally excluded).
$files = @(
    "app/src/main/java/com/economic/dashboard/analyst/AnalystToolExecutor.java",
    "app/src/main/java/com/economic/dashboard/analyst/AskAnalyst.java",
    "app/src/main/java/com/economic/dashboard/news/NewsAdapter.java",
    "app/src/main/java/com/economic/dashboard/ui/AiAnalystBottomSheet.java",
    "app/src/main/java/com/economic/dashboard/ui/ChatAdapter.java",
    "app/src/main/java/com/economic/dashboard/ui/MainActivity.java",
    "capture-screenshot.bat"
)
git add -- $files

# Bail out early if nothing is staged, so we don't print a confusing error.
git diff --cached --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Host "Nothing staged to commit. Working tree may already be clean."
    exit 0
}

# Build the commit message as an argument array (one -m per paragraph).
$commitArgs = @(
    "-m", "feat(analyst): harden AI Analyst tool-use, model routing, and gesture UX",
    "-m", "AnalystToolExecutor: get_series now covers the 11 live ViewModel-backed series (CPI, PCE, Core PCE, S&P 500, Nasdaq, VIX, wages, housing starts/sales, BAA/HY spreads) via a new LiveSeriesProvider, with date-window filtering, downsampling of long daily series, and honest 'not available' messages instead of retry loops (AI Law 16).",
    "-m", "AiAnalystBottomSheet: per-turn model routing (Haiku default; Sonnet for gesture turns and tool-likely questions); concise length tier for one-tap gestures; [STALE] freshness tags on snapshot values; auto-continue/strip dangling 'let me look that up' announcements (AI Law 1); retry only transient failures; live-series provider wiring and tool-round status.",
    "-m", "AiAnalystBottomSheet + NewsAdapter: fix 'headline not in cached feed' refusals - system prompt now treats a user-quoted headline as a real article to analyze directly, and the news long-press smart-prompt path sends the full article (title, source, summary) instead of the bare title.",
    "-m", "ChatAdapter + MainActivity: 'Ask AI' on a selected metric name behaves like a card long-press (snapshot + screen context, concise tier); tool-round status bubble; stray-markdown cleanup; live-series charting from the same provider get_series reads.",
    "-m", "AskAnalyst: openWithQuery overload carrying screen context + concise flag; harvests card and chart values at press time.",
    "-m", "capture-screenshot.bat: dev helper to save emulator screenshots into screenshots/."
)
git commit @commitArgs

git push origin main

Write-Host ""
Write-Host "Done. Latest commit:"
git log -1 --oneline
