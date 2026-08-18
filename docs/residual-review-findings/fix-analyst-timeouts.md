# Residual review findings — fix/analyst-timeouts (c6177fe)

Source: post-fix diff review of range 5b5a6c7..c6177fe (analyst timeout fix).
Accepted as deferred, not fixed in this change.

## 1. callTimeout(180s) tuning is a judgment call, not a measured number
`app/src/main/java/com/economic/dashboard/ui/MainActivity.kt:100`

A legitimate long Sonnet round (max_tokens 4096, slow first byte) could in the
worst case approach 180s and be cut off with "The response timed out". Estimate
says 180s has margin (~60–90s generation + TTFB), but nobody has measured P99
round time on device. Action: after this ships, watch logcat for
SocketTimeoutException / timeout errors on visibly-still-streaming rounds; bump
to 240s if they appear. Severity: low.

## 2. DailyBriefWorker still silently drops the brief on non-timeout errors
`app/src/main/java/com/economic/dashboard/workers/DailyBriefWorker.kt:172`

This fix retries on SocketTimeoutException only. API 5xx, connect failures, and
parse failures still return Result.success(), losing the day's brief silently
(pre-existing behavior, out of this fix's scope). Action if briefs keep going
missing: return Result.retry() for IOException broadly, keeping success() for
parse/logic errors. Severity: low.
