# Kotlin Migration — Batch 4 (Room DAOs + database + repository layer)

Branch: `kotlin/batch-4`

## Scope note

The batch table said "repository" (singular). You chose to include all 4
`*Repository.java` classes rather than just `CacheManager`, so this batch
ended up covering **10 files, ~1,850 lines** — the largest batch so far by a
wide margin.

## Files converted

| File | Shape |
|---|---|
| `ChatMessageDao`, `EconomicHistoryDao`, `NewsArticleDao`, `TreasuryYieldDao` | `interface` (Room `@Dao`) |
| `YieldDatabase` | `abstract class : RoomDatabase()`, keeps `getInstance(Context)` singleton shape |
| `CacheManager` | `object` facade |
| `EconomicRepository` | `class` (instance-based, matches `new EconomicRepository()`) |
| `TreasuryYieldRepository` | `class` (takes `Context`) |
| `NewsArticleRepository` | `class` (takes `Context`) — **confirmed dead code**, zero callers anywhere in the repo; converted as-is, not removed (not this migration's call) |
| `HistoricalDataRepository` | `object` (was already 100% static methods) |

## Interop annotations added and why

- **`@JvmStatic`** on `YieldDatabase.getInstance`, all of `CacheManager`'s and
  `HistoricalDataRepository`'s public functions — standard, same reasoning
  as every prior batch.
- **`@JvmField`** on `HistoricalDataRepository`'s 6 `SERIES_*` constants —
  `HistoricalContextBuilder.java` reads these as raw fields
  (`HistoricalDataRepository.SERIES_10Y_TREASURY`). Also on `CacheManager`'s
  `STALE_AFTER_DAYS`/`RETENTION_MONTHS` and `CacheStatus`'s 5 fields —
  defensive, per established precedent, even though nothing currently reads
  them raw.
- **No `@JvmField` on any Room entity or model field this batch** — not
  applicable here since none of these 10 files are entities themselves.
- `CacheManager.StatusCallback`/`OperationCallback` and
  `HistoricalDataRepository.RefreshCallback` are `fun interface` — lets this
  batch's own Kotlin-to-Kotlin calls (e.g. `CacheManager` calling
  `HistoricalDataRepository.refreshIfStale(context) { ... }`) use trailing
  lambda syntax. Doesn't change how Java calls them (Java lambda-to-SAM
  conversion works against any single-abstract-method interface regardless
  of the `fun` keyword).

## `fetchFredData`'s 4-arity overload chain — kept as real overloads, not `@JvmOverloads`

`EconomicRepository.fetchFredData` has 4 Java overloads (3/4/5/6 args), each
delegating to the next with a default. The natural Kotlin move is default
parameter values + `@JvmOverloads` — but `@JvmOverloads` only generates
overloads by dropping *trailing* defaulted parameters, and at every real
call site `callback` is the **last** argument, after the ones that would
need defaults (`limit`/`frequency`/`category`). Moving `callback` earlier to
make `@JvmOverloads` work would silently change the argument order at 15
call sites in `EconomicViewModel.java`. Instead, this batch keeps 4 genuine,
separate Kotlin function overloads with identical signatures to the
original — no annotation needed, since real overloading (as opposed to
default-parameter elision) works identically for Java callers either way.
Verified all 15 `EconomicViewModel.java` call sites (both 6-arg and the
single-callback forms for `fetchPCEData`/`fetchHousingData`/etc.) compile
against this unchanged.

## Two forced null-handling adjustments (not present in the original)

Both are consequences of batch 3's `EconomicDataPoint.series: String` being
non-null, colliding with genuinely-nullable Gson fields:

- **`EconomicRepository.fetchGDPData`**: `BeaDataPoint.getDescription()` can
  return null if BEA's response omits both `LineDescription` and
  `SeriesCode`. Original Java passed that possible null straight into
  `EconomicDataPoint`'s constructor without complaint. Since that
  constructor param is now non-null, this batch skips the entry instead
  (`description != null` guard) rather than crash or contort
  `EconomicDataPoint`'s nullability for one theoretical BEA-omission case.
- **`EconomicRepository.fetchBlsData`**: `BlsSeries.seriesID` is nullable;
  the fallback `seriesName` (when no configured name matches) now defaults
  to `""` instead of a possible null.

Both are edge cases that essentially never fire in practice (would require
BEA/BLS to omit fields their APIs always populate) — flagging for
completeness, not because either is expected to matter.

## Zero `!!`

This was the hardest batch to keep `!!`-free — `EconomicRepository.kt` and
`HistoricalDataRepository.kt` both do heavy Retrofit/Gson response handling
with several layers of nullable chaining (`response.body()`,
`.observations`, `.results?.series`, etc.). Every one of these was handled
by capturing into a local `val` right after the null check (so the compiler
can smart-cast) rather than repeated `!!` on function-call results, which
Kotlin can't smart-cast across. Grepped the whole batch — zero `!!`
anywhere in the 10 files.

## Left non-idiomatic / deliberately not touched

- `YieldDatabase.getInstance` keeps manual double-checked locking (via the
  canonical Kotlin `instance ?: synchronized(lock) { instance ?: ... }`
  idiom) rather than becoming a bare `object`, unlike `AppExecutors` in
  batch 1 — Room's builder genuinely needs a runtime `Context` argument, so
  a parameterless `object` isn't a fit here regardless of caller count.
- `NewsArticleRepository`'s dead-code status — noted above, not removed.

## Follow-up bugs spotted, not fixed

- `NewsArticleRepository.java` (now `.kt`) has zero callers anywhere in the
  codebase. Worth a decision at some point on whether to wire it up or
  delete it — out of scope for a syntax migration.

## What Aaron needs to exercise on device

This batch touches the entire data-fetching and caching pipeline, so a full
pass is warranted:

1. **Gradle sync, then Build → Rebuild Project.**
2. **Fresh launch / pull-to-refresh on Dashboard**: exercises
   `EconomicRepository`'s FRED/BEA/BLS/Treasury fetchers end-to-end
   (heaviest-edited code in this batch) plus `TreasuryYieldRepository` and
   `HistoricalDataRepository` caching through the DAOs.
3. **Settings → Refresh Data / Clear Cache**: exercises `CacheManager`'s
   `forceRefreshAll`/`clearAllCaches`/`getStatus` — confirm the cache-age
   text updates correctly after each action.
4. **AI Analyst historical questions**: exercises
   `HistoricalDataRepository` + `EconomicHistoryDao` reads through
   `HistoricalContextBuilder`/`AnalystToolExecutor` (already batch-3
   verified callers, now calling into batch-4 Kotlin).
5. **GDP tab specifically**: this is the one path with the new
   null-skip behavior (`fetchGDPData`'s `description != null` guard) —
   confirm GDP data still populates normally.
6. **Chat history canary again**: same as batch 3 — send a message, rebuild,
   confirm it's still there. `YieldDatabase`'s migration/singleton logic was
   fully rewritten this batch (still the same SQL, but worth re-confirming
   given the scope).
7. **Background workers**: `CacheCleanupWorker` (purgeOldData) and
   `YieldRefreshWorker` if practical to trigger.

If red: paste the exact error back and I will not start batch 5.
