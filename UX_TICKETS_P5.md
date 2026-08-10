# UX Implementation Tickets P5 - Economic Dashboard

Tickets derived from the UX audit on 2026-08-10 (`UX_AUDIT_2026-08-10.md`), mapped to the real
codebase. The app is now fully Kotlin. Each ticket notes what **already exists** so you don't
rebuild it, the concrete change, the files to touch, and how to know it's done.

**Effort key:** S = <half day · M = half-2 days · L = >2 days
**Priority key:** P1 = do first (high impact) · P2 = next · P3 = polish

> Workflow reminder: additive changes only, preserve existing ids/APIs. No SDK in the audit
> sandbox - verify by inspection and build in Android Studio.

---

## P1 - Do first

### TICKET-30 - Wire MetricBottomSheet on the six unrouted leaf screens
**Laws:** Progressive Disclosure, Jakob's Law
**Effort:** M
**Current state:** `ui/MetricBottomSheet.kt` is complete and wired in `DashboardFragment.kt`,
`WagesFragment.kt`, `InflationFragment.kt`, `CpiFragment.kt`, `GdpFragment.kt`. The other six
leaf fragments never reference it - taps on their metric cards do nothing.
**Change:** Add card-root click listeners opening `MetricBottomSheet` for each headline metric
on Stocks, Bonds, Spreads, Employment, Housing, Yields, following the exact pattern already in
`GdpFragment.kt`. Route through `PressFeedback.attach` where not already attached.
**Files:** `ui/fragments/StocksFragment.kt`, `BondsFragment.kt`, `SpreadsFragment.kt`,
`EmploymentFragment.kt`, `HousingFragment.kt`, `YieldsFragment.kt`
**Acceptance:**
- Tapping any metric card on all 10 leaf screens opens the detail sheet with chart/history.
- Existing sheet behavior on Dashboard/Wages/Inflation/Cpi/Gdp unchanged.

### TICKET-31 - Restore (or formally retire) the "since you last opened" strip
**Laws:** Zeigarnik Effect, Feedback & Visibility
**Effort:** M
**Current state:** Shipped in the P4 era (`getSinceLastOpen()` on the ViewModel per project
history), but no `sinceLastOpen`/`lastOpen` symbol exists anywhere in `app/src/main/java`
today - most likely removed in Kotlin batch 12a's dead-code pass. `SettingsManager.kt` still
manages analyst last-seen prefs, so the persistence pattern to copy exists.
**Change:** Decide intent first. If restoring: re-add a `getSinceLastOpen()` on
`ui/EconomicViewModel.kt` (diff cached values against a persisted last-open snapshot) and a
dismissible strip at the top of `fragment_dashboard.xml`. If retiring: record the decision in
a CHANGES note so future audits stop flagging it.
**Files:** `ui/EconomicViewModel.kt`, `ui/fragments/DashboardFragment.kt`,
`res/layout/fragment_dashboard.xml`, `utils/SettingsManager.kt`
**Acceptance:**
- Reopening the app after values changed shows a one-line "Since you last opened" summary, or
  a dated CHANGES entry documents deliberate removal.

