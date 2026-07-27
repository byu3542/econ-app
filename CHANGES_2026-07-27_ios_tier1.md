# CHANGES 2026-07-27 — iOS-Feel Tier 1 (IOS-01 – IOS-04)

No Android SDK in this sandbox — everything below was verified by reading the
edited files back and grepping for leftover old attributes, not by building.
Eyeball notes are called out per ticket.

## IOS-01 — Press-in spring feedback (replace ripples)

**New:** `app/src/main/java/com/economic/dashboard/ui/views/PressFeedback.java`
— `attach(View)` wires an `OnTouchListener` that springs `scale`/`alpha` to
0.97/0.85 on `ACTION_DOWN` and back to 1.0/1.0 on `ACTION_UP`/`CANCEL`, via
persistent `SpringAnimation`s (`dampingRatio 0.75f`, `stiffness 1200f`) cached
per view so retargeting mid-press is a smooth redirect, not a restart. Gated on
`MotionUtil.animationsEnabled()` — falls back to an instant alpha/scale set
with no spring when reduce-motion is on. Never consumes the touch event, so
existing click/long-click listeners are untouched.

**Dependency added:** `androidx.dynamicanimation:dynamicanimation:1.0.0` in
`app/build.gradle` (nothing in the app used `SpringAnimation` before this).

**Ripple removed:** `card_kpi_badge.xml` and `card_fed_funds_hero.xml` had
`android:foreground="?attr/selectableItemBackground"` → set to `@null`.
(`card_kpi_navy.xml`/`card_kpi_gold.xml`, the two other files the ticket named,
turned out to be unused anywhere in the app — no ripple to remove there.)

**Wired onto every KPI-family card:**
- `DashboardFragment.java` — the 6 Overview KPI cards + `cardFedFundsHero`.
- `StocksFragment.java` — `cardSp500`/`cardNasdaq`/`cardVix`.
- `SpreadsFragment.java` — `cardSpreadYoY`/`cardSpread3M`.
- `BondsFragment.java` — `cardBaa`/`cardHy`.

Note: the Stocks/Spreads/Bonds "swappable selector" cards had **no ripple and
no press feedback of any kind** before this — `setOnClickListener` was called
on plain `CardView`s with no `clickable`/`foreground` set in XML. They now get
real press feedback for the first time, not just a ripple swap.

**Eyeball in Android Studio:** press and hold any KPI card — it should scale
down/dim, with no ripple. Press and drag off the card — it should settle back
smoothly rather than snapping. Toggle reduce-motion (Settings, if wired) and
confirm it degrades to a plain dim with no scale animation.

## IOS-02 — Collapsing large title

Scoped to the **Overview tab only** (the flagship/default screen) — see
"Deliberately scoped down" below for why Markets/Economy/News weren't also
converted.

**New:** `ui/views/LargeTitleController.java` — `attach(scrollView, largeTitle,
compactTitle, separator)` sets the large title to alpha 1 / compact title +
separator to alpha 0 at rest, then on each scroll event interpolates all three
by `scrollY / 56dp` (clamped 0–1). No manual `translationY` hack — the large
title already moves with its own scroll content, so only the fade is added.

**`activity_main.xml`:** `header_container`'s title+quarter-pill row was
replaced with a single centered `tvHeaderCompactTitle` (17sp, defaults to
**alpha 1** — i.e. today's always-visible behavior — for any tab that doesn't
drive it) in a `FrameLayout` (so it's centered independent of the settings
gear icon width, not just centered-in-remaining-space). The existing 1dp gold
hairline was given `id="headerSeparator"` and is reused as the fade-in
separator rather than adding a second, redundant hairline.

**`fragment_dashboard.xml`:** a new `largeTitleBlock` (serif 28sp "Overview" +
the quarter pill, moved here from the header) sits at the top of the
`NestedScrollView`'s content — as a sibling *before* the skeleton/content
`FrameLayout`, not inside `contentContainer`, so it's visible immediately
during the loading skeleton instead of waiting for `contentContainer` to
un-hide.

**`DashboardFragment.java`:** binds the quarter pill text (moved out of
`MainActivity.updateHeader()` into `MainActivity.currentQuarterLabel()`, a
static helper) and calls `LargeTitleController.attach(...)`.

