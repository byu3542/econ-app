# Kotlin Migration — Batch 11b

Second of three commits splitting batch 11. **The ViewModel and the two bottom
sheets**: 3 files, ~1,209 lines.

Branch: `kotlin/batch-11b`. Builds on 11a (merged).

**Only `MainActivity` (663 lines) remains** — that's 11c, and it completes the
migration's conversion work.

---

## Files converted

| File | Lines | Kotlin shape | Interop |
|---|---|---|---|
| `ui/EconomicViewModel.java` → `.kt` | 617 | `class : AndroidViewModel` + `companion object` | `const val` ×16, `@JvmStatic` ×7 |
| `ui/SettingsBottomSheet.java` → `.kt` | 389 | `class : BottomSheetDialogFragment` | `const val TAG` |
| `ui/MetricBottomSheet.java` → `.kt` | 203 | `object` | `@JvmStatic` ×2 |

All three `.java` originals deleted.

---

## The decision that shaped this batch

**Every `getXxxData()` on the ViewModel stays a FUNCTION, not a property.**

This is the single highest-risk call in 11b, and it was settled by evidence
rather than idiom. A grep of the already-converted Kotlin returned **~100 call
sites**, every one of them using the *function* form with parens:

```
16 .getCpiData()   11 .getFailedSeries()   10 .getWageData()   8 .getTreasuryData()  …
```

Kotlin's synthetic-property sugar does **not** apply to a Kotlin-declared
`getX()` function, so turning these into `val`s would have broken all ~100 at
once.

This is the exact **mirror image** of batch 8's `NewsRepository.getCachedItems()`,
where the sole Kotlin caller used *property* syntax and the getter therefore had
to become a property. Same rule both times: **the call sites decide, not the
idiom** — and the only way to know is to grep for both forms before converting.

---

## The one forced signature change

**`filterByMonths` / `filterByTimeframe` now take and return a NON-NULL list.**

The Java was `List<EconomicDataPoint>` unannotated, which Kotlin saw as a
platform type — so the 13 existing call sites could assign the result straight
back into a non-null `var rows`:

```kotlin
var rows = EconomicViewModel.filterBySeries(data, seriesName)   // non-null
rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "bonds")
```

A faithful nullable port (`List<EconomicDataPoint>?`) would have broken **all 13**
— `BondsFragment`, `CpiFragment`, `EmploymentFragment`, `GdpFragment`,
`HousingFragment` ×4, `InflationFragment`, `SpreadsFragment`, `StocksFragment`,
`WagesFragment` ×2.

Non-null is also honest: every caller passes the result of `filterBySeries`,
which never returns null. The Java's `if (sortedRows == null) return sortedRows`
branch was unreachable and is dropped, along with the now-dead `date == null`
guards (`EconomicDataPoint.date` has been non-null since batch 3). Documented in
the file.

---

## Other interop decisions

**1. Series keys are `const val`, not `@JvmField`.** `MainActivity.java:594`
reads `EconomicViewModel.CACHE_FED_FUNDS` while staying Java until 11c.
`const val` in a companion emits a real `public static final` field on the class
itself. Same for `SettingsBottomSheet.TAG` (read at `MainActivity.java:199/200/228`).

**2. `@JvmStatic` on the seven static helpers** — `getLatest`, `filterBySeries`,
`filterByMonths`, both `filterByTimeframe` overloads, `calculatePercentile`,
`formatPercentile`. `MainActivity.java` calls `getLatest` at six sites and
`filterBySeries` at two.

**3. `EconomicRepository.DataCallback` needs `object :`, not a lambda.** It is a
Kotlin interface with **two** methods (`onSuccess`/`onError`), so it is not a SAM
type — all 18 fetch callbacks are object expressions. No `fun interface` change
was needed or possible here.

**4. `CacheManager`'s callbacks were already `fun interface`** (`StatusCallback`,
`OperationCallback`), so the three `SettingsBottomSheet` lambdas convert cleanly.
Checked before converting, since this is exactly where lesson 13 bites — it just
happened to already be right from batch 4.

**5. `Arrays.asList(this::fetchTreasury, …)` → `List<() -> Unit>`.** The Java
built a `List<Runnable>` from method references purely to derive the pending-fetch
count from the list size. Kotlin function references are `KFunction0`, not
`Runnable`, so the list is typed as `List<() -> Unit>` and invoked with `task()`.
The count-derivation property the comment calls out is preserved exactly.

**6. `cacheAsOf` and `sinceLastOpen` are `MutableLiveData<String?>`.** Both have
explicit `postValue(null)` calls (clearing the cached-data label, dismissing the
strip), which will not compile against a non-null type parameter. The other
`String` LiveData never receives null and stays non-null.

---

## `!!` usage

**None.** Zero occurrences across all three files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`filterByTimeframe(ctx, rows)` — the 2-arg overload has no callers.** All 13
   sites use the 3-arg per-chart form. Ported verbatim per the dead-code
   decision; batch 12 can drop it.
2. **`MetricBottomSheet.show(ctx, layoutRes)` — the 2-arg overload has no callers**
   either. Same treatment.
3. **`moveData` / `getMoveData()` are retained with no producer.** `fetchMove()`
   was removed when FRED turned out not to expose the MOVE index; the LiveData
   and its getter remain, and nothing observes them. The in-file comment already
   explains why it was kept.
4. **`statusMsg` LiveData is written by nothing and observed by nothing.** Dead
   since before this batch.
5. **`EconomicViewModel.getLatest` is O(n) per call and is called ~14 times per
   alert evaluation**, each time re-scanning the full series list. Not a problem
   at current data sizes; noting it because `evaluateAlertRules` runs on every
   refresh.

---

## What to exercise on device

This batch is the app's data spine plus both sheets, so almost everything
touches it. A clean rebuild plus:

1. **Cold launch, then pull-to-refresh.** All 18 fetches must complete and the
   spinner must stop — that is the `pendingFetches` counter, which the
   `List<() -> Unit>` change touches directly. If the count were wrong the
   spinner would hang forever or stop early.
2. **Every KPI card and every metric tab shows numbers**, not "—". That is
   `filterBySeries` / `getLatest` feeding all ~13 screens.
3. **Change the chart time range in Settings → Charts**, then open several
   charts. That is the `filterByTimeframe` signature change — the riskiest edit
   in the batch, touching 13 call sites.
4. **Kill the network and pull-to-refresh.** Per-card retry chips should appear;
   tapping one should re-fetch just that series and clear its chip
   (`retrySeries` + `markFailed`/`markRecovered`).
5. **Warm launch** — cached values paint immediately with the "Showing cached
   data · as of …" label, which then disappears once fresh data lands
   (`primeFromCache` → `cacheAsOf.postValue(null)`, the nullable LiveData).
6. **The "Since you last opened" strip** on a warm launch, and its × dismiss.
7. **Settings sheet, every control**: theme toggle (recreates the activity),
   colorblind switch, default-tab chips, news source switches (including the
   "last one is locked" behaviour), AI switches, chart decimals + timeframe,
   notification switches, Refresh now, Clear cache, and the version line.
8. **Tap a KPI card → its metric sheet → "＋ Add alert"**, create a rule, reopen
   and confirm it lists, then remove it. That exercises `MetricBottomSheet` and
   the `AlertRule` interop from 11a together.
9. **The Fed Funds history sheet** from the Overview hero card —
   `MainActivity.java:593` is the one Java call into `MetricBottomSheet.show`,
   and it uses the return value.

Items 1 and 3 are the highest-value: both are structural changes rather than
transliteration, and both fail in ways that look like data problems rather than
code problems.
