# iOS-Feel Tickets — Economic Dashboard

Goal: make the app *feel* like a well-built native Apple app (motion, materials,
type, gestures) **without** discarding the Alabaster Report identity (warm
off-white `#f5f3ef`, `header_navy`, `accent_gold`, serif masthead).

The distinction matters. "Apple feel" is mostly **physics, hierarchy, and
restraint** — not a color palette. Copying iOS colors would give you the same
grey-and-blue app as everyone else and throw away the one thing your app already
has that Bloomberg/FRED clones don't. So: adopt Apple's *structure and behavior*,
keep your palette.

Same conventions as `UX_TICKETS.md` / `UX_TICKETS_P4.md`.

**Effort:** S = <½ day · M = ½–2 days · L = >2 days
**Priority:** P1 = biggest "this is an Android app" tells · P2 = component-level · P3 = polish

> Code lives in `C:\Android App\EconomicDashboard`. No Android SDK in the tooling
> sandbox — build/verify in Android Studio. Read source with the Read tool, not
> `bash cat` (OneDrive sync truncation).

---

## Tier 1 — the four things that read most as "Android"

If you only do four tickets, do these. Ripples, static headers, opaque bars, and
proportional digits are the giveaways; everything else is refinement.

### IOS-01 — Replace ripples with press-in springs
**Effort:** M · **Priority:** P1
**Current state:** KPI cards and rows use Material's default ripple
(`?attr/selectableItemBackground` / `MaterialCardView` behavior). A ripple
expanding from the touch point is the single most recognizable Android
micro-interaction — iOS never does it.
**Change:** New `ui/views/PressFeedback.java` — a static `attach(View)` that on
`ACTION_DOWN` springs the view to `scale 0.97` + `alpha 0.85`, and on
`ACTION_UP`/`CANCEL` springs back. Use `androidx.dynamicanimation.SpringAnimation`
(`dampingRatio = 0.75f`, `stiffness = 1200f`) — *not* `ViewPropertyAnimator` with
a duration, because springs are interruptible and duration-based tweens are not,
and interruptibility is what makes iOS feel "alive." Set ripple drawables to
`@null` on the cards. Route through the existing `MotionUtil` reduce-motion check
so it degrades to a plain alpha change.
**Files:** new `ui/views/PressFeedback.java`; `res/layout/card_kpi_navy.xml`,
`card_kpi_gold.xml`, `card_kpi_badge.xml`; the fragments that set click listeners
on cards; `utils/MotionUtil.java`.
**Acceptance:** Pressing any KPI card scales it down and back with no ripple;
pressing and dragging off cancels smoothly mid-animation rather than snapping.

### IOS-02 — Collapsing large title
**Effort:** M · **Priority:** P1
**Current state:** `activity_main.xml` has a fixed navy `header_container` with a
2dp gold rule, `tvHeaderLine1` ("U.S. ECONOMIC", 28sp serif, `letterSpacing 0.08`)
and line 2. It never moves. iOS's signature navigation is a large title that lives
*in* the scroll content and collapses into a compact centered inline title, with a
hairline separator fading in under the bar once content passes beneath it.
**Change:** Move the masthead into each fragment's scroll view. On scroll,
interpolate: large title `translationY` up and `alpha` → 0 over the first ~56dp of
scroll, while a compact 17sp inline title fades in centered in the persistent bar,
and a 0.5dp separator fades in at the bar's bottom edge. Keep the serif face and
gold rule for the large state — that's your identity; the compact state can be
sans.
**Files:** `activity_main.xml`, `MainActivity.java`, each `fragment_*.xml` scroll
root; new `ui/views/LargeTitleController.java` to own the scroll math so all
fragments share one implementation.
**Acceptance:** Scrolling any tab collapses the masthead into a compact centered
title; scrolling back to top restores it; the separator only appears when content
is underneath the bar.