**`MainActivity.java`:** `tvHeaderLine1`/`tvHeaderQuarter` renamed/removed in
favor of `tvHeaderCompactTitle`; new `getHeaderCompactTitle()`/
`getHeaderSeparator()` accessors for the controller; a new
`resetHeaderCompactAlwaysVisible()` is called when switching to
Markets/Economy/News so those tabs keep exactly today's always-visible title
(nothing regresses there).

**Deliberately scoped down:** Markets and Economy don't have their own
scrolling content — they host a `ViewPager2` of leaf tabs (Stocks/Spreads/
Bonds under Markets; Gdp/Cpi/Employment/Inflation/Wages/Housing under
Economy). Giving *those* tabs a collapsing title would mean duplicating the
large-title block into all ~10 leaf fragments and re-deciding what text each
one shows — a much bigger, riskier change to make blind (no build/no
compiler). Overview was implemented as the real, working reference; the other
tabs fall back to the old static-title behavior, explicitly (not silently —
`resetHeaderCompactAlwaysVisible()` is the seam).

**Eyeball in Android Studio:** open the Overview tab — large serif "Overview" +
quarter pill should show, header bar should look empty (no title) above it.
Scroll down ~56dp — the large title should fade out while a small centered
"Overview" fades in in the header bar, and the gold hairline should appear
under it. Scroll back to top — both should reverse. Switch to Markets/Economy/
News — title should show in the header exactly as it did before this change.

## IOS-03 — Tabular figures on every number

**Simplified from the ticket's literal ask.** Rather than hand-adding
`fontFeatureSettings="tnum"` to every value/delta/axis-label TextView across
~16 layout files (real risk of missing one, which is exactly the failure mode
the ticket warns about), it's applied **once, app-wide**, via the theme:

- `styles.xml` — new `TextAppearance.Econ.Value` style (documented, for
  explicit/programmatic use).
- `themes.xml` — new `Widget.Econ.TextView` (parent `android:Widget.TextView`)
  carrying `fontFeatureSettings="tnum"`, set as the app theme's
  `android:textViewStyle`. Only plain `<TextView>`/`AppCompatTextView`
  elements resolve that attribute as their default style — Buttons, Chips,
  EditTexts use their own Material default-style attrs, so this is a safe,
  narrowly-scoped default, not a blanket override of every widget.
- `NumberFormatUtil.java` — new `applyTabularFigures(TextView)` static helper.
- `ValueAnimatorUtil.java` — calls it unconditionally at the top of
  `animateOrSet()`, the single shared function every KPI count-up animation
  in the app routes through — this directly closes the ticket's specific
  complaint ("the count-up animation... makes this visibly worse").

One layout (`row_treasury.xml`) already had `fontFeatureSettings="tnum"`
hand-added on three TextViews — confirms this is the right feature string;
now redundant but harmless there.

**Known gap:** MPAndroidChart (v3.1.0) draws axis labels via an internal
`Paint`, not real `TextView`s, and doesn't expose a public hook to set
`Paint.setFontFeatureSettings`. Chart axis labels don't get tabular figures.
Low practical impact since axis labels are static, not rolling/animated —
but flagging since the ticket named `ChartHelper` explicitly.

**Eyeball in Android Studio:** watch any KPI value count up (e.g. pull to
refresh on Overview) — digits should hold their horizontal position, no
jitter. Any settings screen or list with a column of numbers should have
decimals visually aligned.

## IOS-04 — Edge-to-edge with translucent bars

**Found already partially done:** `setDecorFitsSystemWindows(false)` and the
`WindowInsetsCompat` padding on header/bottomNav already existed in
`MainActivity.onCreate()`. What was still missing: the bars were still fully
**opaque** (`header_navy` solid, `bg_bottom_nav_border`'s fill solid
`#0f1729`), the system status bar was still a solid color
(`android:statusBarColor #0f1729`, `windowTranslucentStatus false`), and
`fragmentContainer` was a fixed-size sibling in a vertical `LinearLayout`
between header and bottomNav — meaning nothing actually scrolled *underneath*
either bar, just up to the edge of the space reserved for them.

