# Historical cache expansion — every ApiConfig series in `economic_history`

Date: 2026-07-30
Net: 6 files edited, 1 test file added. No Room schema change, no DB version bump.

`HistoricalDataRepository` cached six series behind one global staleness check.
It now caches **34**, driven by `EconomicSeriesRegistry` as a fetch catalog, with
per-series staleness on a tiered cadence.

Build verified: `gradlew assembleDebug` and `gradlew check` both succeed.
**58 unit tests, 0 failures** (was 43; +15 new).

---

## What changed

### 1. `EconomicSeriesRegistry` is now the fetch catalog

`Entry` grew from 4 fields to 11. One entry fully describes how to fetch a
series and how to store it:

| Field | Purpose |
|---|---|
| `id` | Registry/`seriesId` key — **unchanged for every pre-existing series** |
| `chartLabel`, `toolHint` | Unchanged; still two independent labels by design |
| `source` | `ROOM_CACHED` / `LIVE` |
| `apiSource` | `FRED` / `BLS` / `BEA` / `TREASURY` |
| `remoteId` | Provider's own id — always an `ApiConfig` constant, never a literal |
| `unit` | `"%"`, `"Index"`, `"$/hr"`, `"$/wk"`, `"$M"`, `"Thousands"`, `"Units"` |
| `category` | `Treasury`/`Employment`/`Housing`/`GDP`/`Inflation`/`Markets`/`Credit`/`Policy` |
| `frequency` | FRED aggregation param (`d`/`w`/`m`) or BEA frequency |
| `limit` | Record count, from `ApiConfig` |
| `tier` | `DAILY` / `MONTHLY` / `QUARTERLY` |

Plus `Tier.isStale(last, now)`, `byId()`, `fetchableEntries()`, and
`treasuryEntryForMaturity()`.

**No `id` changed.** Ten series are registered under app-level aliases (`CPI`,
`SP500`, `VIX`, `WAGES`, `PCE`, `CORE_PCE`, `HOUSING_STARTS`, `HOME_SALES`,
`BAA_SPREAD`, `HY_SPREAD`); their provider ids moved into `remoteId`.
`AnalystToolExecutor`'s tool enum, `ChatAdapter.SERIES_LABELS`, and the chat
`[CHART:...]` tags all still resolve — `preExistingIdsArePreserved` pins this.

Eleven entries flipped `LIVE` → `ROOM_CACHED` now that they are actually fetched.

### 2. `HistoricalDataRepository` is registry-driven

Six hardcoded fetch calls → three generic fetchers keyed off `apiSource`:

- **FRED** — one task per stale entry, `remoteId`/`frequency`/`limit`/`unit`
  from the catalog. The old code hardcoded `"%"` for every FRED series.
- **BLS** — all stale BLS series in **one** POST. The v2 API accepts multiple
  `seriesid` values; the response is split back out by matching each returned
  `seriesID` to its entry's `remoteId`. Seven series, one request.
- **BEA** — unchanged behavior, now parameterized by the entry.

`total = 6` became `fred.size + (bls.isEmpty() ? 0 : 1) + bea.size`. The
`AtomicInteger` completion callback still fires exactly once, after every task.

### 3. Per-series staleness

The global `MAX(cachedAtMillis)` check is gone. `refreshIfStale` now calls
`dao.getLastCacheTimeForSeries(id)` per entry and fetches only what has aged
past its own tier:

| Tier | Max age | Series |
|---|---|---|
| `DAILY` | ~1 day | `DGS10` `DGS2` `DGS3MO` `DFF` `SP500` `NASDAQ` `WILL5000INDX` `VIX` `BAA_SPREAD` `HY_SPREAD` + 8× `UST_*` |
| `MONTHLY` | ~7 days | `MORTGAGE30US` `TMBACBW027SBOG` `WSHOMCB` `PCE` `CORE_PCE` `HOUSING_STARTS` `HOME_SALES` `NAPM` + all 7 BLS |
| `QUARTERLY` | ~30 days | `GDP_BEA_T10101` |

`forceRefresh` still ignores tiers and refetches everything.

### 4. Concurrency

`AppExecutors.networkIO()` was already bounded at 4 — not unbounded — but it is
**shared** with the dashboard's own fetches, so queueing ~20 catalog requests on
it would stall the visible UI for seconds on a cold start. History fetches now
run on a dedicated daemon pool of 4 (`historyIO`). Cold start is 20 HTTP calls
(18 FRED + 1 batched BLS + 1 BEA), 4 at a time.

