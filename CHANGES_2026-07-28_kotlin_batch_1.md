# Kotlin Migration — Batch 1 (`utils/` leaves)

Branch: `kotlin/batch-1`

## Files converted (9, all `.java` deleted, matching `.kt` added)

| File | What it does |
|---|---|
| `DeltaFormatter` | KPI delta glyph/sign/color formatting (colorblind-safe) |
| `HapticUtil` | Haptic feedback wrapper, gated by a setting |
| `MotionUtil` | Accessibility gate for animations |
| `NumberFormatUtil` | Central number/timestamp formatting |
| `ValueAnimatorUtil` | Count-up/down animation for KPI TextViews |
| `EdgeToEdgeInsets` | Edge-to-edge inset registry for header/bottom-nav |
| `ImageUtils` | Downscale/base64-encode images for the vision API |
| `NotificationHelper` | Notification channel + posting economic alerts |
| `AppExecutors` | App-wide executor pools |

Batch table correction (flagged in Phase 1, no action needed): the table only
named `MotionUtil`/`HapticUtil`/`DeltaFormatter`/"formatters", but `utils/`
has 12 files. `SettingsManager` and `TimeframeSelector` are correctly
deferred to batch 5, `ChartHelper` to batch 8 — left as `.java`, untouched
this batch.

## Scope change from the original plan: `AppExecutors`

Per your instruction, `AppExecutors` became a bare Kotlin `object` (true
singleton, no `getInstance()`) rather than preserving the old
`getInstance()` shape. This **touched 9 caller files**, purely mechanically —
every call site was one of exactly two shapes, replaced with a plain
find/replace, nothing else in those files touched:

- `AppExecutors.getInstance().networkIO()` → `AppExecutors.networkIO()`
- `AppExecutors.getInstance().diskIO()` → `AppExecutors.diskIO()`

Caller files touched (import + call sites only):
`api/EconomicRepository.java`, `api/HistoricalDataRepository.java`,
`api/NewsApiClient.java`, `api/NewsArticleRepository.java`,
`api/TreasuryYieldRepository.java`, `cache/CacheManager.java`,
`ui/AiAnalystBottomSheet.java`, `ui/ChatAdapter.java`, `ui/MainActivity.java`.
Verified (grep) zero remaining `getInstance()` references anywhere in the repo.

## Interop annotations added and why

- **`@JvmStatic`** on every public function in all 9 `object`s — without it,
  Java callers would need `ClassName.INSTANCE.method()` instead of the
  existing `ClassName.method()` call sites (which all ~60 call sites across
  the app still use unchanged).
- **`@JvmField`** on:
  - `ImageUtils.EncodedImage.base64` / `.mediaType` / `.preview` — **required**,
    not optional. `AiAnalystBottomSheet.java` reads these as raw fields
    (`img.preview`, `img.mediaType`, `img.base64`), and a Kotlin `val` without
    `@JvmField` only generates a getter (`getPreview()`), which would have
    broken that caller at compile time.
  - `MotionUtil.KEY_REDUCE_MOTION`, `NotificationHelper.CHANNEL_ID`,
    `NumberFormatUtil.EM_DASH` — not currently read externally as fields, but
    added per the migration plan's own interop rule (public constants Java
    might read as fields) since it costs nothing.

## Nullability judgment calls

- `HapticUtil`'s four public methods (`selection`/`confirm`/`reject`/`scrubTick`)
  keep `View?` (nullable) parameters, matching the original's explicit
  `v != null` defensive check — this is a deliberate existing guard, not
  converter cruft, so I preserved it rather than tightening to non-null.
- `EdgeToEdgeInsets.applyTopInset` / `applyBottomInset` / `applyBothInsets`
  same reasoning — original had `if (view == null) return;` inside `register()`.
- `MotionUtil.animationsEnabled(c: Context?)` — original explicitly handled
  `c == null` by returning `true`; kept nullable to preserve that.
- Everywhere else (e.g. `DeltaFormatter.color`'s `Context`, `ImageUtils.encode`'s
  `Context`/`Uri`), the original had no null handling and callers never pass
  null, so those became non-nullable — reflects actual reality per the
  migration plan's nullability rule.

## Zero `!!`

Grepped all 9 new files — none. One spot needed a real restructure to avoid
one: in `ValueAnimatorUtil.animateOrSet`, the original Java's single
`||`-chained bail condition (`!mNew.find() || mOld == null || !mOld.find() || ...`)
doesn't let Kotlin smart-cast `oldText` afterward if translated literally
(smart-casting doesn't flow from `mOld`'s nullity back to `oldText`). Split
it into two sequential guard clauses — `if (oldText == null || !mNew.find())`
then `if (!mOld.find() || ...)` — which is behaviorally identical (same four
independent conditions, all side-effect-free, so reordering doesn't change
which branch fires) and lets the compiler prove `oldText` non-null for the
rest of the function without `!!`.

## Left non-idiomatic / deliberately not touched

- `ImageUtils.EncodedImage`'s constructor is now `public` by default (Kotlin's
  default visibility) where the Java version was package-private. Purely a
  visibility widening from Kotlin's different defaults — no behavior change,
  not worth a `internal` annotation for a class only ever constructed inside
  `ImageUtils.encode()` itself.

## Follow-up bugs spotted, not fixed

- None. All 9 files were mechanical translations of existing logic; no bugs
  or bad patterns noticed in the process.

## What Aaron needs to exercise on device

Gradle sync, then **Build → Rebuild Project**. On device, exercise anything
touching these 9 files — this is most of the app's daily-use surface, so a
full pass through the main tabs covers it:

1. **Dashboard tab**: KPI cards showing deltas (`DeltaFormatter`), the
   count-up animation when values refresh (`ValueAnimatorUtil`), pull-to-refresh
   haptic (`HapticUtil.confirm`), edge-to-edge scrolling (`EdgeToEdgeInsets`).
2. **Any fragment tab** (Bonds, CPI, Employment, GDP, Housing, Inflation,
   Spreads, Stocks, Wages, Yields, Markets, News): number formatting
   (`NumberFormatUtil.percent`/`number`/`indexPoints`), tab-strip edge insets.
3. **Bottom-nav tab switching**: haptic tick (`HapticUtil.selection`).
4. **AI Analyst**: attach an image and send a query — exercises
   `ImageUtils.encode` end-to-end, including the `@JvmField` fix (if that
   annotation were wrong, this would be a compile failure, not a runtime one,
   but it's the one path that proves the fields are real fields and not just
   getters).
5. **Settings → Reduce motion toggle**: confirm animations still respect it
   (`MotionUtil`).
6. **Background work**: `DailyBriefWorker`/`ReleaseReminderWorker`/
   `YieldRefreshWorker`/`ThresholdAlertEvaluator` all post through
   `NotificationHelper` — trigger a threshold alert or wait for a scheduled
   worker if practical, otherwise this is lower-risk (pure Java→Kotlin static
   calls, no interop annotation subtlety).
7. **Any network/cache activity** (initial launch, pull-to-refresh, News tab):
   exercises `AppExecutors.networkIO()`/`diskIO()` through all 9 touched
   caller files — this is the highest-risk item in this batch since it's the
   one place I changed caller files, not just added new ones.

If red: paste the exact error back and I will not start batch 2.