**`themes.xml`:** `statusBarColor` → `@android:color/transparent`,
`windowTranslucentStatus` → `true`, `navigationBarColor` added as transparent
too.

**`colors.xml` / `values-night/colors.xml`:** new `header_navy_translucent`
(`#D91A1A2E`, ~85% alpha of `header_navy`).

**`bg_bottom_nav_border.xml`:** fill changed from solid `#0f1729` to `#D90F1729`
(same ~85% alpha); the existing 1dp gold-tinted top hairline is unchanged.

**`activity_main.xml` restructured:** the outer vertical `LinearLayout` is
gone. `fragmentContainer` is now `match_parent`/`match_parent`, declared first
(bottom of z-order) directly under the `CoordinatorLayout`. `header_container`
and `bottomNavWrapper` float on top via `layout_gravity="top"`/`"bottom"`,
translucent backgrounds. `progressBar` is now anchored to `header_container`'s
bottom edge (`app:layout_anchor`) instead of taking its own row.

**New:** `utils/EdgeToEdgeInsets.java` — `update(systemTop, systemBottom)` is
called from `MainActivity`'s existing insets listener (now also feeds this
utility, not just the header/bottomNav padding); `applyEdgeToEdge(View)` pads
a screen's own scrolling root by `system inset + a fixed "chrome height"
dimen` and disables `clipToPadding`, so content can scroll up underneath the
translucent bars instead of stopping at a hard edge. New dimens:
`header_chrome_height` (64dp — **an estimate**, since the header's rendered
height isn't measured at call time; eyeball and adjust if there's a visible
gap/overlap) and `bottom_nav_height` (76dp — exact, matches `bottomNav`'s own
hardcoded `layout_height`).

**Wired into 11 fragments** (one line each, in `onCreateView` right after
`binding = ...inflate(...)`):
`DashboardFragment` (its `NestedScrollView` was given `id="dashboardScroll"`
since it wasn't the fragment's direct root), `StocksFragment`,
`SpreadsFragment`, `BondsFragment`, `GdpFragment`, `CpiFragment`,
`InflationFragment`, `EmploymentFragment`, `WagesFragment`, `HousingFragment`,
`YieldsFragment` — every one of these has a `NestedScrollView` as its layout
root, confirmed by reading each file, so `binding.getRoot()` is directly the
view to pad in all but Dashboard's case.

**API 31+ blur — deliberately NOT implemented**, despite the ticket asking
for it. `View#setRenderEffect` blurs that view's *own* rendered subtree, not
sibling content already drawn behind it in z-order — and both
`header_container` and `bottomNavWrapper` hold real foreground content (the
title, the settings icon, nav icons/labels). Applying a blur there would blur
that content into mush, not "blur the KPI cards scrolling underneath."
Android has no per-view backdrop-sampling API to do the latter inside a
single opaque window (`Window#setBackgroundBlurRadius` blurs what's behind
the *window*, e.g. the home screen — not applicable here). This is documented
in a code comment at the spot in `MainActivity.onCreate()` where the blur call
would have gone, rather than silently omitted. The translucent fill is the
real "material" signal on every API level.

**Not touched:** `MarketsFragment`/`EconomyFragment` (host a `ViewPager2`, not
scrollable themselves) and `NewsFragment` (rooted at `CoordinatorLayout`, a
different shape) — still on the old boxed layout for content clearance. Also
`fragment_treasury.xml` — confirmed dead/orphaned (not inflated by any
Java code; `TreasuryFragment` actually uses `fragment_markets_host.xml`).

**Eyeball in Android Studio, in this order (04 before 02, since 02 depends on
04's header restructure):**
1. Status bar should show through to the translucent navy header, not a solid
   block — you should be able to make out card content faintly through it near
   the top when scrolled.
2. Scroll Overview/Stocks/Spreads/Bonds/Gdp/Cpi/Inflation/Employment/Wages/
   Housing/Yields all the way down — last row should clear the bottom nav with
   a similar-looking gap to before, not be hidden under it. If there's a
   visible gap or slight overlap at the top, adjust `header_chrome_height` in
   `dimens.xml`.
3. Markets/Economy/News tabs should look unchanged (still boxed, opaque-ish
   bars showing translucent color now since that changed for everyone, but no
   scroll-under content clearance work was done there).
