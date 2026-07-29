# Kotlin Migration — Batch 9b

Second of three commits splitting batch 9. This one is the **stateful
plumbing**: 3 files, ~721 lines, touching Room, `EconomicViewModel`,
`org.json`, MPAndroidChart, and static mutable state.

Branch: `kotlin/batch-9b`. Builds on 9a (merged).

**`analyst/` is now 100% Kotlin.** Only **9c** remains:
`AiAnalystBottomSheet` (1,625 lines), which lives in `ui/`.

---

## Files converted

| File | Kotlin shape | Interop annotations |
|---|---|---|
| `analyst/AskAnalyst.java` → `.kt` | `object` | `@JvmStatic` ×4 |
| `analyst/HistoricalContextBuilder.java` → `.kt` | `object` | `@JvmStatic` ×1, `private const val TAG` |
| `analyst/AnalystToolExecutor.java` → `.kt` | `object` + `fun interface LiveSeriesProvider` | `@JvmStatic` ×4, `@JvmField` ×2, `@Volatile`, `@Throws` |

All 3 `.java` originals deleted. **No other file was touched.**

---

## The decision that would have hidden itself

**`LiveSeriesProvider` is declared `fun interface`, not a plain interface.**

Its only implementation is the lambda at `AiAnalystBottomSheet.java:260`. That
file is still Java in this commit, and **Java SAM-converts to any
single-abstract-method interface regardless of source language** — so a plain
Kotlin `interface` would compile green here and break in 9c, when the sheet
becomes Kotlin and a *Kotlin* lambda no longer converts.

This is the one defect in the batch-9 split that a green 9b build cannot catch,
which is exactly why it had to be decided now rather than discovered later.

Return type is `List<EconomicDataPoint>?` — the Java lambda returns `null` in
several branches, and `ChatAdapter.kt:265` already declares its receiver as
`List<EconomicDataPoint>?`.

**Related trap, also handled:** the backing field is named `liveProvider`, not
`liveSeriesProvider`. A Kotlin property of the original name synthesizes a
`setLiveSeriesProvider` accessor that collides with the explicit public function
of the same name — *"platform declaration clash: accessor and function have the
same JVM signature."* The public function name is load-bearing for
`AiAnalystBottomSheet`, so the private field is what got renamed.

---

## Other interop decisions

**1. `AskAnalyst`'s reference parameters are nullable, and this is mandatory.**
`activity`, `query`, `card`, and `chart` are all `T?`. All three public entry
points defensively null-check and silently return. The 39 Java call sites across
12 fragments can genuinely pass null — `DashboardFragment.java:243` guards a
`findViewById` result precisely because it may be null. Declaring these non-null
would make Kotlin's generated `checkNotNullParameter` **throw** where the code
currently no-ops: a runtime crash at fragment setup, invisible at compile time.

**2. Two explicit `openWithQuery` overloads, not default arguments.** Default
args alone would not synthesize the 2-arg form for `MainActivity.java:148`,
`DashboardFragment.java:263`, and `NewsAdapter.kt:152`. `@JvmOverloads` would
also work but adds a synthetic 3-arg overload nobody wants; two plain functions
match the existing shape exactly.

**3. `OnChartGestureListener` is an object expression with all seven
overrides.** It has seven abstract methods, so it is **not** a SAM type — a
lambda is impossible. All four empty overrides are reproduced, and
`onChartGestureEnd`'s `chart.highlightValue(null)` (TICKET-23 crosshair clear)
is preserved.

**4. MPAndroidChart accessed through explicit `.getX()` calls**, matching the
convention set in `ChartHelper.kt` (migration lesson 12): `getData()`,
`getDataSetCount()`, `getDataSets()`, `getEntryCount()`, `getLabel()`, `getY()`,
`getYMin()`, `getYMax()`, and `setOnChartGestureListener(...)`. The library
cannot be built against here, so its accessor shapes are never assumed.

**5. Every `.getValue()` / `.getDate()` / `.getUnit()` on a Kotlin data class
became property access** (`EconomicHistoryEntry`, `EconomicDataPoint`,
`NewsItem`), while `dao.getCount()`, `dao.getSeriesSync()`,
`YieldDatabase.economicHistoryDao()`, and the `EconomicSeriesRegistry`
accessors **stay functions with parens** — they are Kotlin-declared functions,
not properties, so property syntax would not compile. `DerivedMetricsBuilder`
already documents this hazard; the same split applies here.

---

## Null checks that became statically dead

These are removed, not preserved, because the Kotlin owner declares non-null and
a `== null` test against a non-null type does not compile cleanly:

- `dao.getSeriesSync()` returns non-null `List<EconomicHistoryEntry>` — the four
  `rows == null` checks in `HistoricalContextBuilder` and one in
  `AnalystToolExecutor` collapse to `isEmpty()`.