### IOS-03 — Tabular figures on every number
**Effort:** S · **Priority:** P1
**Current state:** Values render with proportional digits, so "1" is narrower than
"8." Any live-updating number therefore jitters horizontally — and the count-up
animation shipped in TICKET-27 makes this visibly worse. Apple ships SF with
`tnum` on by default in exactly these contexts.
**Change:** Add `android:fontFeatureSettings="tnum"` to a
`TextAppearance.Econ.Value` style and apply it to every value / delta /
axis-label TextView. Where values are set in Java, add the same via
`setFontFeatureSettings("tnum")` in `NumberFormatUtil` so nothing is missed.
**Files:** `res/values/styles.xml` (or `themes.xml`), `utils/NumberFormatUtil.java`,
`card_kpi_*.xml`, chart axis formatting in `ChartHelper`.
**Acceptance:** A count-up from 3.9 → 4.25 causes zero horizontal shift; columns of
numbers in any list align on the decimal.

### IOS-04 — Edge-to-edge with translucent bars
**Effort:** M · **Priority:** P1
**Current state:** `themes.xml` sets an opaque `statusBarColor #0f1729` with
`windowTranslucentStatus=false`; content sits in a boxed region between two solid
bars. iOS content always runs under the status bar and home indicator, and the bars
are *materials* — translucent, blurred, with a hairline edge — not opaque blocks.
**Change:** `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)` in
`MainActivity`; apply `WindowInsetsCompat` top padding to the header and bottom
padding to `bottomNav` so nothing is occluded. Make both bars semi-transparent
(≈85% alpha) with a 0.5dp hairline edge. On API 31+ add
`RenderEffect.createBlurEffect(24f, 24f, CLAMP)` on a background layer for real
frosted glass; below 31 the alpha alone is fine.
**Files:** `MainActivity.java`, `res/values/themes.xml`,
`res/values-v31/themes.xml`, `activity_main.xml`,
`res/drawable/bg_bottom_nav_border.xml`.
**Acceptance:** Content visibly scrolls *under* both bars; nothing is clipped by
the status bar or gesture pill; on a Pixel running API 31+ the bar blurs the
content behind it.

---

## Tier 2 — components

### IOS-05 — iOS segmented control for timeframes
**Effort:** M · **Priority:** P2
**Current state:** Timeframe selection (1M / 3M / 1Y / 5Y) uses chips / custom
pills with radii scattered across `bg_chip_outline.xml`, `bg_pill_navy.xml`, etc.
(see BADGE TICKET-08).
**Change:** New `ui/views/SegmentedControl.java`: a recessed track, a raised thumb
that **slides** to the tapped segment on a spring (`dampingRatio 0.9`,
`stiffness 900f`), hairline dividers between unselected segments that fade out as
the thumb approaches. The sliding thumb is the whole point — a chip that just
changes color is the Android version.
**Files:** new `ui/views/SegmentedControl.java` + `res/values/attrs.xml`; the
`fragment_*.xml` files that host timeframe rows; delete the superseded pill
drawables.
**Acceptance:** Tapping a segment slides the thumb with a slight settle; rapid
alternating taps redirect mid-flight rather than queueing.

### IOS-06 — Sheet detents, grabber, and the correct curve
**Effort:** M · **Priority:** P2
**Current state:** `Theme.EconomicDashboard.BottomSheet` +
`EconomicBottomSheetStyle`; `DialogAnimation` uses `slide_in_up.xml` /
`slide_out_down.xml` — a **linear 300ms translate**, which is the most artificial
motion curve available. iOS sheets use a spring, stop at detents, show a grabber,
and scale the presenting screen back behind them.
**Change:** (a) Replace the linear translate with
`PathInterpolator(0.32f, 0.72f, 0f, 1f)` or a spring. (b) Add a 36×5dp grabber pill
at the top of `AiAnalystBottomSheet` and the metric sheets. (c)
`setFitToContents(false)` + `setHalfExpandedRatio(0.55f)` so the sheet has a medium
detent and drags to full. (d) Animate the content behind to `scale 0.94` +
`radius 12dp` while a sheet is open.
**Files:** `res/anim/slide_in_up.xml`, `slide_out_down.xml`, `themes.xml`,
`analyst/AiAnalystBottomSheet.java`, the metric bottom sheet, `activity_main.xml`
(root view to scale).
**Acceptance:** The Analyst sheet opens to roughly half height, drags to full,
dismisses on flick-down, and the app behind it visibly recedes.