### 5. Treasury curve, mirror-written

The 8 constant maturities land in `economic_history` as `UST_1M` … `UST_30Y`
so the Analyst can query the curve through the same path as everything else.

**No second network call.** `TreasuryYieldRepository.updateCache()` already has
the parsed points from the XML it downloaded for `treasury_yields`; it now also
writes them to `economic_history` via `mirrorToHistory()`. `HistoricalDataRepository`
skips `ApiSource.TREASURY` entries entirely (`fetchableEntries()`), and
`fetchableEntriesExcludeTreasury` pins that. The duplication is commented at both
ends: `treasury_yields` is a cleared-on-refresh snapshot, `economic_history` is
an accumulating cache.

Mirror failures are swallowed — the `treasury_yields` write is what the UI
depends on and must not go down with it.

---

## Series added (28 new)

**FRED (12 new, 18 total):** `DFF`, `NAPM`, `WILL5000INDX`, `TMBACBW027SBOG`,
`WSHOMCB`, plus `SP500` `NASDAQ` `VIX` `PCE` `CORE_PCE` `HOUSING_STARTS`
`HOME_SALES` `BAA_SPREAD` `HY_SPREAD` flipped from live-only to cached.
(Previously cached: `DGS10` `DGS2` `DGS3MO` `MORTGAGE30US`.)

**BLS (6 new, 7 total):** `LNS12000000`, `LNS11300000`, `CUSR0000SA0`,
`CES0500000011`, plus `CPI` and `WAGES` flipped from live-only.
(Previously cached: `LNS14000000`.)

**BEA (0 new):** `GDP_BEA_T10101`, unchanged.

**Treasury (8 new):** `UST_1M` `UST_3M` `UST_6M` `UST_1Y` `UST_2Y` `UST_5Y`
`UST_10Y` `UST_30Y`.

### Units fixed

The old fetcher stored `"%"` on every FRED row. The AI quotes the unit verbatim,
so "S&P 500 at 5432.10%" was a live correctness bug:

| Series | Was | Now |
|---|---|---|
| `SP500` `NASDAQ` `WILL5000INDX` `VIX` `PCE` `CORE_PCE` `CPI` `CUSR0000SA0` `NAPM` | `%` | `Index` |
| `HOUSING_STARTS` `LNS12000000` | `%` | `Thousands` |
| `HOME_SALES` | `%` | `Units` |
| `TMBACBW027SBOG` `WSHOMCB` | `%` | `$M` |
| `WAGES` | `%` | `$/hr` |
| `CES0500000011` | `%` | `$/wk` |

`unitsAreCorrectPerSeries` pins all 28.

---

## Row-count estimate

26-month retention (`CacheManager.RETENTION_MONTHS`, unchanged):

| Group | Series | Rows each | Total |
|---|---|---|---|
| FRED daily | 10 | ~566 business days (`DFF` ~792) | ~5,900 |
| FRED weekly | 3 | ~113 | ~340 |
| FRED monthly | 5 | ~26 | ~130 |
| BLS monthly | 7 | ~26 | ~180 |
| BEA quarterly | 1 | ~9 | ~9 |
| Treasury `UST_*` | 8 | 200–566 (accretes ~30 dates/refresh) | 1,600–4,500 |

**≈ 8.2k–11.1k rows, ~1 MB** — up from ~600. Comfortably under the 30k
threshold, so `RETENTION_MONTHS` is untouched and daily series keep daily
resolution rather than being coarsened to weekly.

`CacheCleanupWorker` / `purgeOldData` are series-agnostic (`DELETE WHERE date <
cutoff`) and scale as-is. `CacheManager.getStatus()` already reports
`getSeriesCount()`, which now reads 34 instead of 6; `getLastCacheTimeSync()`
still backs the "Data updated N ago" string correctly as a `MAX` across series.

---

## Call-site changes

### `AnalystToolExecutor`

Caching daily series broke two assumptions in the Room read path, both fixed:

1. **No down-sampling.** `getCachedSeries` printed every row. A 24-month `SP500`
   request would have dumped ~550 lines into the model's context. Both paths now
   share a `render()` that samples to ≤40 points — the logic `getLiveSeries`
   already had.
