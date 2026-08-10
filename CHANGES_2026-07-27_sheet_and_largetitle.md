# CHANGES 2026-07-27 — Metric sheet clipping + IOS-02 collapse quality

Follow-up to `CHANGES_2026-07-27_ios_tier2.md` (IOS-06 sheet detents) and
`CHANGES_2026-07-27_ios_nav_fixes.md` (IOS-02 large title). Reported from
device screenshots after both shipped.

No Android SDK in this sandbox — everything below was verified by reading the
edited files back and reasoning about layout/scroll math, **not** by building.
Eyeball checklist at the end of each section.

---

## Defect 1 — metric benchmark sheets were clipped and mis-anchored

**Root cause** (confirmed against source, `ui/MetricBottomSheet.java:46-48`):
`behavior.setFitToContents(false)` tells `BottomSheetBehavior` to position the
sheet view's top edge by offset and expects that view to span the parent. But
`sheet_metric_wrapper.xml`'s root `LinearLayout` was `wrap_content`, its
`sheetContent` `FrameLayout` was `wrap_content`, and every injected content
root (`dialog_treasury_status.xml` etc.) was `wrap_content` too — nothing in
the chain could actually fill the parent, and nothing scrolled. Half-expanded,
the sheet's own content height exceeded the 45% left below the 55% detent, and
the overflow (DISMISS row, "+ Add alert") ran off the bottom with no scroller
to reach it. Expanded, the view still measured `wrap_content`, so the sheet's
bottom landed mid-screen with the activity showing through below it.

**Shipped:**

1. **`sheet_metric_wrapper.xml`** — root `LinearLayout` → `match_parent`. The
   drag handle stays fixed at the top; `sheetContent` now lives inside a new
   `androidx.core.widget.NestedScrollView` (`id=sheetScroll`,
   `layout_height="0dp"`, `layout_weight="1"`, `fillViewport="true"`,
   `clipToPadding="false"`). `fillViewport` is required, not decorative:
   without it a short benchmark list stops stretching and the navy background
   ends mid-sheet for the small metrics.
2. **`EdgeToEdgeInsets`** — added `getSystemBottomInsetPx()` /
   `getSystemTopInsetPx()`, thin public accessors over the already-cached raw
   `systemBars()` insets. `MetricBottomSheet.show()` uses the bottom one to
   pad `sheetScroll` so the last row and "+ Add alert" pill clear the gesture
   inset. Deliberately the **raw** systemBars inset, not
   `EdgeToEdgeInsets`'s nav-wrapper-inclusive bottom padding (used by
   `applyBottomInset`/`applyBothInsets` elsewhere) — the sheet sits above the
   bottom nav, not beside it, so folding in `bottomNavWrapper`'s height would
   over-pad.
3. **`MetricBottomSheet.java`**:
   - `setExpandedOffset(statusBarInsetPx + 8dp)` — the expanded sheet now
     stops short of the status bar instead of going flush to y=0. Flush-to-top
     read as a full-screen dialog rather than a sheet with Overview still
     peeking above it.
   - `setState(STATE_HALF_EXPANDED)` moved from before `dialog.show()` to a
     `content.post()` callback that runs after the first layout pass (setting
     state before layout can re-resolve to `STATE_COLLAPSED` on first
     measure).
   - That same post-layout callback now measures `content.getHeight()`
     against 55% of screen height. Short content (e.g. a metric with only 2-3
     benchmark rows) gets `setFitToContents(true)` + `setSkipCollapsed(true)`
     + `STATE_EXPANDED` instead of the half detent, so it sizes to its own
     content rather than opening on dead space under a half-height sheet.
   - Left `setFitToContents(false)` / `setHalfExpandedRatio(0.55f)` in place
     for the normal case — that's the IOS-06 medium-detent behavior the user
     wants, not something this defect asked to change.
4. **Corner radius on the receding backdrop** — IOS-06 asked for `scale 0.94`
   **+ 12dp corner radius** on the presenting screen; only the scale had
   shipped. Added a `ViewOutlineProvider` + `setClipToOutline(true)` on
   `android.R.id.content`, with the radius driven by the same `onSlide`
   callback that drives the scale (0 → 12dp as `slideOffset` goes 0 → 1), and
   reset to 0 in the same two places the scale already resets
   (`STATE_HIDDEN`, `setOnDismissListener`).