- `NewsRepository.cachedItems` is non-null — `items == null` collapses.
- `EconomicDataPoint.date` / `.unit` are non-null `String` — `d == null`
  collapses to `d.length < 7`, and
  `dp.getUnit() != null && !dp.getUnit().isEmpty()` collapses to
  `dp.unit.isNotEmpty()`.
- `EconomicHistoryEntry.date` is non-null — the `e.getDate() != null` guard in
  `appendMonthlySeries` collapses.

These are safe because those models have been Kotlin since batch 3 and construct
with generated null-checks — a null would already be crashing at construction
today. `dateToQuarterLabel`'s parameter is deliberately left `String?` anyway, to
preserve its documented "falls back to the raw date" contract.

`NewsItem.title/summary/tag/source` are genuinely `String?`, so **every** null
check in `getHeadlines` is preserved, with `title`/`summary` captured into local
`val`s first (migration lesson 6 — Kotlin will not smart-cast another class's
`var` property).

---

## Deliberately left non-idiomatic

- **`Double.MIN_VALUE` kept as a max-seed** in `appendYieldLine` and
  `appendUnemploymentBlock`. It is the smallest *positive* double (~4.9e-324),
  not the most negative — a latent bug for an all-negative series. Kotlin's
  `Double.MIN_VALUE` is identical to Java's, so the port is faithful. See
  follow-up #1; fixing it here would be a behavior change smuggled into a syntax
  batch.
- **Every tool-result string copied character-for-character**, including
  `"Invalid series_id"`, `"No cached data"`, `"No data loaded"`, and the
  pre-computed-YoY sentence. These are a behavioral contract under AI Law 16 —
  the system prompt in `AiAnalystBottomSheet` quotes them back verbatim, so a
  reworded string silently breaks the "do not retry" instruction.
- **`LinkedHashMap` kept explicit** in `appendMonthlySeries` — the
  "last reading of the month wins" semantics depends on insertion-order
  overwrite.
- **`var window: List<EconomicDataPoint>`** — reassigned from an `ArrayList` to
  a `subList` view, so the reference is `var` while the type stays read-only.

---

## `!!` usage

**None.** Zero occurrences across all three files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`Double.MIN_VALUE` max-seed** (see above). Harmless today — the two
   affected sections are yields and unemployment, which are always positive —
   but wrong if either ever goes negative. Fix is `-Double.MAX_VALUE`.

2. **`appendGdpBlock`'s separator condition looks off by one.** It appends
   `" | "` when `i < rows.size - 1`, but the loop starts at
   `max(0, rows.size - 8)` — so when more than 8 quarters are cached, the last
   rendered quarter still gets a trailing `" | "`. Pre-existing; unchanged.

3. **`AnalystToolExecutor` holds a static `@Volatile` provider set from a
   fragment.** `AiAnalystBottomSheet` wires it in `onViewCreated` and never
   clears it in `onDestroyView`. The provider itself holds a `WeakReference` to
   the ViewModel, so this does not leak — but the static reference outliving the
   sheet is worth a look when 9c lands.

---

## What to exercise on device

The compile-time surface is wide (39 Java call sites), so a clean rebuild is the
main gate. The runtime risks are the listener attachments, which fail on
*interaction*, not on load.

1. **Long-press a metric card** on Dashboard, and again on Bonds / CPI /
   Employment / GDP / Inflation / Spreads / Stocks / Wages. The
   "Ask Econ Analyst" menu must appear and the answer must quote the on-screen
   number. This is `wireCardLongPress` — the nullable-parameter decision above
   is exactly what this exercises.
2. **Long-press a chart** (Yields, CPI, Housing, Wages, Spreads, Stocks) → the
   "✨ Explain this chart" menu. This is the seven-method
   `OnChartGestureListener` object expression; a missing override would surface
   here.
3. **Drag-scrub a chart, then release** — the crosshair marker must clear. That
   is `onChartGestureEnd` specifically, which is easy to lose in an object
   expression.
4. **Ask the analyst a history question** ("what was the 10Y last September?")
   so `HistoricalContextBuilder` runs its Room queries and the tool loop calls
   `get_series`.
5. **Ask a CPI/PCE question** — the reply should cite a YoY figure. That
   exercises the pre-computed-YoY path with its verbatim strings.
6. **Ask a news question** ("what's in the news about the Fed?") →
   `get_headlines`.
7. **Confirm the daily brief still works** if you have it enabled —
   `DailyBriefWorker.kt` calls `HistoricalContextBuilder.build`.

Item 2 is the highest-value check: it is the only path where a mistake compiles
cleanly and fails silently at gesture time.