### TICKET-32 - TalkBack pass: contentDescription on data surfaces
**Laws:** Accessibility (WCAG), Recognition Over Recall
**Effort:** M
**Current state:** Direction encoding is already colorblind-safe (`utils/DeltaFormatter.kt`:
glyph + sign + color). But `fragment_dashboard.xml` and `card_kpi_badge.xml` contain zero
`contentDescription`, and coverage elsewhere is single-digit. Charts, sparklines, and
icon-only buttons are silent to screen readers.
**Change:** Set dynamic `contentDescription` on each KPI card root at bind time ("Unemployment
4.1 percent, down 0.1 since last month") - build the string from `NumberFormatUtil` +
`DeltaFormatter` so it stays consistent. Mark decorative sparklines
`importantForAccessibility="no"`. Add static descriptions to icon buttons and give each chart
a summary description.
**Files:** `ui/fragments/DashboardFragment.kt` (bind path), the 10 leaf fragments' value
setters, `res/layout/card_kpi_badge.xml`, `res/layout/fragment_*.xml` icon buttons
**Acceptance:**
- TalkBack reads value + unit + direction on every KPI card on Dashboard and all leaf screens.
- Sparklines/decorative elements are skipped; icon buttons announce their action.

## P2 - Next

### TICKET-33 - Finish skeleton + crosshair parity (Wages, Cpi, Yields)
**Laws:** Doherty Threshold, Jakob's Law
**Effort:** S
**Current state:** `ui/views/SkeletonController.kt` is used by 8 of 10 leaf fragments -
`WagesFragment.kt` and `CpiFragment.kt` still lack it. `ChartHelper.attachCrosshair` is on 9
of 10 - `YieldsFragment.kt` lacks it.
**Change:** Adopt `SkeletonController` in Wages and Cpi exactly as in `InflationFragment.kt`;
attach the crosshair in Yields as in `BondsFragment.kt`.
**Files:** `ui/fragments/WagesFragment.kt`, `CpiFragment.kt`, `YieldsFragment.kt`, plus
skeleton views in `res/layout/fragment_wages.xml`, `fragment_cpi.xml`
**Acceptance:**
- Cold-open of Wages/Cpi shows skeletons (no spinner/blank) before data paints.
- Long-press on the Yields chart shows the crosshair marker like every other chart.

### TICKET-34 - Timeframe selector on Yields and Treasury
**Laws:** Jakob's Law, Recognition Over Recall
**Effort:** S
**Current state:** `utils/TimeframeSelector.kt` + `view_timeframe_selector.xml` are included
by 9 chart layouts; `fragment_yields.xml` and `fragment_treasury.xml` have no timeframe
control, so those charts are stuck on a fixed range.
**Change:** Add the `view_timeframe_selector` include above each chart and wire
`TimeframeSelector.attach(...)` with a chartId, mirroring `fragment_bonds.xml` /
`BondsFragment.kt`. The expanded 34-series Room cache already holds the history to filter.
**Files:** `res/layout/fragment_yields.xml`, `fragment_treasury.xml`,
`ui/fragments/YieldsFragment.kt`, the treasury host fragment
**Acceptance:**
- Yields and Treasury charts re-render on timeframe taps; selection persists via
  `SettingsManager` like other screens.

### TICKET-35 - Surface DerivedMetricsBuilder outputs on cards
**Laws:** Tesler's Law
**Effort:** M
**Current state:** `analyst/DerivedMetricsBuilder.kt` computes real wage growth, yield
spreads, and YoY deltas, but its sole consumer is `ui/AiAnalystBottomSheet.kt` - none of it
reaches a card or the metric sheet.
**Change:** Show the relevant derived value as a secondary line on the matching screens:
real wage growth on Wages, computed spread on Spreads, YoY on Cpi/Inflation. Reuse
`NumberFormatUtil`/`DeltaFormatter` for formatting; add one row to `MetricBottomSheet` for the
derived value with a one-line "how it's computed" note.
**Files:** `analyst/DerivedMetricsBuilder.kt` (expose a non-AI accessor if needed),
`ui/fragments/WagesFragment.kt`, `SpreadsFragment.kt`, `CpiFragment.kt`,
`InflationFragment.kt`, `ui/MetricBottomSheet.kt`
**Acceptance:**
- Wages screen shows "Real wage growth: X%" computed for the user, without opening the AI.
- Values match what the AI analyst reports for the same metrics.

## P3 - Polish

### TICKET-36 - Direct labels on the Spreads comparison chart
**Laws:** Uniform Connectedness, Occam's Razor
**Effort:** S
**Current state:** `ChartHelper.labelLineEnd`/direct-label support exists and is used by
`InflationFragment.kt` and `WagesFragment.kt`; the Spreads multi-series chart still shows a
detached legend.
**Change:** Apply the same direct end-of-line labeling in `SpreadsFragment.kt` and hide the
detached legend.
**Files:** `ui/fragments/SpreadsFragment.kt`, `utils/ChartHelper.kt` (reuse, no changes
expected)
**Acceptance:**
- Each series on Spreads is labeled at its line end in the series color; no separate legend.

### TICKET-37 - Adopt or delete the unused card variants (navy/gold)
**Laws:** Similarity / Common Region
**Effort:** S
**Current state:** TICKET-29 found `card_kpi_navy.xml` and `card_kpi_gold.xml` unreferenced;
that is still true - no `fragment_*.xml` includes them, only `card_kpi_badge.xml` is live.
**Change:** Either adopt the gold variant for the single hero card per screen (pairs with the
Von Restorff hero already in place) or delete both layouts and note it in CHANGES, so the
design system reflects reality.
**Files:** `res/layout/card_kpi_navy.xml`, `card_kpi_gold.xml`, adopting fragments' layouts
**Acceptance:**
- Zero unreferenced card layouts remain, or the gold variant renders the hero on each screen.

## Coverage map (law -> ticket)
- Jakob's Law -> 30, 33, 34
- Zeigarnik Effect -> 31
- Accessibility -> 32
- Doherty Threshold -> 33
- Recognition Over Recall -> 32, 34
- Tesler's Law -> 35
- Uniform Connectedness -> 36
- Similarity / Common Region -> 37
- Progressive Disclosure -> 30
