# Kotlin Migration — Batch 9a

First of three commits splitting batch 9 (`analyst/` + `AiAnalystBottomSheet`,
~2,820 lines). This one is the **pure static plumbing**: 6 files, ~474 lines,
no Android UI, no lifecycle, no threading, no HTTP.

Branch: `kotlin/batch-9a`.

**9b** (`AskAnalyst`, `HistoricalContextBuilder`, `AnalystToolExecutor`, ~721
lines) and **9c** (`AiAnalystBottomSheet` alone, 1,625 lines) follow, each
built on device before the next starts.

---

## Why batch 9 was split

Not primarily size — **where the test net is**. Exactly one file in batch 9 has
unit coverage (`AiAnalystBottomSheet`, 8 pure functions via
`AiAnalystBottomSheetTest`), and it's also the largest. The other nine files
have no direct test at all, so a regression there is invisible until the app
runs. Since there's no build in the tooling sandbox, a single 2,820-line commit
that fails to compile produces an error list spanning ten files with no way to
bisect.

9a is the cheapest, highest-signal chunk: if it builds, the `object` /
`@JvmStatic` / `const val` / `@JvmField` conventions are proven for the rest.

---

## Files converted

| File | Kotlin shape | Interop annotations |
|---|---|---|
| `analyst/ModelConfig.java` → `.kt` | `object` | `const val` ×2 |
| `analyst/EconomicSeriesRegistry.java` → `.kt` | `object` + nested `enum class Source` + nested `class Entry` | `@JvmStatic` ×5, `@JvmField` ×4 |
| `analyst/ReleaseCalendar.java` → `.kt` | `object` | `@JvmStatic` ×1 |
| `analyst/NewsContextBuilder.java` → `.kt` | `object` | `@JvmStatic` ×2 |
| `analyst/DerivedMetricsBuilder.java` → `.kt` | `object` | `@JvmStatic` ×1 |
| `analyst/SmartPromptGenerator.java` → `.kt` | `object` + nested `enum class` w/ `companion object` | `@JvmStatic` ×5 |

All 6 `.java` originals deleted. **No other file was touched** — every existing
call site still compiles against the same names and shapes.

Every annotation above is **load-bearing right now**: `AnalystToolExecutor.java`
and `AiAnalystBottomSheet.java` are still Java until 9b/9c and call into all six
of these.

---

## Interop decisions worth recording

**1. `ModelConfig` uses `const val`, not `val` or `@JvmField`.**
`AiAnalystBottomSheet.java:86-87` reads `ModelConfig.MODEL_FAST` into its own
`static final String`. A plain `val` in an `object` compiles to
`ModelConfig.INSTANCE.getMODEL_FAST()` — the Java call site breaks. `const val`
emits a real static field and preserves compile-time-constant semantics.

The two model-id strings are **indirectly test-pinned**:
`AiAnalystBottomSheetTest` asserts the literals `"claude-haiku-4-5-20251001"`
and `"claude-sonnet-5"` through `chooseModel`. Copied character-for-character.

**2. `EconomicSeriesRegistry.Entry`'s constructor is public, not private.**
It's package-private in Java, called only by the 17 `new Entry(...)`
initializers inside the same class. Kotlin trap: **an enclosing class cannot
reach a nested class's `private` constructor the way Java can**, so
`private constructor` would break the `ENTRIES` initializer. `internal` is not
an option either (name-mangles — migration lesson 5). Public is correct and
zero-risk; there are no Java callers outside the class.

**3. `Entry`'s four fields need `@JvmField`, and it's mandatory this commit.**
`AnalystToolExecutor.java:75` reads `e.id` and `e.toolHint` as raw fields and
stays Java until 9b. Without `@JvmField` that's a compile break.

