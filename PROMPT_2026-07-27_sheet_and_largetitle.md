# Claude Code prompt — metric sheet clipping + IOS-02 collapse quality

Paste everything below the line into Claude Code, run from
`C:\Android App\EconomicDashboard`.

---

You are working in the Economic Dashboard Android app at
`C:\Android App\EconomicDashboard`. There is **no Android SDK in your
environment** — you cannot build. Verify by reading files back and reasoning
about layout math; the user builds in Android Studio. Follow the conventions in
`CLAUDE.md`, `UX_DESIGN_LAWS.md`, and the existing `CHANGES_2026-07-27_*.md`
files (dated changes doc summarizing what shipped, what was scoped down, and
what to eyeball).

Two defects were reported from device screenshots after IOS-02 and IOS-06 from
`IOS_FEEL_TICKETS.md` shipped. Both diagnoses below are already confirmed
against the source — treat them as findings to fix, not hypotheses to re-derive,
but do sanity-check the line references since the files may have moved.

---

## Defect 1 — metric benchmark sheets are clipped and mis-anchored

**Reproduce:** Overview → tap the "10Y-3M SPREAD" KPI card → the benchmarks
sheet opens. In the half-expanded state the bottom of the list, the DISMISS
row, and the "+ Add alert" button are cut off below the screen edge with no way
to scroll to them. Dragging up to expanded pins the sheet's top to the status
bar but leaves its **bottom** floating around 72% down the screen, with the
dimmed Overview and the bottom nav visible underneath it.

**Root cause** — `ui/MetricBottomSheet.java:46-48`:

```java
behavior.setFitToContents(false);
behavior.setHalfExpandedRatio(0.55f);
behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
```

`setFitToContents(false)` switches `BottomSheetBehavior` from "size the sheet to
its measured content" to "the sheet spans the parent and I position its top
edge by offset." That contract requires a sheet view that can actually fill the
parent. This one can't:

- `res/layout/sheet_metric_wrapper.xml` — root `LinearLayout` is
  `layout_height="wrap_content"`, and its `sheetContent` `FrameLayout` is
  `wrap_content`.
- Every injected content root (`res/layout/dialog_*_status.xml`, e.g.
  `dialog_treasury_status.xml`) is also a `wrap_content` `LinearLayout` with its
  own `android:background="@color/header_navy"`.
- Nothing anywhere in that hierarchy is a scrolling container.

So the two states fail in different ways:

- **Half-expanded** — top edge goes to 55% of screen height, but the view keeps
  its own content height, which exceeds the remaining 45%. The overflow runs off
  the bottom of the screen and there is no scroller to reach it.
- **Expanded** — top edge goes to `expandedOffset` (0), the view still measures
  `wrap_content`, so the sheet's bottom lands mid-screen and the activity shows
  through below it. That's the "floating slab" in the second screenshot.

**Fix:**

1. `sheet_metric_wrapper.xml` — root `LinearLayout` → `layout_height="match_parent"`.
   Keep the `BottomSheetDragHandleView` fixed at the top, then put `sheetContent`
   inside an `androidx.core.widget.NestedScrollView` with
   `layout_height="0dp"`, `layout_weight="1"`, and `android:fillViewport="true"`.
   `fillViewport` matters: without it a short benchmark list won't stretch and
   the navy background will stop mid-sheet again for the small metrics.
2. The scroll view needs bottom padding for the gesture inset so the last row
   and the "+ Add alert" pill clear the home indicator. Use the same
   `WindowInsetsCompat.Type.systemBars()` bottom the rest of the app uses —
   `utils/EdgeToEdgeInsets` already caches it; add a small accessor rather than
   re-plumbing insets into a dialog window, and set `clipToPadding="false"`.
