# Kotlin migration — batch 12a (dead-code removal + check_badges fix)

Date: 2026-07-30
Branch: `kotlin/batch-12a`
Net: **+40 / −536 lines** across 15 files (5 files deleted outright).

Batch 12 is the first batch where rule 4 ("syntax only, do not refactor
behavior") no longer applies — it is the cleanup batch that rule 4 was deferring
work into. Scope was agreed before starting: **12a dead code, 12b the two
correctness fixes, 12c the retry-chip helper.** The interop-annotation strip
(~168 `@JvmStatic` + ~128 `@JvmField` that no longer have Java callers) was
considered and **deliberately dropped** — largest diff on the list, zero
behavioral gain, and it perturbs R8 name assignment across every file, which is
the lesson-14 failure mode. The annotations are harmless dead weight.

---

## The headline finding: the dead code was one feature, not seven leftovers

The accumulated follow-ups across eleven CHANGES files read as a scatter of
unrelated dead methods. They were not. ~420 of the ~550 candidate lines were a
single unwired feature — the NewsAPI + Smart Prompts path from the unmerged
`feature/newsapi-smartprompts` branch (3 commits, local + origin). Its UI half
was merged into `main` at some point; its data half never got wired up.

```
NewsArticleRepository.kt   59 lines, zero callers
  └── NewsApiClient.kt    158 lines, no consumer but the above
  └── NewsArticleDao.kt    36 lines, no consumer but the above
PromptAdapter.kt           65 lines  ─┐ zero callers
item_smart_prompt.xml      50 lines  ─┘
SmartPromptGenerator       52 lines of article-based methods + EconomicCategory
```

That branch cannot be merged either way: it is 5,320 lines of pre-migration Java
against a tree that is now 100% Kotlin. Reviving the feature means
re-implementing, not merging. Deleted per your decision.

### What was kept, and why

**`models/NewsArticle.kt` and its `@Database(entities = [...])` registration
stay.** It is a Room entity in the live schema at `version = 5`; deleting it is a
schema change requiring a version bump and a migration, which is not cleanup and
does not belong in batch 12. The `news_articles` table now exists with no DAO —
an orphan, harmless, and documented in a note added to `YieldDatabase`'s KDoc.

The live News tab is unaffected: it uses the completely separate RSS-backed
`news/NewsRepository`, which never touched this table.

---

## Files deleted (5)

| File | Lines | Why |
|---|---|---|
| `api/NewsApiClient.kt` | 158 | Only consumer was `NewsArticleRepository` |
| `api/NewsArticleRepository.kt` | 59 | Zero callers since batch 4 |
| `database/NewsArticleDao.kt` | 36 | Only consumer was `NewsArticleRepository` |
| `ui/PromptAdapter.kt` | 65 | Zero callers since batch 7 |
| `res/layout/item_smart_prompt.xml` | 50 | Only inflated by `PromptAdapter` |

## Files edited (10)

| File | Removed |
|---|---|
| `analyst/SmartPromptGenerator.kt` | `generatePromptsForArticle`, `generateComparativePrompts`, `generateOutlookPrompts`, the whole `EconomicCategory` enum + `detectFromArticle`, and the now-unused `NewsArticle` import. Only `generatePromptsForHeadline` was ever live (`NewsAdapter.kt:112`). |
| `database/YieldDatabase.kt` | `abstract fun newsArticleDao()`. Entity list untouched — **no schema change**. Added a KDoc note explaining the orphan table. |
| `ui/MainActivity.kt` | `updateHeader()` (empty body) **and all six of its call sites**. |
| `ui/EconomicViewModel.kt` | `filterByTimeframe(ctx, rows)` 2-arg overload (all 13 sites use the 3-arg form); `statusMsg` LiveData + `getStatusMsg()` (written by nothing, observed by nothing). |
| `ui/MetricBottomSheet.kt` | `show(ctx, layoutRes)` 2-arg overload — all six call sites use the 4-arg form. Merged the orphaned KDoc into the surviving function. |
| `ui/AiAnalystBottomSheet.kt` | `newInstanceWithContext(String, String)`; the 1-arg `submitExternalQuery(String)`. Merged its KDoc into the 3-arg form. |
| `ui/fragments/DashboardFragment.kt` | `wireCardTapAnalyst` + `buildAskQuery` (the latter existed only to serve the former). |
| `ui/fragments/MarketsFragment.kt` | `viewModel` and `pagerAdapter` fields (both assigned, never read) + the now-unused `ViewModelProvider` / `EconomicViewModel` imports. |
| `ui/EconomicPagerAdapter.kt` | deleted (zero callers since batch 7) |
| `scripts/check_badges.sh` | see below |

### One judgment call worth reviewing

`MainActivity` line 190 was `CacheManager.forceRefreshAll(this) { runOnUiThread { updateHeader() } }`.
Removing `updateHeader` leaves the callback empty, but **the `forceRefreshAll`
call itself must stay** — the refresh is the side effect that matters; the
callback was only a UI notification. It is now `forceRefreshAll(this) { }` with a
comment saying so. Same reasoning applies to the observer at old line 614
(`getFedFundsData().observe { updateHeader() }`), which was removed entirely
because its body was a no-op — the observer registration was the only thing it
did.

---

## `scripts/check_badges.sh` — two bugs, both fixed (open item 3)

Folded into 12a per your decision. `./gradlew check` was red on this
pre-existing, migration-unrelated task. It turned out to be **two** bugs, not one.

**1. Word-splitting on a path with a space.** Line 79 was `for f in $dot_hits`,
unquoted. The repo path contains a space (`C:\Android App\...`), so every path
split into fragments mid-string, and the script's own `$BADGE_LAYOUT` exclusion
on the next line could therefore never match — it flagged `view_status_badge.xml`,
the exact file it tells you to use. Fixed with `while IFS= read -r f`, matching
the idiom already used at line 50.

**2. A fixed line window that bled into sibling views.** With bug 1 silenced, a
second failure surfaced underneath it: `sheet_settings.xml:435` (`tvNotifStatus`).
That element declares no styling at all — just `style=` and a margin. The check
scanned `NR>=s-8 && NR<=s+10` around the matched id, a window that reached into
the *next* `<TextView>` and picked up `tvNotifBlocked`'s `android:textColor` at
line 442, then blamed it on `tvNotifStatus`.

Fixed by bounding the scan to the single XML element that declares the id — walk
back to its opening tag, forward to its closing bracket. Verified against a
synthetic layout that this is precise in both directions: a neighbour's
`textColor` is no longer attributed to a clean id, and a genuine inline
`textColor`/`textSize` on a status TextView still flags.

**`./gradlew check` is now fully green.** The `-x checkBadges` workaround in the
standing rules is no longer needed and has been removed from `KOTLIN_MIGRATION.md`.

---

## Verification

```
./gradlew check      → BUILD SUCCESSFUL (lint + tests + checkBadges)
./gradlew testDebugUnitTest checkBadges → 43 tests, 0 failures
```

Test breakdown unchanged from batch 11: 11 + 21 + 2 + 9 = 43.

The build caught one real mistake: a **sixth** `updateHeader()` call site at
line 657 (inside the clear-cache dialog's confirm handler) that the initial grep
sweep miscounted. Under the pre-batch-12 "verify by inspection only" regime this
would have shipped as a compile break. Worth recording as the first concrete
payoff of the corrected standing rule 1.

Phase 4 greps confirm zero dangling references to any of the 18 deleted symbols,
and `proguard-rules.pro` references none of the deleted classes.

Interop note: none of the four Java test files touched anything deleted here.
The `@JvmStatic`/`@JvmField` they do depend on (13 and 1 respectively) are all
untouched — see the annotation-strip decision at the top.

---

## What to exercise on device

Everything here is deletion of code with no callers, so the risk profile is
"something was less dead than the greps proved." The realistic failure mode is a
reflective or XML-driven reference no grep would find.

1. **Gradle sync, then Build → Rebuild Project.**
2. **Chat history canary (do this first).** `YieldDatabase` was edited — a DAO
   accessor was removed. The entity list and `version = 5` are untouched, so
   Room's identity hash should be unchanged and no migration should fire. Open
   the AI Analyst and confirm prior messages are still there. **If chat history
   is gone, stop and tell me** — that would mean the identity hash shifted and
   `fallbackToDestructiveMigration()` ran.
3. **Markets tab** — `MarketsFragment` lost two fields and two imports. Confirm
   the STOCKS/BONDS/YIELDS/SPREADS tab strip still renders and all four pages
   swipe.
4. **News tab** — load articles, then long-press a headline for AI prompts. This
   is the live `generatePromptsForHeadline` path, the one survivor in
   `SmartPromptGenerator`. Confirm prompts still appear.
5. **Any benchmark sheet** (CPI / GDP / Wages status dialogs, Fed Funds history
   from the dashboard) — exercises the surviving 4-arg `MetricBottomSheet.show`.
6. **Dashboard** — pull-to-refresh, and Settings → Refresh Data. Exercises the
   two `CacheManager.forceRefreshAll` call sites whose callbacks changed.
7. **Backgrounding and reopening the app** — `onResume` lost its `updateHeader()`
   call. Confirm the header/title still reads correctly on return, and that the
   News and Analyst badges still refresh.
8. **AI Analyst long-press entry** from any metric card — exercises the merged
   `submitExternalQuery(query, screenContext, concise)`.

If red: paste the exact error back and I will not start 12b.

---

## Follow-up items spotted, not fixed

1. **`ApiConfig.NEWS_API_KEY` is now dead.** Its only consumer was
   `NewsArticleRepository`. Left in place deliberately — removing it means
   editing `build.gradle`'s `BuildConfig` field too, and that is a build-config
   change, a different category from source cleanup. One line in `ApiConfig.kt`
   plus one in `build.gradle` whenever you want it.
2. **`news_articles` is now an orphan table** — entity registered, no DAO, no
   reads or writes. Cleaning it up properly is a `version = 6` migration with a
   `DROP TABLE`. Worth doing the next time the schema changes for another
   reason, not on its own.
3. **`MetricBottomSheet.show`'s `seriesKey`/`seriesLabel` are now always
   non-null at every call site** (all six pass real values). The parameters are
   still typed `String?`. Could be tightened to non-null, which would remove the
   internal null branches. Not done — it is a signature change, better suited to
   12c's refactor slot if you want it.
4. **`DashboardFragment.showBenchmarkDialog(layoutRes)` and
   `InflationFragment.showBenchmarkDialog(layoutRes)` 1-arg forms** — noticed
   while removing the `MetricBottomSheet` overload. Not audited for callers this
   batch; worth a grep in 12c.
5. Still open from earlier batches, untouched here and **not** in the agreed 12
   scope: the `parseArticleJson` NPE follow-up is now **moot** (that file is
   deleted); the `ThumbLoader`/`NewsRepository` ad-hoc thread pools, the
   `appendGdpBlock` off-by-one separator, the uncleaned
   `AnalystToolExecutor.setLiveSeriesProvider` static, the un-removed
   `OnGlobalLayoutListener`, the 2026-only FOMC/CPI tables in two places, and
   `EconomicDataPoint`'s Gson-via-`Unsafe` nullability hole all remain.

---

## Next

**12b** — the two correctness fixes: `Double.MIN_VALUE` → `-Double.MAX_VALUE` as
a max-seed at `HistoricalContextBuilder.kt:116` and `:142`, and
`lowercase(Locale.getDefault())` → `Locale.US` at `SmartPromptGenerator.kt:22`
(the second `Locale.getDefault()` site at old line 108 went away with
`detectFromArticle`, so only one call site remains to fix).

**12c** — the retry-chip helper: 13 identical lines × 10 chart fragments,
differing only in the `retryKeys` array. A `wireRetryChip(view, viewModel, vararg
keys)` helper collapses ~130 lines to ~10.

Per standing rule 2: do not start 12b until this is confirmed built and run on
device.
