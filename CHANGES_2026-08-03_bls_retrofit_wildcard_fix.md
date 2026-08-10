# 2026-08-03 — Fix BLS data source failing on every refresh

## Symptom
User reported an in-app banner saying several data sources weren't
loading/refreshing. Logcat (filtered to `EconomicRepository`/`MainActivity`
during a manual refresh) showed two distinct, unrelated failures:

1. **BLS (Employment, CPI, Wages) — failed on every single refresh, 100% reproducible.**
2. **BEA (GDP) + most FRED-backed series (Fed Funds, 10Y/2Y/3M history, PCE,
   Housing, MBS/Mortgage, S&P 500, Nasdaq, VIX, BAA, HY spreads) — failed once,
   all within ~100ms of each other, all as `SocketException: Software caused
   connection abort` during the TLS handshake.**

Only the Treasury yield curve fetch succeeded in that log capture.

## Root cause 1 — BLS: fixed
`EconomicApiService.getBlsData()` declared its `@Body` parameter as
`Map<String, Any>`. `kotlin.collections.Map` is declared `out V`
(declaration-site covariance). When the value type argument (`Any`) is a
non-final class, Kotlin emits a JVM generic signature of `Map<String, ?>` for
that parameter — a genuine wildcard, not just a Kotlin-side annotation.
Retrofit builds its request adapters via reflection at call-proxy-creation
time and explicitly rejects wildcard parameter types:

```
java.lang.IllegalArgumentException: Parameter type must not include a type
variable or wildcard: java.util.Map<java.lang.String, ?> (parameter #1)
for method EconomicApiService.getBlsData
```

This threw before any HTTP request was ever sent, so every BLS-backed series
(Employment, CPI, Wages) failed on every app refresh since this interface was
converted to Kotlin — it was never a network or key problem for BLS. FRED's
and BEA's `getFredData`/`getBeaData` use `Map<String, String>`, and `String`
is `final`, so Kotlin doesn't need to emit a wildcard for those — they were
never affected.

**Fix:** `app/src/main/java/com/economic/dashboard/api/EconomicApiService.kt`
— changed the parameter type to `Map<String, @JvmSuppressWildcards Any>`,
which forces the concrete `Map<String, Object>` JVM signature Retrofit
requires. Both call sites (`EconomicRepository.fetchBlsData()` and
`HistoricalDataRepository.fetchBlsBatch()`) already build a concrete
`HashMap<String, Any>`, so no caller changes were needed.

Not yet verified by a build — this sandbox has no Android SDK, so please
rebuild in Android Studio and re-run a refresh to confirm BLS-backed cards
(Employment, CPI, Wages) populate.

## Root cause 2 — BEA/FRED: network blip, not a code defect
All four backend APIs (FRED, BLS, BEA, Treasury) responded successfully when
called directly from outside the app with the same keys, and the BEA/FRED
failures in the log are `SocketException` during TLS handshake, arriving
near-simultaneously across every open connection in the 4-thread network
pool. That pattern — many concurrent sockets aborting at the same instant —
is consistent with the phone's network changing mid-refresh (Wi-Fi/cellular
handoff, VPN toggling, brief signal loss), not a bug in this codebase. No
code change was made for this; it should self-resolve on a refresh with a
stable connection. If it recurs consistently (not just once), that would
point at something environmental worth chasing separately (e.g. a VPN or
firewall rule scoped to `apps.bea.gov`/`api.stlouisfed.org`).

## Files changed
- `app/src/main/java/com/economic/dashboard/api/EconomicApiService.kt`
