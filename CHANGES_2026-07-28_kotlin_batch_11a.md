# Kotlin Migration — Batch 11a

First of three commits splitting batch 11. **The leaf/support classes**: 7 files,
~710 lines — alerts, the snapshot cache, the custom View, the Application class,
and the two benchmark-grading files.

Branch: `kotlin/batch-11a`.

**11b** = `EconomicViewModel` + `MetricBottomSheet` + `SettingsBottomSheet`
(~1,209 lines). **11c** = `MainActivity` alone (663 lines), last.

> **Scope correction (lesson 14 again):** the batch table lists batch 11 as
> "`MainActivity` + remaining activities", but `MainActivity` is the *only*
> Activity, and the table never assigns `EconomicViewModel`,
> `SettingsBottomSheet`, `MetricBottomSheet`, `Benchmarks`, `BenchmarkStyle`,
> `alerts/`, `cache/DashboardDataCache`, `views/SparklineView`, or
> `EconomicDashboardApplication` to any batch. Batch 12 is a cleanup pass, not a
> conversion pass, so batch 11 has been scoped to cover **everything remaining**.

---

## Files converted

| File | Kotlin shape | Interop |
|---|---|---|
| `alerts/AlertRule.java` → `.kt` | `class` + `companion object` | `@JvmField` ×6, `const val` ×3 |
| `alerts/ThresholdAlertEvaluator.java` → `.kt` | `object` | `@JvmStatic` ×1 |
| `cache/DashboardDataCache.java` → `.kt` | `object` | `@JvmStatic` ×5 |
| `views/SparklineView.java` → `.kt` | `class : View` | `@JvmOverloads` ctor |
| `EconomicDashboardApplication.java` → `.kt` | `class : Application` | none (manifest only) |
| `ui/BenchmarkStyle.java` → `.kt` | `object` + nested `enum class Tier` | `@JvmStatic` ×5 |
| `ui/Benchmarks.java` → `.kt` | `object` + nested `class Result` | `@JvmStatic` ×17, `@JvmField` ×2 |

Plus one non-source change — see "The ProGuard rule" below.

---

## Verification

Because this batch touches a serialization schema, a custom View, and the
Application class — all of which fail at *runtime* rather than compile time — it
got a three-way parallel audit (Java-caller resolution, line-by-line fidelity
diff against the Java recovered from git history, and a runtime-hazard sweep).
One auditor went further and compiled probe translations of the converted logic
against the original Java, diffing stdout.

Results worth recording:

- **`Benchmarks` ladders verified mechanically, not by eye.** All 17 grading
  functions were reduced to a normalised token stream of every comparison
  operator, constant, badge word, and `Tier` — 166 tokens in the Java, 166 in the
  Kotlin, empty diff. No `>=`/`>` drift, no threshold drift, no vocabulary drift.
- **JVM shape confirmed via `javap`**: `AlertRule` emits raw
  `public String seriesKey/label/op; public double threshold; public boolean lastState;
  public Double lastValue` — byte-identical descriptors and declaration order to
  the Java, so Gson's emitted key order is unchanged.
- **Runtime equivalence probes** covering `mortgageAffordabilityIndex` (incl. the
  `monthlyRate == 0.0` branch), `AlertRule.describe()` with null label/op, the
  evaluator ladder, and the `SparklineView` coordinate arithmetic produced
  byte-identical output. Notably `w * i / (vals.size - 1)` stays float division —
  no integer division introduced.

---

## The ProGuard rule (your call, taken)

**`AlertRule` had no R8 keep rule.** Release builds run `minifyEnabled true`, and
`proguard-rules.pro` covered `models/`, `database/`, `workers/`, and the Retrofit
interface — but `AlertRule` lives in `alerts/`. R8 therefore renamed
`seriesKey`/`label`/`op`/`threshold` to `a`/`b`/`c`, and Gson wrote *those* names
into the user's stored alert rules.

The failure mode is silent, not a crash:
`SettingsManager.getAlertRules` returns rules without throwing, `MetricBottomSheet`
renders each as `"• null"`, and `ThresholdAlertEvaluator` skips every one — alerts
stop firing permanently.

This is **pre-existing** (`AlertRule.java` had the same exposure), but converting
the class changes its shape, which changes R8's name assignment — so it was
likely to fire on the next release regardless. Added:

```
-keep class com.economic.dashboard.alerts.AlertRule { *; }
```

**Honest caveat:** this does not rescue rules already stored by a previous
release. Those were written with obfuscated keys and the new build looks for real
ones, so existing saved rules break once on this release either way. What the
rule buys is that names are stable from here on.

**New lesson for the migration doc:** *a Gson-serialized class outside the
package covered by the keep rule has no protection; converting it to Kotlin
perturbs R8 field naming and can silently invalidate stored user data. Check
`proguard-rules.pro` coverage for any serialized class a batch touches.*

---

## Interop decisions

**1. `AlertRule` takes `@JvmField`, unlike `NewsItem` in batch 8.** Batch 8 chose
getters for a plain field-bag; this one goes the other way, for two reasons that
did not apply there: it is a **Gson storage schema** where the field names are the
on-disk format, and `MetricBottomSheet.java` reads `r.seriesKey` raw while staying
Java until 11b. Documented in the file so the divergence doesn't read as drift.

**2. `Benchmarks.mortgageAffordabilityIndex` → public + `@JvmStatic`.** It was
package-private and is called by the same-package `BenchmarksTest`. `internal`
would name-mangle the JVM signature and break that call (lesson 5).

