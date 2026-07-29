# Kotlin Migration — Batch 10b

Second of three commits splitting batch 10. **The ten chart/metric fragments**,
~2,877 lines, **one commit each** per the migration doc.

Branch: `kotlin/batch-10b`. Builds on 10a (merged).

Only `DashboardFragment` (732 lines) remains — that's **10c**.

---

## Files converted (one commit each, in order)

| Commit | File | Lines |
|---|---|---|
| 1 | `BondsFragment` | 205 |
| 2 | `CpiFragment` | 207 |
| 3 | `SpreadsFragment` | 209 |
| 4 | `GdpFragment` | 211 |
| 5 | `YieldsFragment` | 216 |
| 6 | `EmploymentFragment` | 246 |
| 7 | `StocksFragment` | 249 |
| 8 | `WagesFragment` | 304 |
| 9 | `InflationFragment` | 385 |
| 10 | `HousingFragment` | 645 |

All 10 `.java` originals deleted. **No file outside `ui/fragments/` was touched.**

---

## The pattern, established once and applied ten times

These fragments are near-identical in shape, so the value of this batch is that
one set of decisions covers all ten. `BondsFragment` (commit 1) is the reference.

**1. `lateinit var` for view fields, nullable `var` only for data.**
The migration doc calls for `lateinit var` on view/binding fields initialised in
`onCreateView`/`onViewCreated`, and that is what these use. Data fields that are
genuinely absent until a LiveData emission (`currentBaaData`, `lastGdpData`,
`currentMbsData`, …) stay nullable `var`.

**2. Java null guards became `::field.isInitialized`.** The Java had defensive
`if (swappableChart == null) return` and `if (skeleton != null) skeleton.reveal()`
checks. Against a `lateinit` field, `== null` will not compile, and simply
deleting the guard would change behaviour. `::swappableChart.isInitialized` is
the exact Kotlin equivalent and is used everywhere the Java had a guard.

**3. `binding` stays a nullable `var`, captured into a local `val`.**
`val b = binding ?: return` at the top of `onViewCreated`, then `b.xxx`
throughout. **Zero `!!` across all ten files.**

**4. Explicit `.setXxx()` on every MPAndroidChart mutation** (migration lesson
12) — including `LimitLine.setLineColor/setLineWidth/setTextColor/setTextSize`
and `chart.setData(...)`. Property syntax is used **only** for getter-only reads
that fetch a sub-object: `chart.xAxis`, `chart.axisLeft`, `chart.axisRight`,
`chart.legend`, `chart.description`. The library can't be built against here, so
its accessor pairs are never assumed.

**5. Int literals widened to `Double` at Java call sites.** Kotlin does not
auto-widen. `Benchmarks.bondSpread(latest, 5, 8)` → `(latest, 5.0, 8.0)`;
`stockLevel(latest, 3500, 4000)` → `(latest, 3500.0, 4000.0)`. Signatures were
checked against `Benchmarks.java` rather than assumed — all take `double`.

**6. `.getValue()`/`.getDate()` on `EconomicDataPoint` → `.value`/`.date`,**
while `LiveData.getValue()` and every `EconomicViewModel` getter keep their
explicit call form (both are still Java). The two appear on the same line in
places and are not interchangeable — same hazard flagged in `DerivedMetricsBuilder`.

**7. `TimeframeSelector.attach(sel, id) { … }` as a trailing lambda.**
`OnTimeframeChange` was already declared `fun interface` in batch 5, so this
converts cleanly. Checked before converting — a plain interface here would have
been the `LiveSeriesProvider` trap all over again.

---

## Deliberately left non-idiomatic

- **The dense one-line-per-statement chart styling** (`styleChart`) is preserved
  line-for-line rather than reflowed. It is the same ~15 lines in eight files;
  reformatting would bury the real diff.
- **`Math.max`/`Math.min`/`Math.abs`** kept over Kotlin's `maxOf`/`minOf`/`abs`.
- **`InflationFragment.showBenchmarkDialog(int)`** — the 1-arg overload has no
  caller. Ported verbatim per the batch-9 dead-code decision, with a comment.
- **`HousingFragment`'s index-walking loops** (`buildEntriesForDates`, the Fed
  MBS alignment loop) keep their manual `while` form rather than becoming
  `zip`/`filter` chains — the two cursors advance independently and a functional
  rewrite would not be a syntax-only change.
- **`Double.MAX_VALUE` min-seed in `EmploymentFragment`** is correct as-is
  (unlike the `Double.MIN_VALUE` max-seed flagged in 9b) — kept verbatim.

---

## `!!` usage

**None.** Zero occurrences across all ten files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`BondsFragment`/`SpreadsFragment`/`EmploymentFragment`/`StocksFragment` all
   assign `activeCard` immediately before calling `setActiveCard(sameValue)`.**
   The field write is redundant — `setActiveCard` takes the value as a parameter
   and the field is only read in `buildSwappableChart`. Harmless, but it is the
   kind of thing that drifts out of sync. Pre-existing in all four.

2. **`HousingFragment.makeLineDataSet(..., filled: Boolean)` — the `filled`
   parameter is passed `false` at all five call sites** and only guards a
   `setDrawCircles(false)` call. Effectively dead.

3. **`InflationFragment` computes `pceBase` but the PCE series is skipped
   entirely when `pceData` is null**, leaving a two-line chart with a three-line
   legend arrangement. Pre-existing; unchanged.

4. **The retry-chip block is copy-pasted verbatim across all ten fragments**
   (~14 lines each, ~140 lines total). It differs only in the `retryKeys` array.
   A single `wireRetryChip(view, viewModel, vararg keys)` helper would remove
   ~125 lines. Good batch-12 candidate — deliberately **not** done here, since
   extracting a shared helper mid-migration is a refactor, not a syntax port.

---

## What to exercise on device

Every one of these is a visible screen, and the failure modes are visual rather
than crash-y. A clean rebuild plus a pass through each tab:

1. **Markets → STOCKS / BONDS / YIELDS / SPREADS**, then **Economy → GDP /
   Employment / Inflation / Housing**, plus the **CPI** and **Wages** screens.
   For each: the KPI cards show numbers (not "—"), the status badge word and its
   colour look right, and the chart draws.
2. **Tap between the swappable KPI cards** on Bonds, Spreads, Employment,
   Stocks, and Housing. The gold active-card border must move, the chart title
   must change, and the chart must redraw with the new series. This exercises
   `setActiveCard` + `buildSwappableChart` — the most-touched logic in the batch.
3. **Change the time range** on any chart with an inline range switcher. Both
   charts on CPI, Wages, Inflation, and Housing are driven by one control, so
   confirm *both* redraw.
4. **Long-press a card and a chart** on a few of these screens — that is
   `AskAnalyst`, already converted in 9b but wired from here.
5. **Housing specifically:** confirm Existing Home Sales reads like "4.09M" and
   not "4,090.00M", and that the MBS chart shows two gold/grey lines on the left
   axis and a coral mortgage line on the right. That dual-axis setup is the most
   intricate chart config in the batch.
6. **Yields:** the maturity rows should zebra-stripe (alternate rows tinted) and
   the change column should show ▲/▼ with basis points, or "—" when flat.

Item 2 is the highest-value check; item 5 is where a subtle port error would be
most visible.
