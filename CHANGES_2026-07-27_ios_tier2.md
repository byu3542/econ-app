# CHANGES 2026-07-27 — iOS-Feel Tier 2 (IOS-05 – IOS-09)

Same caveat as Tier 1: verified by reading files back and grepping, not by
building — no Android SDK in this sandbox.

## IOS-05 — iOS segmented control for timeframes

The ticket assumed the timeframe chips were scattered pill drawables (BADGE
TICKET-08 style). In reality there's already **one** shared, reused
implementation: `utils/TimeframeSelector.java` + `res/layout/
view_timeframe_selector.xml`, included via `<include>` and driven by
`TimeframeSelector.attach(...)` in 9 fragments (Stocks, Spreads, Bonds, Gdp,
Cpi, Inflation, Employment, Wages, Housing). That meant the whole ticket could
be done by upgrading **one component**, with zero changes needed at any of
the 9 call sites.

**`view_timeframe_selector.xml` rewritten:** was a plain `LinearLayout` row of
4 `TextView`s whose active one got a programmatically-built gold
`GradientDrawable` background. Now a `FrameLayout` stack: a recessed track
(`bg_segmented_track.xml`, ~8% black, works on both light and navy card
surfaces), a sliding thumb (`bg_segmented_thumb.xml`, gold, `id=
timeframeThumb`), a row of static hairline dividers at the segment
boundaries, and the original `TextView` row on top (id unchanged —
`timeframeSelector` — so nothing calling `findViewById` on it broke).

**New:** `ui/views/SegmentedControl.java` — `slideTo(thumb, target)` /
`snapTo(thumb, target)` size the thumb to match the target segment's measured
width and spring/snap its `translationX`. Same persistent-`SpringAnimation`
pattern as `PressFeedback` (`dampingRatio 0.9f`, `stiffness 900f`) — cached on
the thumb view via `setTag`, so rapid alternating taps retarget the same
spring instead of racing two animations against each other.

**`TimeframeSelector.java`:** `restyle()` now looks up the thumb (a sibling of
the segment row, found via `selector.getParent()`) and calls
`SegmentedControl.slideTo`/`snapTo` instead of building a per-segment
`GradientDrawable`; text-color logic (navy on active, muted on inactive) is
unchanged. First attach snaps (segment widths aren't known before layout, so
it runs inside `selector.post(...)`); every tap after that springs.

**Deliberately simplified:** the hairline dividers are static (always
visible, low alpha) rather than dynamically fading out as the thumb
approaches — the ticket's "fade out as thumb approaches" detail. Doing that
properly needs per-divider distance-to-thumb math re-run every animation
frame; skipped as a secondary visual detail relative to the sliding-thumb
mechanic itself, which is the actual acceptance-tested behavior.

**Eyeball in Android Studio:** on any of the 9 screens with a timeframe row
(e.g. Stocks, Gdp), tap a different range — the gold thumb should slide
under it with a slight overshoot/settle, not just repaint. Tap rapidly back
and forth — the thumb should visibly redirect mid-slide, not queue up a chain
of finished animations.

## IOS-06 — Sheet detents, grabber, and the correct curve

**Found already partially done:** both sheets already have a grabber —
`AiAnalystBottomSheet`'s `dialog_ai_chat.xml` has a plain 32×3dp `View`
handle; `MetricBottomSheet`'s `sheet_metric_wrapper.xml` uses Material's own
`BottomSheetDragHandleView`. Neither needed adding.

**`MetricBottomSheet.java`** (the GDP/CPI/unemployment/etc. status sheets and
Fed Funds history — the simpler of the two sheet types): now calls
`behavior.setFitToContents(false)` + `setHalfExpandedRatio(0.55f)` +
`setState(HALF_EXPANDED)` so it opens to ~55% height and drags to full
instead of snapping straight to its single measured height. Also adds a
`BottomSheetBehavior.BottomSheetCallback` that scales the presenting screen
(`android.R.id.content`) down to as low as 0.94 as the sheet's slide offset
increases, resetting on dismiss/hidden — the "app recedes behind the sheet"
effect.

**`AiAnalystBottomSheet.java`** (the AI chat sheet): **height/detent logic
deliberately left unchanged** — it already forces `fitToContents(true)` +
full `sheetHeight` + `STATE_EXPANDED` immediately on open, and that's
entangled with real, working keyboard-aware resize logic (the
`OnGlobalLayoutListener` that shrinks the sheet when the IME appears).
Retrofitting a half-height detent onto that without being able to run the app
and actually type into it risks breaking the one thing this sheet does that
nothing else in the app does. What *was* added, additively, with no
interaction with the height logic: the same backdrop-scale
`BottomSheetCallback` as `MetricBottomSheet`, plus a defensive scale-reset in
`onDestroyView()` in case the fragment is torn down without passing through
`STATE_HIDDEN`.