3. `MetricBottomSheet.java` — keep `setFitToContents(false)` and the 0.55 half
   detent (that's the IOS-06 behavior the user wants), but:
   - Set `behavior.setExpandedOffset(...)` so the expanded sheet stops short of
     the status bar rather than going flush to y=0. iOS sheets always leave the
     presenting screen peeking above them; flush-to-top is what makes this read
     as a full-screen dialog instead of a sheet. Status-bar inset + 8dp is the
     right target.
   - Move `setState(STATE_HALF_EXPANDED)` to **after** `dialog.show()` (or into
     a `setOnShowListener`). Setting state before the sheet is laid out is
     unreliable — the behavior can re-resolve to `STATE_COLLAPSED` on first
     measure.
   - Guard the half detent for short content: if the measured content is
     shorter than 55% of the screen, `STATE_HALF_EXPANDED` opens a sheet with
     dead space under the content. Either fall back to `setFitToContents(true)`
     in that case or expand straight to `STATE_EXPANDED`.
4. Only `MetricBottomSheet` has this bug. `AiAnalystBottomSheet.java:311-315`
   uses `setFitToContents(true)` with an explicit peek height and
   `SettingsBottomSheet.java:62-63` uses expanded + `skipCollapsed` — both are
   internally consistent. **Do not "harmonize" them into this change.**

**Also visible in the screenshot, lower priority:** IOS-06 asked for the
presenting screen to recede to `scale 0.94` **+ 12dp corner radius**. The scale
shipped (`MetricBottomSheet.java:52-69`) but the radius did not, so the
scaled-back Overview sits against black with square corners. Add the radius via
a `ViewOutlineProvider` + `setClipToOutline(true)` on the presenting view,
animated alongside the existing `onSlide` scale, and reset it in the same two
places the scale is reset (`STATE_HIDDEN` and `setOnDismissListener`).

---

## Defect 2 — the IOS-02 large-title collapse is not seamless, and the collapsed bar is too heavy

**Reproduce:** Overview, scroll slowly. Mid-scroll both the large serif
"OVERVIEW" and the compact "Overview" are legible at once, and the large title
is guillotined mid-glyph by the header's hard bottom edge while it's still
nearly opaque.

### 2a — the fade is decoupled from the physical hand-off

`ui/views/LargeTitleController.java` drives everything from raw scroll distance:

```java
private static final float COLLAPSE_DISTANCE_DP = 56f;
float progress = clamp(scrollY / collapseDistancePx);
largeTitle.setAlpha(1f - progress);
compactTitle.setAlpha(progress);
separator.setAlpha(progress);
```

Two problems:

- **56dp is arbitrary and unrelated to where the title actually is.** The large
  title block (`fragment_dashboard.xml`, `@id/largeTitleBlock`) has 16dp top
  padding, a 28sp serif line, an 8dp gap, and the quarter pill — its bottom sits
  roughly 90dp into the content. So the title finishes fading out while it is
  still sitting in open space well below the bar, and the compact title reaches
  full opacity before the large one has gone anywhere near it. The crossfade
  doesn't correspond to any event the eye can see, which is exactly what "not
  seamless" describes.
- **The two ramps are the same linear ramp**, so at progress 0.5 both titles sit
  at alpha 0.5 — a visible double-title ghost band through the whole middle of
  the gesture.

**Fix:** drive progress from the large title's position relative to the header's
bottom edge, not from `scrollY`:

- Compute the collapse range from the large title block's own geometry — start
  fading when its **bottom** approaches the header's bottom edge, finish when it
  has passed under. `MainActivity` already publishes the measured header height
  through `utils/EdgeToEdgeInsets` (`setHeaderHeight`); expose a getter rather
  than re-measuring, and add the fallback path for the first frame.
- **Stagger the crossfade** so the titles never both sit high: large title alpha
  `1 → 0` over roughly the first 60% of the range, compact title `0 → 1` over
  roughly the last 55%, with a small overlap. Interpolate each with an ease
  rather than linearly.
- Give the large title a small upward `translationY` (~ -8dp across the range)
  and optionally a `0.96` scale so it reads as being handed off to the bar
  rather than merely dissolving. It must reach alpha 0 **before** its glyphs
  reach the header's bottom edge — that's what removes the guillotine.
- Route the whole thing through `utils/MotionUtil` so reduce-motion degrades to
  a plain instant swap, matching how `PressFeedback` handles it.

### 2b — the collapsed bar is too heavy

Two separate contributors, both worth fixing:

- **The bar is ~69dp of chrome.** `activity_main.xml`'s `header_inner_layout`
  is `paddingTop=@dimen/space_md` (12dp) + `paddingBottom=@dimen/space_sm` (8dp)
  around a **48dp** `btnSettings` `ImageView` — the gear, not the 17sp title,
  dictates the height. Plus the 1dp separator. iOS's compact navigation bar is
  44pt. Bring the bar's content height to ~44dp: keep the gear's 48dp touch
  target via negative margins or by letting it overflow the bar's padding box,
  rather than by shrinking the touch target below 48dp (that would violate
  `UX_DESIGN_LAWS.md`'s touch-target rule — check the exact law number and cite
  it in the changes doc).
- **The separator is a gold rule, not a hairline.** `@id/headerSeparator` is
  `1dp` of `@color/gold_hairline` (`#2EC9A84C`). At full alpha under a compact
  title that reads as a heavy border. IOS-04's ticket text calls for a **0.5dp**
  hairline. Make it 0.5dp and neutral (an alpha-on-black/white separator token,
  not gold). Note that the same `View` is deliberately reused as both the
  identity gold rule under the *large* masthead state and the collapse
  separator — decide explicitly whether to split it into two views (gold rule
  belongs to the large title block in the scroll content; hairline belongs to
  the bar) and say which you chose and why in the changes doc. Splitting is
  probably right: they're doing two different jobs and the shared alpha is what
  forces the compromise.

### Scope note

IOS-02 is still Overview-only by design (Markets/Economy/News host a
`ViewPager2` or a different root and fall back to the always-visible compact
title via `MainActivity.resetHeaderCompactAlwaysVisible()`). **Do not** extend
the collapsing title to other tabs in this change. But any header-height or
separator change affects every tab, so check those three screens don't regress.

---

## Deliverables

1. The code changes above.
2. `CHANGES_2026-07-27_sheet_and_largetitle.md` in the repo root **and** copied
   to `C:\Users\kaaro\OneDrive\Documents\Claude\Projects\Android Econ Monitor App\`
   (per `CLAUDE.md`), in the same style as the existing dated changes docs:
   root cause per defect, what shipped, anything scoped down and why, and an
   "Eyeball in Android Studio" checklist.
3. Update the "Implementation status" section at the bottom of
   `IOS_FEEL_TICKETS.md` to note that IOS-02 and IOS-06 had follow-up fixes.

## Constraints

- Don't touch `AiAnalystBottomSheet` or `SettingsBottomSheet` behavior.
- Don't repaint to iOS greys/system blue — the warm off-white + navy + gold
  identity stays (see the "deliberately not on this list" section of
  `IOS_FEEL_TICKETS.md`).
- Some fragments reference `R` fully-qualified and don't import it; adding a
  bare `R.color`/`R.id` to those files breaks the build. Check the imports of
  any file before adding a bare `R.` reference.
- Prefer measured values over hardcoded dimens for anything positional — the
  64dp `header_chrome_height` estimate was already wrong by ~5dp and has been
  superseded by `EdgeToEdgeInsets.setHeaderHeight()`.
