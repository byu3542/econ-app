# Kotlin migration — batch 12c (retry-chip helper)

Date: 2026-07-30
Branch: `kotlin/batch-12c`
Net: **+76 / −150 lines** across 11 files (1 new, 10 edited).

**This is the last batch of the Kotlin migration.** With it, batch 12 and the
whole plan in `KOTLIN_MIGRATION.md` are complete.

---

## What changed

Ten chart fragments each carried a byte-identical 14-line copy of the TICKET-18
retry-chip wiring, differing only in which series keys they watched. Verified
identical by direct comparison across all ten before touching anything, not
assumed from the batch-10b note.

New `utils/RetryChip.kt` — one `object` with a single `wire()` function. Each
call site drops from 14 lines to one or three:

```kotlin
// before (×10)
val retryChip: TextView? = view.findViewById(R.id.tvRetry)
val retryKeys = arrayOf(EconomicViewModel.CACHE_TREASURY)
viewModel.getFailedSeries().observe(viewLifecycleOwner) { failed ->
    if (retryChip != null) {
        var hit: String? = null
        if (failed != null) for (k in retryKeys) if (failed.contains(k)) { hit = k; break }
        ...
    }
}

// after
RetryChip.wire(this, view, viewModel, EconomicViewModel.CACHE_TREASURY)
```

| Fragment | Keys watched |
|---|---|
| Bonds | `KEY_BAA`, `KEY_HY` |
| Cpi | `CACHE_CPI` |
| Employment | `CACHE_EMPLOYMENT` |
| Gdp | `CACHE_GDP` |
| Housing | `CACHE_MBS`, `CACHE_TREASURY`, `KEY_HOUSING` |
| Inflation | `CACHE_CPI`, `KEY_PCE` |
| Spreads | `CACHE_TREASURY` |
| Stocks | `KEY_SP500`, `KEY_NASDAQ`, `CACHE_VIX` |
| Wages | `KEY_WAGES`, `CACHE_CPI` |
| Yields | `CACHE_TREASURY` |

### Where it lives, and why

`utils/RetryChip.kt`, as an `object` with a `wire()` function — matching
`TimeframeSelector`'s package and the `object X { fun attach/wire(...) }` shape
used by `PressFeedback`, `TimeframeSelector`, and `AskAnalyst.wireCardLongPress`.
Standing rule 6 (reuse existing conventions) rather than a Kotlin extension
function, which would have read better in isolation but matched nothing else in
the codebase.

The helper imports `ui.EconomicViewModel` from `utils/`, which looks like a
layering inversion. It is already the established pattern: `utils/TimeframeSelector`
and `utils/NotificationHelper` both import from `ui/` today.

No `@JvmStatic` on the new function. No Java callers remain in `app/src/main`,
and the batch-12 decision was to stop adding interop annotations, not just to
stop stripping them.

---

## The one thing that was easy to get wrong

**Iterate the declared keys, not the failed set.**

The obvious Kotlin simplification is `failed.firstOrNull { it in keys }`. That is
a real behaviour change: `getFailedSeries()` returns a `Set<String>`, which has
no guaranteed iteration order, while the original loops over the fragment's own
`retryKeys` array and takes the first hit. When two of a screen's series have
failed at once, the two forms can retry *different* series.

The helper keeps the original direction:

```kotlin
val hit = if (failed == null) null else keys.firstOrNull { failed.contains(it) }
```

This matters on the five multi-key screens — Bonds, Housing, Inflation, Stocks,
Wages. On the five single-key screens the distinction is unobservable. The `keys`
parameter order is now load-bearing, so it is documented in the helper's KDoc.

## One deliberate micro-difference

The original registered the `getFailedSeries()` observer even when
`findViewById(R.id.tvRetry)` returned null, with the observer body then doing
nothing on every emission. The helper returns early instead, so no observer is
registered for a screen whose layout has no `tvRetry`.

Unobservable — the old observer's body was a no-op in exactly that case — but it
is a difference, and worth naming rather than burying. All ten current layouts do
have `tvRetry`, so this path is not exercised today.

---

## Verification

```
./gradlew check   → BUILD SUCCESSFUL (lint + tests + checkBadges)
43 tests, 0 failures
```

