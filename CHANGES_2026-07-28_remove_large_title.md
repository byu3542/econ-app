# CHANGES 2026-07-28 — Remove Overview's collapsing large title

Follow-up to `CHANGES_2026-07-27_sheet_and_largetitle.md` (which had just fixed
the large title's collapse quality). A device screenshot of the rebuilt app
prompted a design decision, confirmed in chat: drop the large serif masthead
and its "Q3 2026" quarter pill entirely, rather than continue refining the
collapse animation. Overview's header should be a plain static bar — "Overview"
+ gear icon — identical in behavior to Markets/Economy/News.

No Android SDK in this sandbox — verified by reading files back and grepping
for dangling references, not by building.

## What shipped

- **`fragment_dashboard.xml`** — deleted the `largeTitleBlock` `LinearLayout`
  wholesale: the serif `tvLargeTitle` ("OVERVIEW"), the `tvLargeTitleQuarter`
  pill, and `goldMastheadRule` (the gold identity rule added the same day in
  the prior change). The scroll content now starts directly at the
  cache-status/content `FrameLayout` — no gap where the masthead used to sit.
- **`DashboardFragment.java`** — removed the `tvLargeTitleQuarter` lookup +
  `MainActivity.currentQuarterLabel()` call, the
  `LargeTitleController.attach(...)` call in `bindViews()`, and the
  `LargeTitleController.detach(...)` call in `onDestroyView()`.
- **`MainActivity.java`**:
  - `resetHeaderCompactAlwaysVisible()` — previously called for
    Markets/Economy/News only (Overview's own `LargeTitleController` drove the
    header's alpha instead) — is now called on **every** tab switch, including
    Overview's. No tab is special-cased anymore; all four behave identically.
  - Removed `getHeaderCompactTitle()` / `getHeaderSeparator()` — they existed
    solely to hand the header's title/separator views to
    `LargeTitleController`, which no longer exists.
  - Removed `currentQuarterLabel()` — its only caller was the deleted
    quarter-pill code in `DashboardFragment`.
  - `updateHeader()` was already an empty no-op left over from IOS-02 (the
    quarter pill had moved out of it); its stale comment referencing that pill
    was cleaned up. The method itself is left in place since `onCreate()`/
    `onResume()` still call it.
  - Removed now-unused `TextView` import.
- **Deleted `ui/views/LargeTitleController.java`** outright — with Overview
  reverted to a static header, it had zero remaining callers.
- **`EdgeToEdgeInsets.java`** — removed `getHeaderHeightPx(View)`, added in the
  prior change specifically for `LargeTitleController`'s collapse-range math
  and now unused. `getSystemBottomInsetPx()`/`getSystemTopInsetPx()` (added
  the same day, for `MetricBottomSheet`) are unrelated and untouched.
- **`activity_main.xml`** — updated two doc comments (on `header_container`
  and `headerSeparator`) that described the now-removed collapsing behavior,
  so they don't mislead the next reader. No layout/behavior change here beyond
  the comment text — the 44dp bar height, 48dp touch target, and neutral
  0.5dp hairline from the prior change are unaffected and still apply to all
  four tabs (they were never Overview-specific).

## What was deliberately left alone

- **The gold identity rule is gone, not relocated.** The prior change had
  moved it from the shared header separator into a static `goldMastheadRule`
  View inside the (now-deleted) masthead block. Since the whole masthead is
  gone and the screenshot's target header has no gold rule at all, it wasn't
  moved anywhere else — there's no more "large identity state" for it to
  belong to. `@color/gold_hairline` itself is untouched and still used
  elsewhere (chat bubble stroke, `bg_pill_navy`).
- **`@color/header_hairline_neutral`, `@dimen/divider_hairline_height`, and
  the 44dp bar-height changes** from the prior change are all still in place
  — this change only removes Overview's large-title layer sitting *above*
  that bar, not the bar itself.
- **IOS-06 (sheet detents) and Defect 1 (metric sheet clipping) fixes** from
  the prior change are unrelated to this one and untouched.

## Eyeball in Android Studio

Overview should now open directly to a static navy header — "Overview" bold,
centered, gear icon end-aligned — with no serif masthead, no quarter pill,
and no scroll-driven fade of any kind; scrolling the content should leave the
header completely unchanged, matching Markets/Economy/News. Switch between
all four tabs and back to Overview — the header title/hairline should look
identical in every case, with no flash or mismatched alpha on the switch.