**Deliberately not touched:** `AiAnalystBottomSheet.java` (`fitToContents(true)`
+ explicit peek height) and `SettingsBottomSheet.java` (expanded +
`skipCollapsed`) are both internally consistent on their own terms and don't
have this bug — only `MetricBottomSheet` combined `fitToContents(false)` with
a `wrap_content` view chain. Per the ticket's explicit constraint, neither was
"harmonized" into this change.

**Eyeball in Android Studio:** Overview → tap "10Y-3M SPREAD" → sheet should
open to ~55% height with the DISMISS row and "+ Add alert" pill fully visible
and reachable (scroll if the list is long). Drag to full — the sheet's bottom
should now reach the screen bottom (clearing the gesture pill), with its top
stopping just below the status bar rather than flush to it, and Overview
should visibly shrink *and* pick up rounded corners behind it. Try a
short-content metric sheet (fewer benchmark rows) — it should open sized to
its content, not half-height with empty space underneath.

---

## Defect 2 — IOS-02 large-title collapse quality

### 2a — crossfade decoupled from the physical hand-off

**Root cause** (`ui/views/LargeTitleController.java`): progress was
`scrollY / 56dp`, a constant unrelated to where the large title block actually
sits (~90dp of its own content into the scroll). The large title finished
fading in open space, well before reaching the bar, while the compact title
reached full opacity before the large one had moved at all — and because both
ramps were the same linear function of the same `progress`, the two titles
sat at alpha 0.5 simultaneously through the whole middle of the gesture (the
visible double-title ghost band), with the large title's glyphs guillotined by
the header's hard bottom edge while still nearly opaque.

**Shipped** — `LargeTitleController` rewritten to drive progress from
geometry, not a constant:

- **`EdgeToEdgeInsets.getHeaderHeightPx(View)`** — public accessor over the
  existing `topInset()` (measured-first, dimen-fallback-on-first-frame) logic
  MainActivity already populates via `setHeaderHeight()`. No re-measuring.
- **`computeProgress()`** — the collapse range is now
  `(scrollView.getPaddingTop() + largeTitle.getBottom()) - headerHeightPx`:
  the distance, in px, for the title's bottom edge to travel from its resting
  position down to the header's (stationary) bottom edge. `progress` is
  `scrollY / collapseRangePx`, so it always corresponds to where the title
  physically is, on any device/font-scale, not a hardcoded dp guess.
- **Staggered, eased crossfade** — large title alpha `1 → 0` over the first
  60% of the range; compact title (+ separator) alpha `0 → 1` over the *last*
  55% (start at 45%) — a deliberate 15%-of-range overlap instead of one shared
  ramp, so the two are never both at 0.5 together. Both ramps go through
  `AccelerateDecelerateInterpolator` instead of linear interpolation.
- **Hand-off motion** — large title gets `translationY` (0 → -8dp) and scale
  (1.0 → 0.96) across the *full* range (not just its 60% fade window), so it
  reads as being lifted into the bar rather than dissolving in place. Because
  its fade finishes at 60% of the range — well before progress reaches 1.0,
  the point where its glyphs would physically reach the header's bottom edge —
  it's fully transparent before the guillotine point, which is what removes it.
- **Reduce motion** — routed through `MotionUtil.animationsEnabled()`, same
  as `PressFeedback`. When off, the whole crossfade collapses to an instant
  swap at the range's midpoint (large title alpha 0/1, compact alpha 1/0, no
  translation/scale) instead of a continuous interpolation.

**Eyeball in Android Studio:** Overview, scroll slowly from the top — the
serif "OVERVIEW" should fade out (lifting slightly) well before its glyphs
reach the header's bottom edge, and the compact "Overview" + hairline should
still be fading in as it finishes, not already fully visible. There should be
no point during the scroll where both titles look equally solid. With
Settings → Reduce Motion (or system "Remove animations") on, the swap should
be an instant cut partway through the scroll instead of a fade.

### 2b — collapsed bar was too heavy

**Root cause:** `header_inner_layout` was `paddingTop 12dp + paddingBottom
8dp` around a 48dp `btnSettings` `ImageView` — 68dp of chrome from the gear
alone (the 17sp title never drove the height), plus a 1dp separator = ~69dp
total, versus iOS's 44pt compact bar. The separator itself
(`@color/gold_hairline`, ~18% alpha gold) was also doing two jobs at once: the
identity gold rule under the always-visible compact bar on tabs without a
collapsing title (Markets/Economy/News), and the IOS-02 collapse hairline on
Overview. At full alpha under a compact title, that read as a heavy border
rather than a hairline.

