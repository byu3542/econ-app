# Kotlin Migration — Batch 10c

Final commit of batch 10: **`DashboardFragment` alone**, 732 lines — the
Overview screen, converted last as the migration doc prescribes.

Branch: `kotlin/batch-10c`. Builds on 10a + 10b (both merged).

**`ui/fragments/` is now 100% Kotlin.** Batch 10 is complete.

---

## The `fun interface` decision from 10a pays off here

`DashboardFragment` supplies `WatchlistSetupFragment.Listener`:

```kotlin
sheet.setListener { getView()?.let { applyWatchlistOrder(it) } }
```

That is now a **Kotlin lambda**, and it only compiles because 10a declared
`Listener` as a `fun interface` rather than a plain one. Had it stayed plain,
10a would have built green (the Java lambda SAM-converted fine) and *this*
commit would have failed — exactly like `LiveSeriesProvider` between 9b and 9c.

**Second time this pattern has appeared in two batches.** Worth promoting to a
standing rule, which I've added to the migration doc's lessons list:

> When a batch converts an interface whose implementer is still Java, check
> whether that implementer is a **lambda** and whether it converts in a later
> batch. If both, the interface needs `fun` — and the earlier batch's green
> build will not tell you.

---

## Nullability: a deliberate deviation from 10b

10b used `lateinit var` for view fields. **This file mostly does not**, and the
difference is intentional:

- Fields from **ViewBinding** (`b.swipeRefresh`, `b.cardGdp.root`, …) are
  genuinely non-null → **`lateinit var`**.
- Fields from **`view.findViewById`** (`skeletonShimmer`, `contentContainer`,
  `tvCacheAsOf`, and all five Fed-funds hero views) are null-checked at *every*
  use in the Java, including inconsistently — so they stay **nullable `var`**
  with the guards preserved verbatim.

That split follows the doc's "nullability reflects reality" rule rather than
applying one shape uniformly. Using `lateinit` for the hero views would have
meant rewriting ~12 null checks into `isInitialized` checks and would crash
rather than no-op if a layout variant ever omitted one.

**Zero `!!` in the file.**

---

## Other conversion notes

**1. `0xFFC9A84C.toInt()` in `setSparkline`.** Eight hex digits — Kotlin infers
`Long`, and `SparklineView.setLineColor` takes `int` (checked, not assumed).
Second occurrence of migration lesson 4 actually biting, after `NewsFragment`
in 10a.

**2. `Boolean.FALSE.equals(loading)` → `loading == false`.** `getIsLoading()`
emits a boxed `Boolean?`, and the Java used `Boolean.TRUE/FALSE.equals(...)`
precisely to null-guard. `== true` / `== false` on a `Boolean?` is the exact
Kotlin equivalent — both are false when the value is null.

**3. The local `Group` class survives as a Kotlin local class.** Java declared
`final class Group` *inside* `applyWatchlistOrder`; Kotlin supports local
classes, so the structure is preserved rather than hoisted to a nested type.
`Collections.sort(groups, comparator)` became `groups.sortBy { it.rank }` —
same in-place sort, same ordering.

**4. `contentContainer instanceof LinearLayout` → `as? LinearLayout ?: return`,**
which both checks and smart-casts in one step.

**5. `getActivity() instanceof MainActivity` → `(activity as? MainActivity)?.`**

**6. Statics moved to a `companion object`** — `FOMC_2026`, `MONTH_ABBR`,
`buildAskQuery`, `bestRank`, `tailValues`. None are called from Java, so no
`@JvmStatic` is needed.

---

## Deliberately left non-idiomatic

- **`onResume()` that only calls `super`** — kept, since deleting it is a
  behaviour-neutral cleanup rather than a syntax port.
- **Three dead members ported verbatim** per the batch-9 dead-code decision,
  each commented: `wireCardTapAnalyst` (no caller), the 2-arg `wireCardDialog`,
  and the 1-arg `showBenchmarkDialog`.
- **`Math.max`/`Math.min`/`Math.abs`** kept over Kotlin equivalents.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`wireCardTapAnalyst` is dead, and its sibling `buildAskQuery` exists only
   to serve it.** Both are reachable only if someone re-wires a card tap. Batch
   12 candidate — deleting the pair removes ~25 lines.

2. **`swipeRefresh` is used unguarded on one line and null-guarded on another**
   in the original Java (`swipeRefresh.setOnRefreshListener(...)` at line 152 vs
   `if (swipeRefresh != null)` at 157). It comes from ViewBinding and is
   non-null, so the guard was already dead — now explicit via `lateinit`.

3. **`updateNextFomcLabel()` hardcodes the 2026 FOMC calendar** and hides the
   label entirely past Dec 2026 — same expiry as `ReleaseCalendar` (flagged in
   9a). Two places now carry a 2026-only table.

4. **`applyWatchlistOrder` runs on every `onViewCreated` and again on watchlist
   finish**, doing a remove/re-add of six views each time. Harmless but does
   trigger a full re-layout; a no-op guard when the order is unchanged would be
   cheap.

---

## What to exercise on device

Overview is the app's first screen, so most of this is the launch path:

1. **Cold launch with app data cleared** — the shimmer skeleton should show,
   then cross-fade to real content once data lands. This is `hideSkeleton()`
   plus the cache-first branch.
2. **Warm launch** — content should appear immediately with *no* shimmer
   (`viewModel.hasCache()` path), and the "as of" cache label should read right.
3. **Pull to refresh** — spinner runs, a haptic tick fires on completion, values
   roll/count up to their new numbers (TICKET-27 `ValueAnimatorUtil`).
4. **All six KPI cards + the Fed funds hero**: value, date, delta chip with the
   right colour and ▲/▼, tier dot colour, status word, percentile, and a
   sparkline. The sparkline should be gold — that is the hex cast.
5. **Tap each KPI card** → its benchmark sheet opens. **Long-press each** → the
   Analyst opens with a concise, context-aware answer.
6. **Tap the Fed funds hero** → the Fed Funds history dialog.
7. **First-run watchlist sheet** (clear app data to reach it): pick metrics in a
   deliberate order, hit Done, and confirm the Overview clusters **reorder** to
   match. This is the `fun interface` callback firing — the single most
   important check in this commit.
8. **"Since you last opened" strip** on a warm launch — appears with text, and
   the × dismisses it for the session.

Item 7 is the one to do first if you only do one.