**Curve:** `slide_in_up.xml`/`slide_out_down.xml` were a linear 300ms
translate — but they back `Theme.EconomicDashboard.FullScreenDialog`, which a
grep across the whole `java/` tree confirms **nothing in the app currently
applies** (the sheets are `BottomSheetDialog(Fragment)`s using Material's own
`BottomSheetBehavior` motion, not this resource; the theme's own comment says
"kept for reference"). Fixed anyway for correctness — new
`res/interpolator/interpolator_ios_sheet.xml` (a `pathInterpolator`,
`0.32, 0.72, 0, 1`) wired onto both anim files — but this has no visible
effect on the app today, and is called out as such in a comment in the new
interpolator file so nobody mistakes it for the live sheet motion.

**Eyeball in Android Studio:** tap any KPI card that opens a status sheet
(e.g. Overview → GDP card) — it should open to about half the screen height
and let you drag it up to full or down to dismiss; the Overview screen
behind it should visibly shrink slightly while the sheet is open. Open the AI
Analyst (center FAB) — should still open full-height immediately as before,
and the screen behind it should also recede.

## IOS-07 — Swift Charts aesthetic in `ChartHelper`

**Found partially already true:** `chart.setDrawBorders(false)` was already
set in `styleLineChart` — there was no chart border to remove, contrary to
the ticket's "current state."

**`ChartHelper.styleLineChart()` / `declutterDark()`:** X-axis (vertical)
gridlines are now **always off**, unconditionally — previously they were
drawn whenever the user's gridlines setting was on. Y-axis (horizontal)
gridlines now use `enableGridDashedLine(6f, 4f, 0f)` (dashed) instead of a
solid thin line.

**New:** `ChartHelper.styleLineSeries(LineDataSet, color)` — 2dp line width,
`Mode.LINEAR` (no cubic smoothing), no circles/values, and a gradient fill
(`GradientDrawable`, ~18% of the series' own color at the top fading to fully
transparent at the bottom). **Round line caps are not implemented** —
MPAndroidChart v3.1.0 has no public API for `Paint` cap style on a
`LineDataSet`; noted in the method's doc comment rather than silently
dropped.

**Applied to 7 of the 10 charting fragments'** primary series — Stocks,
Spreads, Bonds, Gdp, Inflation, Employment, Yields — replacing their
`setColor/setLineWidth/setDrawCircles/setDrawValues/setMode(CUBIC_BEZIER)`
chains with one call. **Deliberately not applied to Cpi, Wages, or Housing**:
those three draw per-point circles on purpose (`setCircleColor`/
`setCircleRadius`, no `setDrawCircles(false)`) — a distinct, existing,
intentional style for sparser single-series screens. `styleLineSeries` always
hides circles, so applying it there would have silently removed a deliberate
design choice rather than modernizing it. Benchmark/reference lines
(`LimitLine` objects — the zero-line, warn-line, deep-line, target/elevated
lines in Spreads/Inflation) are a structurally different MPAndroidChart class
(`setLineColor`, not `setColor`) and were correctly left untouched throughout.

**Haptic tick on scrub:** `attachCrosshair()` now also registers an
`OnChartValueSelectedListener` that fires `HapticUtil.scrubTick()` once per
*new* data point crossed (tracked by comparing `Entry.getX()` to the last
ticked value), not on every pixel of drag. Uses a different listener slot
than `AskAnalyst.attachChartExplain`'s `OnChartGestureListener` (verified —
no conflict).

**Not implemented:** replacing the floating `ChartMarker` tooltip bubble with
"the value readout replaces the card's header value in place." The existing
marker already shows date+value near the touch point, which is a reasonable
approximation — but the ticket's exact ask needs each fragment's drag gesture
to write into its *own* specific header `TextView` (`tvSp500Value`, etc.),
which isn't something `ChartHelper`'s shared `attachCrosshair()` can do
generically without a callback API that doesn't exist yet. Scoped out as too
large/fragile to retrofit across every charting screen blind in this pass.

**Eyeball in Android Studio:** any chart — no vertical lines at all, no solid
border; horizontal lines (if the gridlines setting is on) should look dashed.
Stocks/Spreads/Bonds/Gdp/Inflation/Employment/Yields charts should have a
soft gradient fill under the line and a straight (not curved) line between
points. Cpi/Wages/Housing charts should look unchanged (still show dots).
Drag across any chart with a crosshair — should feel a light tick each time
the highlighted point changes.

## IOS-08 — Inset grouped lists

**Found already mostly done:** Settings sections are already
`MaterialCardView`s (`style="@style/SettingsCard"`) with `cardCornerRadius
12dp`, a 1dp `divider_sand` stroke, `cardElevation 0dp` — functionally an
inset-grouped card already, just not built from the exact drawable/dimen
names the ticket assumed. Left this structure alone rather than rebuilding it
from scratch.