**3. `Result.word` needs `@JvmField`; `Result.tier` does not.** The test asserts on
`Benchmarks.mortgage30(2.5).word` — a raw field read. `tier` is read only from
Kotlin and carries `@JvmField` for symmetry; the comment now says so rather than
implying the test forces both.

**4. `rateBadge`/`mortgageSpread`/`bondSpread` renamed their first parameter
`val` → `v`** — `val` is a Kotlin hard keyword, so this is forced, not stylistic.
All ~35 call sites pass positionally, so nothing breaks. Now documented in-file,
per the migration's own "forced *and* documented" rule.

**5. `ThresholdAlertEvaluator` captures the map key before lookup.** Two auditors
disagreed on whether `latest[r.seriesKey]` (indexing a `Map<String, Double>` with
a `String?`) resolves via the stdlib `Map<out K, V>.get` extension — one claimed
to have compiled it successfully, one called it a hard blocker. Since this batch
cannot be built here, it is written as `val key = r.seriesKey ?: continue`, which
sidesteps the question. Behaviour is identical: the Java's `latest.get(null)`
returned null for these maps, which only ever receive non-null constant keys.

**6. `SparklineView`'s `@JvmOverloads` rationale was corrected.** The original
comment claimed inflation would fail without it. That is wrong here — the primary
constructor's *full arity* already **is** `(Context, AttributeSet?)`, which is
what `LayoutInflater` looks up. `@JvmOverloads` exists to reproduce the Java's
second 1-arg constructor. The usual warning applies when *trailing* params carry
defaults (`defStyleAttr: Int = 0`), which is not this shape. Comment fixed so the
migration notes don't propagate a false lesson.

---

## Behaviour deltas found and accepted (all inert)

1. **`SparklineView(Context)` now chains to `View(Context, null)`** instead of
   `View(Context)` — a different AOSP init path. Unreachable: nothing constructs
   this view programmatically (both sites use `findViewById`). Noted in-file.
2. **`DashboardDataCache`'s Gson `TypeToken` now reifies as
   `List<? extends EconomicDataPoint>`** rather than `List<EconomicDataPoint>`,
   because Kotlin's `List<E>` is declaration-site covariant. Verified benign by
   Gson round-trip — `$Gson$Types` resolves the wildcard to its upper bound and
   the JSON is byte-identical. The same construct already ships at
   `SettingsManager.kt:178` against live data.
3. **`ThresholdAlertEvaluator.fire()`'s `when (r.op)` no longer NPEs on a null
   `op`** the way the Java's unguarded `switch` would. Unreachable — `fire` is
   only called from branches where `op` already matched.
4. **`SparklineView.setData` publishes the array after filling it**, where the
   Java published it first. A concurrent `onDraw` could previously observe a
   partially-filled array. Strictly safer.

---

## `!!` usage

**None.** Zero occurrences across all seven files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`EconomicDataPoint` is a Kotlin `data class` with six non-null properties and
   no defaults, deserialized by Gson.** Gson allocates via `Unsafe`, bypassing the
   constructor — so cached JSON missing `date` yields a `null` in a `String`-typed
   field, and the NPE surfaces later at `ChatAdapter.kt:279` rather than at parse
   time. Pre-existing (since batch 3), unrelated to 11a, surfaced while auditing
   cache compatibility. Worth a look.
2. **`@JvmStatic` on all of `BenchmarkStyle` and on 15 of the 17 `Benchmarks`
   functions is dead weight today** — only `mortgage30`,
   `mortgageAffordabilityIndex`, all of `DashboardDataCache`, and
   `ThresholdAlertEvaluator.evaluate` have live Java callers. Harmless, and worth
   keeping until 11b/11c convert the remaining Java. Batch 12 can strip them.
3. **Kotlin classes are final by default**, so `AlertRule`,
   `EconomicDashboardApplication`, and `SparklineView` are no longer extensible.
   Grep confirms no subclasses exist.

---

## What to exercise on device

Most of this batch is invisible plumbing; the checks that matter are the
runtime-shaped ones.

1. **Launch the app at all.** `EconomicDashboardApplication` runs seven init calls
   before any UI exists — theme, news context, notification channel, three
   schedulers, and the cache refresh. If the manifest reference or any init call
   broke, the app dies on launch with no UI.
2. **Confirm the saved theme is applied** (light/dark/system) — that is the very
   first line of `onCreate`.
3. **Overview KPI cards must show their gold sparklines.** That is
   `SparklineView` inflating from `card_kpi_badge.xml` — the XML-inflation path,
   which fails at runtime, not compile time.
4. **Ask the Analyst something that renders an inline chart** (e.g. "how has the
   10Y trended?") — the second `SparklineView` inflation site, in
   `item_chat_message.xml`.
5. **Every status badge, everywhere** — Overview tier dots + words, and the badge
   on each metric tab. That is `Benchmarks` + `BenchmarkStyle`, and a threshold
   or vocabulary slip would show as a wrong word or wrong colour. Spot-check a
   few against what they read before.
6. **Tap a KPI card → its sheet → "Add alert"**, create a rule, and confirm it
   lists correctly (not `"• null"`). Then confirm existing rules still list —
   **on a debug build this will work regardless**; the R8 issue above only
   affects release builds.
7. **Warm launch** — `DashboardDataCache` should repaint the Overview instantly
   with no shimmer.
8. **`./gradlew test`** — `BenchmarksTest` is the direct regression net for this
   batch (raw `.word` field read + the package-private
   `mortgageAffordabilityIndex` call).

Item 8 is the cheapest real signal; items 3 and 4 are the ones a clean build
cannot predict.