### IOS-07 — Swift Charts aesthetic in `ChartHelper`
**Effort:** M · **Priority:** P2
**Current state:** MPAndroidChart defaults — visible chart border, both gridline
axes, a floating tooltip marker on tap. `UX_DESIGN_LAWS.md` Law 17 already flags
the chart junk; this ticket says specifically what to replace it with.
**Change:** Drop the chart border and **all** vertical gridlines; horizontal
gridlines become light dashed hairlines only. Line width 2dp, round caps, cubic
smoothing off (real data shouldn't be smoothed — it misrepresents), gradient fill
under the line from 18% accent → transparent. Replace the floating tooltip with an
iOS-style **scrub**: dragging shows a thin vertical rule and a dot, and the value
readout replaces the card's header value rather than floating over the plot.
Haptic tick as the scrub crosses each data point.
**Files:** `ui/ChartHelper.java`, chart setup in each leaf fragment,
`res/drawable/` (new gradient), `utils/HapticUtil.java` (IOS-09).
**Acceptance:** No chart has a border or vertical gridlines; dragging across a
chart updates the header value in place and ticks haptically per point.

### IOS-08 — Inset grouped lists
**Effort:** M · **Priority:** P2
**Current state:** Settings and row-based lists are flat lists / full-bleed rows.
The iOS "inset grouped" table is one of the most recognizable Apple patterns and
the cheapest to copy convincingly.
**Change:** Group rows into `card_white` containers with `radius_card_kpi` (12dp),
inset 16dp from the screen edge. Separators are **0.5dp hairlines inset to align
with the row's text**, not full-bleed, and absent after the last row. Section
headers 13sp uppercase `text_secondary` above each group. Trailing chevron on any
row that navigates.
**Files:** `sheet_settings.xml`, any row include layouts, new
`res/drawable/divider_hairline_inset.xml`, `res/values/dimens.xml`.
**Acceptance:** Settings reads as discrete rounded groups; no separator touches the
left edge or appears below a group's last row.

### IOS-09 — Haptics
**Effort:** S · **Priority:** P2
**Current state:** None. iOS fires haptics constantly and it's a large share of why
those apps feel expensive.
**Change:** New `utils/HapticUtil.java` wrapping `performHapticFeedback`:
selection tick on tab switch / segment change (`CLOCK_TICK`, or `SEGMENT_TICK` on
API 34+), `CONFIRM` when a refresh completes, `REJECT` on a failed fetch, light
tick on chart scrub. Gate on a `SettingsManager` key, defaulting on.
**Files:** new `utils/HapticUtil.java`, `utils/SettingsManager.java`,
`sheet_settings.xml`, `MainActivity.java`, `SegmentedControl.java`, `ChartHelper.java`.
**Acceptance:** Tab switches and segment changes tick; a completed pull-to-refresh
gives a distinct confirm; toggling the setting off silences all of it.

---

## Tier 3 — polish and opinion calls

### IOS-10 — Push transitions with parallax + predictive back
**Effort:** M · **Priority:** P3
**Current state:** Fragment transactions likely use the default fade. iOS pushes
slide in from the right at full speed while the outgoing screen slides left at
~⅓ speed under a dimming scrim, and can be dragged back interactively from the
left edge.
**Change:** A shared `pushTransaction()` helper applying that parallax pair, and
its mirror on pop. Enable `android:enableOnBackInvokedCallback="true"` and wire
Android's **predictive back** — it is genuinely close to iOS's interactive pop and
is the modern-Android answer rather than a hack.
**Files:** `MainActivity.java`, `AndroidManifest.xml`, new
`res/anim/push_in_right.xml` / `push_out_left_parallax.xml` + pop mirrors.
**Acceptance:** Navigating into a metric screen slides with visible parallax;
dragging from the left edge peels the screen back and can be released to cancel.

### IOS-11 — Type scale aligned to the Apple ramp
**Effort:** M · **Priority:** P3
**Current state:** Sizes are ad-hoc across screens (34/28/22/18/15/13/11 all appear;
the BADGE audits document six overline variants alone). Apple's ramp is fixed and
tightly tracked at large sizes.
**Change:** Define one scale — Large Title 34/bold/`-0.02` tracking, Title 28/bold,
Title2 22/bold, Headline 17/semibold, Body 17, Subhead 15, Footnote 13, Caption 11
— as `TextAppearance.Econ.*` styles, and route every TextView through them. Note
the tracking direction: Apple tracks large text **tighter** and small text looser.
Your masthead's `letterSpacing 0.08` all-caps is the opposite convention — keep it
there deliberately as your editorial signature, but don't let it spread.
Consider Inter as the sans (SF isn't licensable off-platform); keep serif for the
masthead only.
**Files:** `res/values/styles.xml`, `res/font/`, every `fragment_*.xml` and
`card_*.xml`.
**Acceptance:** A grep for `android:textSize` in layouts returns near-zero hits
outside the style file.

### IOS-12 — Semantic color layer
**Effort:** S · **Priority:** P3
**Current state:** `colors.xml` mixes role names with literal names, and the badge
audit found four different greys doing one job. Apple's system is purely semantic:
`label` / `secondaryLabel` / `tertiaryLabel` / `separator` / `systemBackground` /
`secondarySystemGroupedBackground`, each with a dark-mode value.
**Change:** Add a semantic layer *on top of* your existing hex tokens — don't
repaint. `label` → `text_navy`, `label_secondary` → `text_secondary`,
`separator` → a true alpha value (e.g. `#4D3C3C43`) rather than a solid grey,
`bg_grouped` → `bg_warm_off_white`, `bg_grouped_secondary` → `card_white`. Migrate
layouts to the semantic names; keep the literals as the single definition point.
Mirror everything in `values-night`.
**Files:** `res/values/colors.xml`, `res/values-night/colors.xml`, layouts,
`ui/BenchmarkStyle.java`.
**Acceptance:** Layouts reference role names only; changing `separator` in one
place changes every hairline in the app.

### IOS-13 — (opinion call) Drop the gold nav indicator
**Effort:** S · **Priority:** P3
**Current state:** `navActiveIndicator` + `bg_nav_indicator.xml` draw a sliding
2–4dp gold line above the active tab, positioned in `MainActivity` via `post()`.
**Change:** iOS tab bars have no indicator — the selected item is signaled by a
filled icon plus tint, nothing more. Removing it is more Apple; keeping it is more
*you*, and it came from your own mockup.
**Recommendation: keep it.** It's the kind of restrained signature Apple itself
would defend, and it costs you nothing in usability. Listed only so the choice is
deliberate rather than accidental. If you do keep it, at least make it *spring*
between items instead of translating linearly.
**Files:** `activity_main.xml`, `MainActivity.java`, `bg_nav_indicator.xml`.

---

## Suggested order

1. **IOS-01, 03, 04** — small, self-contained, and together they change the app's
   feel more than the other ten combined.
2. **IOS-02** — the largest single visual shift; do it once 01/04 are stable since
   it touches the same layout.
3. **IOS-05, 06, 09** — component tier, each independently shippable.
4. **IOS-07, 08** — more surface area; schedule when there's room.
5. **IOS-10 – 13** — polish, and 11/12 double as cleanup for debt the badge audits
   already logged.

## Things deliberately *not* on this list

- **Repainting to iOS greys and system blue.** Throws away your identity for a
  look every other app has.
- **Removing bottom-nav labels.** Android users expect them; Jakob's Law beats
  imitation.
- **Back chevron in the top-left as the only back affordance.** Android has a
  system back; don't fight it — IOS-10 makes it *feel* like iOS instead.
- **Copying SF Pro.** Not licensable off Apple platforms. Inter is the standard
  substitute.

---

## Implementation status (2026-07-27)

Tiers 1 and 2 (IOS-01 through IOS-09) have been implemented. See
`CHANGES_2026-07-27_ios_tier1.md` and `CHANGES_2026-07-27_ios_tier2.md` for what
actually shipped, what was scoped down from the ticket text (several "current
state" descriptions above were stale — grabbers and some edge-to-edge plumbing
already existed), and what to eyeball in Android Studio. Tier 3 (IOS-10–13) is
not started.