**What was actually missing and got added:**
- New `res/drawable/divider_hairline_inset.xml` (flat `divider_sand` color)
  + `dimen/divider_hairline_height` (0.5dp). Inserted as `<View>` separators
  between consecutive rows in the three cards that have more than one
  `SettingsRow` in sequence: **News Sources** (3 dividers between its 4
  rows), **AI Analyst** (1 divider between its 2 rows, not before the
  destructive button below them), **Notifications** (2 dividers between its
  3 rows). Cards with only one row (Appearance, Data, Charts) don't need
  one. "Absent after the last row" is satisfied by construction — dividers
  are only ever placed *between* rows, never trailing.
- Because the rows already sit inside each card's own `contentPadding`
  (16dp), a `match_parent`-width divider is already flush with the row
  text rather than the card edge — no extra inset math needed.
- `SettingsSectionHeader` style (`themes.xml`): was 11sp (`text_caption`),
  Title Case, `text_muted`. Changed to 13sp (`text_secondary` dimen),
  `textAllCaps="true"`, `text_secondary` color — matching the ticket's "13sp
  uppercase text_secondary" exactly. Confirmed this style is used nowhere
  else, so the change is contained to Settings.

**Not applicable:** "trailing chevron on any row that navigates" — every row
in `sheet_settings.xml` is a switch, button, or toggle group; none of them
navigate to another screen, so there's nothing to add a chevron to here.

**Eyeball in Android Studio:** open Settings — News Sources, AI Analyst, and
Notifications cards should each show thin hairlines between their rows (not
touching the card's left/right edges, not appearing after the last row in
each card). Section headers ("APPEARANCE", "DATA", etc.) should render
uppercase and slightly larger than before.

## IOS-09 — Haptics

**New:** `utils/HapticUtil.java` — `selection()` (`SEGMENT_TICK` on API 34+,
else `CLOCK_TICK`), `confirm()` (`CONFIRM` on API 30+, else `CLOCK_TICK`),
`reject()` (`REJECT` on API 30+, else `LONG_PRESS`), `scrubTick()`
(`CLOCK_TICK`). Every method is a no-op if `SettingsManager.KEY_HAPTICS` is
off (new key, **default true**).

**Wired in:**
- Tab switch — `MainActivity`'s `bottomNav.setOnItemSelectedListener` calls
  `HapticUtil.selection()`.
- Segment change — `TimeframeSelector`'s tap handler now calls
  `HapticUtil.selection()` instead of a bare `performHapticFeedback` call.
- Chart scrub — `ChartHelper.attachCrosshair()`'s new per-point tick (IOS-07)
  routes through `HapticUtil.scrubTick()`.
- Refresh complete — `DashboardFragment`'s existing "refresh finished" haptic
  now routes through `HapticUtil.confirm()` instead of a bare
  `CONTEXT_CLICK` call.
- Failed fetch — `MainActivity.observeViewModel()`'s error observer (which
  previously only logged) now also calls `HapticUtil.reject()`.

**Settings toggle:** new switch row in the Appearance card of
`sheet_settings.xml` ("Haptics" / new strings `haptics_title`/`haptics_sub`),
bound in `SettingsBottomSheet.setupTheme()` via the existing generic
`bindSwitch(...)` helper, default on.

**Not touched:** the several *other* existing `performHapticFeedback` call
sites in the app (card-tap `CONTEXT_CLICK` in `DashboardFragment`,
`AiAnalystBottomSheet`'s send-message `KEYBOARD_TAP`) — these are outside the
ticket's explicitly named trigger list (tab switch, segment change, refresh
complete, failed fetch, chart scrub) and weren't touched to keep this change
scoped to what was asked, though they'd be reasonable to route through
`HapticUtil` too in a follow-up for full settings-gate coverage.

**Eyeball in Android Studio:** switch bottom-nav tabs and tap a timeframe
segment — should feel a light tick on both. Pull to refresh on Overview and
let it finish — should feel a distinct, slightly stronger confirm tick versus
the selection tick. Turn off network and force a refresh — should feel a
reject-style tick on the error. Turn off "Haptics" in Settings → Appearance —
all of the above should go silent.

## Final verification notes (applies to both tiers)

- Every new Java/XML file listed above was confirmed to exist on disk.
- All edited/new XML files were parsed with Python's `xml.dom.minidom` —
  all well-formed, no unclosed tags.
- Grepped for leftover references to renamed/removed views
  (`tvHeaderLine1`, `tvHeaderQuarter`) — none found outside this change set.
- `androidx.dynamicanimation` was not previously a dependency; added once in
  `app/build.gradle` and used by both `PressFeedback` and `SegmentedControl`.
- Not started: Tier 3 (IOS-10 push transitions/predictive back, IOS-11 type
  scale, IOS-12 semantic color layer, IOS-13 nav indicator opinion call).