Phase 4 greps:
- `retryKeys` / `retryChip` — zero remaining occurrences anywhere in `app/src`.
- `RetryChip.wire` — exactly 10 call sites, one per fragment.
- No fragment was left with a now-unused `TextView` or `R` import (checked
  per-file; all ten still use both for other things).

---

## What to exercise on device

Unlike 12a and 12b, this one is a genuine refactor of live UI wiring, so it
deserves the most attention of the three. The retry chip only appears when a
fetch actually fails, which is the awkward part — it needs network interference
to see at all.

1. **Gradle sync, then Build → Rebuild Project.**
2. **Open all ten chart tabs with working network** — Bonds, CPI, Employment,
   GDP, Housing, Inflation, Spreads, Stocks, Wages, Yields. Confirm **no retry
   chip is visible** on any of them. That is the `GONE` branch, and it is the
   path that runs on every single screen open, so a regression here would be
   constant and obvious.
3. **Force a failure to see the chip at all:** turn off wifi/data, then
   pull-to-refresh on the Dashboard (or cold-start the app) so the fetches fail,
   then visit a chart tab. The retry chip should appear.
4. **Tap the chip with network restored** — confirm it actually refetches and the
   chip disappears once the series lands.
5. **Worth doing on a multi-key screen specifically** (Housing, Stocks, or
   Inflation) since that is where the key-order semantics live. With network off,
   several of that screen's series fail at once; the chip should retry and clear
   normally.
6. **Rotate the device on a chart tab** while the chip is visible — the observer
   is now bound to `fragment.viewLifecycleOwner` passed in from the caller rather
   than referenced directly in the fragment body. Same object, but this is the
   line where a lifecycle mistake would show up, and it would show up as a
   duplicate-observer or a crash on rotation.

If red: paste the exact error back.

---

## Follow-up items spotted, not fixed

1. **`AskAnalyst.openWithQuery`'s KDoc is stale.** It explains the two-overload
   design with "39 Java call sites across 12 fragments and MainActivity bind to
   these exact signatures, and default args alone would not synthesize the 2-arg
   form for them." There are no Java call sites anywhere in `app/src/main` any
   more, so the stated reason no longer holds. The overloads are harmless; the
   comment is now misleading. Likely not the only such comment — a sweep for
   migration-era "Java callers" justifications would be a small, tidy piece of
   work.
2. **The `@JvmStatic`/`@JvmField` question is still open by choice**, not by
   oversight — ~181 and ~129 respectively, of which the four Java test files need
   only 13 and 1. Deliberately dropped from batch 12 (see 12a). Recorded here so
   the next person finds the reasoning rather than re-deriving it.
3. **Earlier follow-ups still open**, none of them in batch 12's agreed scope:
   the `ThumbLoader`/`NewsRepository` ad-hoc thread pools and the un-shut-down
   per-call executor; the uncleaned `AnalystToolExecutor.setLiveSeriesProvider`
   static; the un-removed keyboard `OnGlobalLayoutListener`; the 2026-only FOMC/CPI
   tables in `ReleaseCalendar` and `MainActivity.updateNextFomcLabel` (these
   expire — worth a calendar reminder, not a code fix); `EconomicDataPoint`'s
   Gson-via-`Unsafe` nullability hole; `MainActivity.describeMetricSnapshot`'s
   ten repetitive branches; the duplicated tab-key→`R.id` mapping; the
   now-unreachable options menu; and the dead `ApiConfig.NEWS_API_KEY` from 12a.
4. **`appendGdpBlock`'s separator is NOT a bug** — recorded here because it was
   reported as one in batch 9b's CHANGES file and repeated since. The loop runs
   `for (i in start until rows.size)` with `start = max(0, rows.size - 8)`;
   `start` only moves the beginning, so the final index is always `rows.size - 1`,
   which is exactly where the `i < rows.size - 1` separator guard goes false.
   Simulated at 3, 8, 9, 12 and 20 cached quarters — no trailing separator at any
   size. **Nothing to fix; the 9b note was wrong.**

---

## Migration complete

`app/src/main` is 100% Kotlin (93 files), batches 0–12 are done, and
`./gradlew check` is green. The four files in `app/src/test` remain Java
deliberately — they are the interop regression net, and porting them would make
the `@JvmField`/`@JvmStatic`/package-private traps they guard against invisible.

Explicitly out of scope and still untouched, per the plan's closing section:
Jetpack Compose, and converting the existing threading to coroutines. Both are
their own projects.
