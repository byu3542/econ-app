# Kotlin Migration — Batch 10a

First of three commits splitting batch 10 (`ui/fragments/` + deferred pieces,
~4,170 lines). This one is the **structural hosts**: 6 files, ~561 lines.

Branch: `kotlin/batch-10a`.

Deliberately small. It proves two patterns cheaply before the bulk lands:
the **plain-fragment shape** (ViewBinding + `onDestroyView` teardown) and the
**nested `FragmentStateAdapter`** shape deferred from batch 7.

**10b** = the 10 chart/metric fragments (~2,877 lines, one commit each).
**10c** = `DashboardFragment` alone (732 lines), last as the migration doc says.

---

## Files converted

| File | Notes |
|---|---|
| `ui/fragments/EconomyFragment.java` → `.kt` | + nested `EconomyPagerAdapter` (deferred from batch 7) |
| `ui/fragments/TreasuryFragment.java` → `.kt` | + nested `MarketsTabAdapter` (deferred from batch 7) |
| `ui/fragments/MarketsFragment.java` → `.kt` | uses the already-Kotlin `MarketsPagerAdapter` |
| `news/NewsFragment.java` → `.kt` | deferred from batch 8 |
| `news/NewsViewModel.java` → `.kt` | deferred from batch 8 |
| `ui/onboarding/WatchlistSetupFragment.java` → `.kt` | |

All 6 `.java` originals deleted. **No other file was touched.**

With this, the batch-7 and batch-8 deferrals are both closed out.

---

## The decision that would have hidden itself (again)

**`WatchlistSetupFragment.Listener` is now a `fun interface`.**

`DashboardFragment.java:149` supplies it as a **Java lambda**:

```java
sheet.setListener(() -> { if (getView() != null) applyWatchlistOrder(getView()); });
```

`DashboardFragment` stays Java until **10c**. Java SAM-converts to any
single-method interface regardless of source language, so a plain Kotlin
`interface` would build green *here* and break in 10c when that lambda becomes
Kotlin — a Kotlin lambda does not convert to a plain Kotlin interface.

This is the identical trap to `AnalystToolExecutor.LiveSeriesProvider` in batch
9b, and the same structural blind spot: the split cannot catch it at build time,
so it has to be decided in the earlier commit.

**Worth generalising:** every time a batch converts an interface whose
implementer is still Java, check whether that implementer is a lambda and
whether it converts later. If both, the interface needs `fun`.

---

## Other decisions

**1. Nested adapters stay nested, not `inner`.** `EconomyPagerAdapter` and
`MarketsTabAdapter` were Java `private static class`. Kotlin's default nested
class is the equivalent; marking them `inner` would capture an outer `Fragment`
instance they don't need and change construction.

**2. `const val TAG` on `WatchlistSetupFragment`** — `DashboardFragment.java`
reads `WatchlistSetupFragment.TAG` while still Java. A plain `val` would need
`Companion.getTAG()`.

**3. `0xFFc9a84c.toInt()` in `NewsFragment`.** Eight hex digits — Kotlin infers
`Long` without the cast, and `setColorSchemeColors` takes `int` (migration
lesson 4). This is the first place in the whole migration where that lesson has
actually bitten rather than being preventive.

**4. `MutableLiveData<String?>` for `errorMessage`.** `loadNews()` clears the
previous error with `setValue(null)`, which will not compile against a non-null
type parameter.

**5. Dead null check collapsed.** `NewsRepository.fetchAllFeeds()` returns a
non-null `List` (Kotlin since batch 8), so the original's
`result != null ? result : new ArrayList<>()` fallback is statically dead.
The `items == null` check in `NewsFragment`'s observer is **kept** — `NewsAdapter.submitList`
genuinely accepts `List<NewsItem>?`.

**6. `binding` teardown preserved exactly.** Each fragment keeps
`var binding: T? = null` set in `onCreateView` and nulled in `onDestroyView`,
with every read captured into a local `val b = binding ?: return`. Zero `!!`
across all six files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`MarketsFragment.viewModel` and `.pagerAdapter` are assigned and never
   read.** `viewModel` is fetched from `ViewModelProvider` in `onViewCreated`
   and never used; `pagerAdapter` is only set on the ViewPager. Both ported
   as-is. Candidates for deletion in batch 12.

2. **`MarketsFragment.pagerAdapter` is never nulled in `onDestroyView`** while
   `binding` is. Harmless (the fragment is discarded too) but inconsistent.

3. **`NewsFragment`'s `textNewsMeta` always says "just now"** regardless of
   actual fetch age — the string resource takes a time argument that is
   hardcoded. Pre-existing.

---

## What to exercise on device

All six are UI entry points, so this is mostly visual. A clean rebuild plus:

1. **Markets tab** → the four sub-tabs (STOCKS / BONDS / YIELDS / SPREADS) must
   appear and swipe between correctly. That is `MarketsFragment` +
   `MarketsPagerAdapter`.
2. **Economy tab** → four sub-tabs (GDP / Employment / Inflation / Housing),
   swipeable. That is the nested `EconomyPagerAdapter` — a `getItemCount()` or
   `createFragment()` mistake shows as a missing or wrong tab.
3. **The Yields/Spreads host** (`TreasuryFragment`) if it is reachable in your
   nav — it is constructed from `EconomicPagerAdapter.kt`.
4. **Check the top inset on Markets and Economy** — their TabLayouts must sit
   *below* the translucent header, not underneath it (the IOS-04 fix). This is
   the one thing that silently regresses if `applyTopInset` moved.
5. **News tab** — list loads, filter chips work, swipe-to-refresh spins and
   stops, the gold refresh colour is correct (that is the hex-literal cast), and
   the "clear filter" button resets to All.
6. **First-run watchlist sheet** — hardest to reach, since it is guarded by
   `isOnboardingComplete`. If you want to test it, clear app data first. Tick a
   few boxes (progress bar should fill "N of 6"), hit Done, and confirm the
   Overview reorders. That last step is the `fun interface` callback firing.

Item 6 is the highest-value check but the least convenient; item 4 is the one
most likely to regress unnoticed.
