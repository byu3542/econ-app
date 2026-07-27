# CHANGES 2026-07-27 — iOS-Feel navigation fixes (post-Tier 1/2)

Follow-up to `CHANGES_2026-07-27_ios_tier1.md`. Reported from three device
screenshots (Overview, Markets, News) showing navigation chrome buried under
the translucent header.

No Android SDK in this sandbox — everything below was verified by reading the
edited files back and grepping for stale call sites, **not** by building.
Eyeball notes per section.

---

## Root cause

IOS-04's insets were applied at the **wrong level of the fragment tree**.

`EdgeToEdgeInsets.applyEdgeToEdge()` padded a view by *both* bars at once, and
it was wired into all 11 leaf fragments. Ten of those eleven don't own the top
of the screen — they live inside a `ViewPager2` owned by `MarketsFragment` or
`EconomyFragment`, below a `TabLayout`. So:

| Symptom (screenshot) | Cause |
|---|---|
| Markets: `STOCKS / BONDS / YIELDS / SPREADS` ghosted behind the header, untappable | The host `LinearLayout` got **no** top inset, so its `TabLayout` laid out at y=0 under the header |
| Markets: large empty gap above the S&P cards | The leaf (`StocksFragment`) got a top inset it didn't need — status-bar height + 64dp *below* the tab row |
| News: subtitle and filter chips half-occluded by the header | `NewsFragment` was skipped by IOS-04 entirely (its root is a `CoordinatorLayout`, not the `NestedScrollView` shape the helper assumed) |
| All tabs: content legible *through* both bars | 85% bar alpha with no backdrop blur |
| Overview: large "OVERVIEW" and a half-faded compact "Overview" visible at once | `LargeTitleController.attach()` hard-set the compact title to alpha 0 and waited for a scroll event, so it disagreed with any non-zero restored scroll position |

---

## The new inset rule

Stated explicitly in the `EdgeToEdgeInsets` class doc so the next change
doesn't have to re-derive it:

- **Whoever owns a screen's topmost pixel takes the top inset** — the tab-strip
  hosts, News' header block, Overview's scroll view.
- **Whoever owns a screen's bottommost scrolling pixel takes the bottom inset** —
  the leaf scroll views, News' `RecyclerView`.
- **Nobody takes both** unless it genuinely spans the full screen behind both
  bars. Only Overview does.

## `utils/EdgeToEdgeInsets.java` — rewritten

- `applyEdgeToEdge(View)` → replaced by `applyTopInset(View)` /
  `applyBottomInset(View)` / `applyBothInsets(View)`. Only `applyBottomInset`
  (and `applyBothInsets`) disables `clipToPadding` — a tab-strip host shouldn't
  bleed its children.
- **Padding is now additive, not absolute.** Each target's XML padding is
  captured at registration and the inset is added on top, so a view like News'
  `RecyclerView` (which ships `paddingBottom="@dimen/space_sm"`) keeps its own
  spacing instead of having it overwritten.
- **Bar heights are measured, not guessed.** `setHeaderHeight(px)` /
  `setNavHeight(px)` are published from `MainActivity` (see below). The old
  hardcoded `header_chrome_height` 64dp estimate survives only as a
  first-frame fallback.
- **Targets are retained weakly and re-padded on change.** Measurements land
  *after* a fragment's `onCreateView`, so registration had to stop being
  fire-and-forget. Fragments can register whenever and don't care about
  ordering; dead targets are pruned on the next re-apply.

## `ui/MainActivity.java`

Two `OnLayoutChangeListener`s publish `headerContainer` / `bottomNavWrapper`
heights. Both views already carry their own system-bar padding by layout time,
so measured height *is* the clearance a screen needs — no separate
`systemTop + chrome` arithmetic. Listener, not a one-shot `post()`, so it
survives rotation and font-scale changes. Both setters no-op on an unchanged
value and the re-apply is posted off the layout pass, so there's no loop.

## Fragments re-wired

| Fragment | Before | After |
|---|---|---|
| `MarketsFragment` | *(none)* | `applyTopInset(root)` |
| `EconomyFragment` | *(none)* | `applyTopInset(root)` |
| `NewsFragment` | *(none)* | `applyTopInset(newsHeaderBlock)` + `applyBottomInset(recyclerNews)` |
| `DashboardFragment` | `applyEdgeToEdge` | `applyBothInsets` |
| Stocks, Bonds, Yields, Spreads | `applyEdgeToEdge` | `applyBottomInset` |
| Gdp, Cpi, Employment, Inflation, Wages, Housing | `applyEdgeToEdge` | `applyBottomInset` |

`res/layout/fragment_news.xml` — the subtitle/meta `LinearLayout` gained
`android:id="@+id/newsHeaderBlock"` so it can be targeted. No other structural
change there.

## Bar opacity 85% → 95%

`values/colors.xml`, `values-night/colors.xml` (`header_navy_translucent`
`#D91A1A2E` → `#F21A1A2E`) and `drawable/bg_bottom_nav_border.xml` (fill
`#D90F1729` → `#F20F1729`).

Rationale, recorded because it reverses part of Tier 1: Android can't give this
app a real backdrop blur (the Tier 1 doc and the `MainActivity` comment explain
why — `setRenderEffect` blurs the view's own subtree, and the bars hold real
foreground content). Without a blur, 85% left KPI values and news headlines
fully legible *through* the bar, competing with the title and nav labels —
which reads as a rendering bug rather than a material. 95% keeps the depth cue
and restores legibility. The three values must stay in sync.

## `ui/views/LargeTitleController.java` (IOS-02)

- `attach()` now seeds the fade from the scroll view's **actual** `scrollY`
  (immediately, and again in a `post()` once scroll position is restored)
  instead of assuming 0.
- New `detach(scrollView)`, called from `DashboardFragment.onDestroyView()`, so
  a torn-down Overview can't keep driving the shared header's alpha after
  another tab has taken it over.

---

## Eyeball in Android Studio

1. **Markets / Economy** — the leaf tab strip should sit flush under the header
   with no gap and no overlap, and be fully tappable. Content below it should
   start immediately under the strip, not ~90dp down.
2. **News** — "The latest stories shaping the economy" should be fully visible;
   every filter chip should be tappable across its whole height.
3. **All tabs** — scroll down: cards should pass *under* both bars and be
   clearly dimmed by them, with the title and nav labels staying crisp.
4. **Overview** — at rest: large serif "OVERVIEW", empty header bar. Scroll
   ~56dp: large fades out, compact fades in, gold hairline appears. Switch to
   Markets and back — the header must not be left showing two titles or a
   half-faded one.
5. **Rotation / display-size change** — the measured bar heights should
   re-publish and every screen should re-pad; check nothing double-pads.

## Not addressed

- Markets/Economy/News still have no collapsing large title (IOS-02 remains
  Overview-only, as scoped in Tier 1).
- Tier 3 (IOS-10–13) still not started.