2. **YoY pre-computation was live-path-only.** The TICKET-4 / AI Law 15–16 guard
   that hands the model a guaranteed-correct trailing YoY for `CPI`/`PCE`/
   `CORE_PCE` lived in `getLiveSeries`; routing those ids to Room would have
   silently dropped it. Extracted to `appendYoyIfIndexLevel()`, called from both,
   and always computed from the **full** series, never the sampled window.

Also: `getCachedSeries` falls back to the live provider when Room has no rows
(cold first launch, before the catalog refresh lands), and values now render as
`4.25%` / `5432.10 Index` — a separator for every unit except `%`.

**No prompt wording changed.** The system prompt's INLINE CHARTS sentence lists
`allIds()`, so it now names 34 ids instead of 17 — the template is untouched.

### `ChatAdapter`

The `[CHART:...]` path branches on `LIVE_SERIES.containsKey()`, which is now
empty, so every sparkline reads Room. Added the same cold-cache fallback: if
Room yields <2 points, draw from the live provider.

### `ApiConfig`

Two new constants, `FRED_CACHE_DAILY_LIMIT` (800) and `FRED_CACHE_MONTHLY_LIMIT`
(36), used only by the history cache. Existing limits (`FRED_MBS_LIMIT`,
`FRED_PCE_LIMIT`, `FRED_HOUSING_LIMIT`, `BLS_HISTORY_YEARS`) are reused where
they apply; the dashboard's own `STOCK_INDICES_LIMIT` / `BOND_SPREADS_LIMIT` are
untouched, since 120 daily points is six months — fine for a chart, not for a
26-month cache.

### `forceRefresh` no longer calls `clearAll()`

A blanket wipe would also delete the `UST_*` rows, which this class does not
fetch and so would not restore. It now deletes only the series it is about to
refetch.

---

## Tests

`app/src/test/java/com/economic/dashboard/analyst/EconomicSeriesCatalogTest.java`
— 15 tests, all pure JVM (no Android framework, no DB, no network; this module
has no `returnDefaultValues`, so anything touching `android.util.Log` would
throw).

- **Tier staleness** — boundary cases per tier, never-cached, and that a
  3-day-old series is stale at `DAILY` but fresh at `MONTHLY`/`QUARTERLY`.
- **Catalog completeness** — every `ApiConfig` series constant appears exactly
  once as a `remoteId`, referenced by constant so a rename is a compile error;
  all 8 `TREASURY_FIELDS` covered and reachable via `treasuryEntryForMaturity()`;
  plus a reflection guard that fails if any `FRED_*`/`BLS_*` constant outside a
  known non-series set escapes the catalog.
- **Unit correctness** — 28 explicit assertions.
- Id uniqueness, pre-existing ids preserved (incl. the `SERIES_*` constants
  `HistoricalContextBuilder` uses), Treasury excluded from `fetchableEntries()`,
  and every entry carrying usable metadata.

---

## Follow-ups

1. **`NAPM` is a discontinued FRED series.** ISM withdrew redistribution rights;
   observations end around 2016. It is catalogued because `ApiConfig` declares
   it, but expect `0 valid observations` in the log rather than current PMI
   prints. Worth either sourcing PMI elsewhere or dropping the constant.
2. **`ApiConfig.BLS_CPI_W` is misnamed.** `CUSR0000SA0` is seasonally-adjusted
   CPI-**U**; true CPI-W is `CWUR0000SA0`. `EconomicRepository` still labels it
   "CPI-W All Items" on the dashboard. The new catalog entry is labelled
   accurately rather than propagating the misnomer into AI output — so the chart
   caption and the Analyst now disagree until the dashboard label is corrected.
3. **`HistoricalContextBuilder` still covers only the original six series** and
   still says "2-Year Window". It compiles and behaves exactly as before. Widening
   it means editing prompt content, which was explicitly out of scope here.
4. **`UST_10Y` and `DGS10` are the same quantity from different publishers.**
   Their `toolHint`s say so, but a future pass might collapse the overlap or
   teach the model when to prefer which.
5. **Treasury history only accretes while the app is used.** The XML fetch covers
   ~30 days at a time, so `UST_*` reaches full 26-month depth only after months
   of regular use — unlike FRED series, which backfill in one call.
6. **`HistoricalDataRepository.forceRefresh` alone does not restore `UST_*`.**
   `CacheManager.forceRefreshAll` does, because it kicks
   `TreasuryYieldRepository.forceRefresh()` first — but the two are independent
   async paths, not a guaranteed ordering.