**4. The registry's five accessors stay functions.** `entries()`, `allIds()`,
`roomCachedIds()`, `liveToolHints()`, `chartLabels()` deliberately avoid
`getX()` naming. `ChatAdapter.kt:584` already calls `chartLabels()` with
parens — turning any into a `val` breaks that already-Kotlin call site
(the inverse of batch 8's `getCachedItems()` lesson).

**5. `.getValue()` means two different things in `DerivedMetricsBuilder`.**
On the same line you can have `vm.getTreasuryData().getValue()` (LiveData —
Java, either form compiles) and `tenY.getValue()` (`EconomicDataPoint` — a
Kotlin data class, **must** be `.value`). The port keeps the explicit
`.getX()` form for Java-owned APIs and uses property syntax only where the
owner is Kotlin. A comment in the file says so, because the distinction is
invisible at a glance.

---

## Deliberately left non-idiomatic

- **`toLowerCase()` → `lowercase(Locale.getDefault())`, not `lowercase()`.**
  Java's bare `toLowerCase()` uses the **default locale**; Kotlin's bare
  `lowercase()` uses **`Locale.ROOT`**. They are not the same function. Passing
  `Locale.getDefault()` explicitly keeps the port behaviour-identical. See
  follow-up #1 — the locale choice is a latent bug, preserved on purpose.
- **`subList(0, min(5, size))` kept** instead of `take(5)`. Behaviourally
  equivalent for the one live caller, but `subList` returns a live view and
  `take` returns a copy; not worth changing in a syntax-only pass.
- **`entries()` still returns the internal array by reference** — callers can
  mutate it. Preserved verbatim; not "fixed" to a copy or a `List`, because
  `AnalystToolExecutor.java:74` iterates it as `Entry[]`.
- **`LinkedHashMap` kept explicitly** in `liveToolHints()`/`chartLabels()` —
  the deterministic declaration order is load-bearing for the tool schema and
  chart labels.
- **Dead code ported verbatim** (your call) rather than deleted — see
  follow-up #2.

---

## `!!` usage

**None.** Zero occurrences across all six files.

---

## Follow-up items spotted, not fixed (rule 4)

1. **Turkish-locale bug in `SmartPromptGenerator`, now explicit.** Keyword
   matching lowercases with the *default* locale, then compares against English
   literals (`"fed"`, `"cpi"`, …). On a Turkish-locale device `"I"` lowercases
   to `"ı"`, so headlines containing capital I fail to match their bucket. The
   Java had the same bug; the port preserves it and makes it visible at the call
   site. One-word fix (`Locale.US`) whenever you want it.

2. **Dead API surface — zero call sites repo-wide.** Ported as-is per your
   decision, flagged for batch 12:
   - `SmartPromptGenerator.generatePromptsForArticle(NewsArticle)`
   - `SmartPromptGenerator.generateComparativePrompts(List<NewsArticle>)`
   - `SmartPromptGenerator.generateOutlookPrompts(NewsArticle)`
   - the entire `SmartPromptGenerator.EconomicCategory` enum and its
     `detectFromArticle` — the only consumer of the `models.NewsArticle` import
   Only `generatePromptsForHeadline` is live (`NewsAdapter.kt:112`).

3. **`generateOutlookPrompts(article)` ignores its parameter entirely** — returns
   the same 5 canned strings regardless of input. Pre-existing; dead anyway.

4. **`ReleaseCalendar`'s FOMC/CPI tables are hardcoded for 2026 only.** Past
   2026 both `nextFomc` and `nextCpi` return null and the block silently
   degrades to just the GDP line. Pre-existing, unchanged — but it expires,
   so worth a calendar reminder rather than a code fix.

---

## What to exercise on device

This commit has no UI of its own; everything lands through the analyst and news
surfaces. A clean rebuild is the main gate — the `@JvmStatic`/`const val`/
`@JvmField` decisions above are all compile-time.

1. **Open the AI Analyst and ask anything.** This exercises
   `NewsContextBuilder`, `DerivedMetricsBuilder`, `ReleaseCalendar`, and
   `EconomicSeriesRegistry` together — all four feed `buildSystemPrompt()`.
   The answer should be normal; nothing user-visible should change.
2. **Ask a series question** ("what's the 10Y doing?") so the tool loop runs —
   confirms `EconomicSeriesRegistry` still feeds
   `AnalystToolExecutor.SERIES_IDS`/`LIVE_SERIES` correctly. A wrong registry
   port would surface as "Invalid series_id".
3. **Check an inline chart renders in a chat reply** — `ChatAdapter`'s
   `SERIES_LABELS` comes from `EconomicSeriesRegistry.chartLabels()` at class
   load. A static-init failure here would break the chat list, not just labels.
4. **Long-press a news headline** → the suggestion menu should still show
   prompts (`SmartPromptGenerator.generatePromptsForHeadline`).
5. **Model routing** — a plain typed question should still use the fast model
   and a long-press/gesture turn the strong one (`ModelConfig`). Hard to observe
   directly; the unit test covers it, so a green `./gradlew test` is the real
   check here.

Item 3 is the highest-value single check — it's the one path that fails at
*class-load* rather than at call time.