**Shipped:**

- **Bar height** — removed `header_inner_layout`'s vertical padding entirely.
  `btnSettings` keeps its 48dp `layout_width`/`layout_height` (its actual touch
  target — **UX_DESIGN_LAWS.md Law 4, Fitts's Law**: "Minimum touch target
  48x48dp") but gets `layout_marginTop="-2dp"` / `layout_marginBottom="-2dp"`,
  so it contributes only 44dp (48 − 2 − 2) to the `FrameLayout`'s
  `wrap_content` height while still rendering at its full 48dp box —
  `clipChildren="false"` was added to both `header_inner_layout` and its
  parent `header_container` so that 2dp-per-edge overflow isn't clipped.
  Result: 44dp of bar content, 48dp touch target, nothing shrunk below the
  law's floor.
- **Separator split in two**, since they're genuinely two different jobs and
  the shared alpha is what forced the compromise:
  - `activity_main.xml`'s `headerSeparator` (the persistent bar's own View)
    is now purely the hairline: `@dimen/divider_hairline_height` (0.5dp) of
    new `@color/header_hairline_neutral` (`#1FFFFFFF` — the same
    dark-surface hairline alpha already used by `hairline_on_surface`'s
    night-mode value, reused here since the header is navy in both themes).
    It still fades 0→1 with `LargeTitleController` on Overview and stays at
    alpha 1 via `resetHeaderCompactAlwaysVisible()` on the other tabs — same
    behavior as before, just a different (lighter, neutral) color.
  - `fragment_dashboard.xml`'s `largeTitleBlock` gets a new static
    `goldMastheadRule` View (2dp, `@color/accent_gold`) below the quarter
    pill — the identity accent that used to live in the shared separator,
    now living where it conceptually belongs (the large masthead itself) and
    needing zero animation, since it just scrolls away with the rest of the
    block like `tvLargeTitle` does.
  - `@color/gold_hairline` is unchanged and still used elsewhere (chat
    bubble stroke, `bg_pill_navy`) — not removed, just no longer the
    header separator's color.

**Scope check:** Markets/Economy/News don't have a large-title block (IOS-02
is Overview-only by design; those tabs call
`resetHeaderCompactAlwaysVisible()`), so they only pick up the bar-height and
hairline-color changes — both apply to every tab since they live in the
shared `activity_main.xml`/`MainActivity`, not per-tab code. The three tabs'
own top-inset padding comes from `EdgeToEdgeInsets`'s *measured* header
height (`setHeaderHeight`, fed by `header_container`'s
`OnLayoutChangeListener`), not a hardcoded dimen, so they pick up the new
~44dp-shorter bar automatically — no per-fragment changes needed, and nothing
to regress.

**Eyeball in Android Studio:** compare the header's visual height
before/after on all four tabs — should look noticeably slimmer, closer to a
standard iOS nav bar. The gear icon should still be comfortably tappable
(same 48dp box, just less padding around it) with no visible clipping at its
top/bottom edges. On Markets/Economy/News (always-visible compact title) the
line under the bar should read as a subtle neutral hairline, not gold. Scroll
Overview to the top — the large serif masthead should show a solid gold rule
under its quarter pill; scroll down and that rule should scroll away with the
rest of the masthead rather than fading like the bar's hairline does.

---

## Note on referenced convention files

`UX_DESIGN_LAWS.md` and `CLAUDE.md` are not present in the
`EconomicDashboard` repo itself — both were found in
`C:\Android App\ProjectDocs\`
and consulted from there for this change (the Law 4 citation above).

## Final verification notes

- Every edited XML file (`sheet_metric_wrapper.xml`, `activity_main.xml`,
  `fragment_dashboard.xml`, `colors.xml`, `values-night/colors.xml`,
  `dimens.xml`) was parsed with Python's `xml.dom.minidom` — all well-formed.
- Grepped for other call sites of `LargeTitleController.attach`,
  `headerSeparator`, `gold_hairline`, and `COLLAPSE_DISTANCE_DP` — no stale
  references left outside this change set; `DashboardFragment`'s call site is
  unchanged (same 4-arg signature).
- Grepped `res/layout` for hardcoded assumptions about the old ~69dp header
  height — found none; every consumer reads the live measured value through
  `EdgeToEdgeInsets`.
