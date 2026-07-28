# Kotlin Migration — Batch 5 (`SettingsManager`, `TimeframeSelector`)

Branch: `kotlin/batch-5`

## Files converted

| File | Shape |
|---|---|
| `SettingsManager` | `object`, ~30 `@JvmField` constants |
| `TimeframeSelector` | `object`, `fun interface OnTimeframeChange` |

## Interop annotations added and why

- **`@JvmField` on all ~30 `KEY_*` constants, `PREFS_NAME`, and
  `DEFAULT_WATCHLIST`** — checked every one of the 18 caller files; most of
  the keys are read as raw fields (`SettingsManager.KEY_HAPTICS`,
  `SettingsManager.DEFAULT_WATCHLIST` in a for-each, etc.). This is the pure-constants
  pattern from batches 1 and 4 (not the mutable-entity-field pattern from
  batch 3), so applied directly without re-asking.
- **`@JvmStatic`** on every public function in both files — standard.
- `TimeframeSelector.OnTimeframeChange` is `fun interface` — the 9 fragment
  callers already use Java lambda syntax (`months -> buildSwappableChart()`),
  which works against any single-abstract-method interface regardless of
  the `fun` keyword.

## Nullability

- `SettingsManager.getString`/`setString`: `def`/`value` parameters and the
  return type are nullable (`String?`) — the original was called internally
  with a literal `null` default (`getString(c, KEY_ALERT_RULES, null)`), and
  `SharedPreferences.getString` itself returns nullable. External callers
  always pass non-null defaults, so nothing changes for them.
- `getAlertRules(c)` returns a read-only `List<AlertRule>` (not
  `MutableList`) — checked both external callers (`ThresholdAlertEvaluator`,
  `MetricBottomSheet`), both only iterate/read, never mutate the returned
  list, so this is safe. `addAlertRule`/`removeAlertRulesFor` now call
  `.toMutableList()` internally (a copy) before mutating and re-saving —
  functionally identical end result to the original's in-place `ArrayList`
  mutation, just via an extra copy instead of casting away read-only-ness.
- `TimeframeSelector.attach`'s `selector`/`cb` parameters stay nullable
  (`LinearLayout?`/`OnTimeframeChange?`), matching the original's explicit
  `if (selector == null) return;` / `if (cb != null) ...` guards. `chartId`
  is non-null (never guarded in the original).

## Zero `!!`

Grepped both files — none needed. `removeAlertRulesFor`'s early
`if (seriesKey == null) return 0` lets the rest of the function use
`seriesKey` as smart-cast non-null.

## Left non-idiomatic / deliberately not touched

- `SettingsManager`'s Gson `TypeToken<List<AlertRule>>() {}` super-type-token
  pattern is kept exactly as the original Java anonymous-subclass idiom —
  it's the correct way to preserve generic type info at runtime for Gson,
  in either language.

## Follow-up bugs spotted, not fixed

- None.

## What Aaron needs to exercise on device

`SettingsManager` backs nearly every persisted preference in the app, so a
broad pass is the right check here rather than one narrow path:

1. **Gradle sync, then Build → Rebuild Project.**
2. **Settings sheet**: toggle night mode, colorblind mode, haptics, chart
   gridlines, smart chips, detailed AI, notifications (big moves / releases)
   — confirm each persists across an app restart.
3. **Any chart's timeframe selector** (Bonds/CPI/Employment/GDP/Housing/
   Inflation/Spreads/Stocks/Wages — 9 fragments use `TimeframeSelector`):
   switch ranges, confirm the thumb slides/snaps correctly and the choice
   is remembered per-chart after backgrounding and reopening.
4. **Threshold alerts** (if wired into the UI you're testing): add an
   alert rule, confirm it persists and fires; remove it, confirm the count
   removed is correct.
5. **Onboarding watchlist** (fresh install or clear app data): confirm the
   default watchlist order appears correctly, and a custom watchlist
   persists.
6. **News tab category toggles**: confirm gov/media/intl/research filters
   still work (routed through `NewsRepository` → `SettingsManager.getBool`).

If red: paste the exact error back and I will not start batch 6.
