# Kotlin migration — batch 12b (correctness fixes)

Date: 2026-07-30
Branch: `kotlin/batch-12b`
Net: **+10 / −11 lines** across 2 files. Three lines of actual behavior change;
the rest is replacing stale "deliberately not fixed" comments with the reasoning
behind the fix.

Both of these were carried forward under standing rule 4 ("do not refactor
behavior — if you spot a bug, write it in the CHANGES file"). Rule 4 no longer
applies in batch 12; this is the batch those notes were deferring into.

---

## Fix 1 — `Double.MIN_VALUE` as a running-max seed

`analyst/HistoricalContextBuilder.kt`, two sites: `appendYieldLine` (was line
116) and `appendUnemploymentBlock` (was line 142). Both seeded a running maximum
with `Double.MIN_VALUE`, which is the smallest **positive** double (~4.9e-324),
not the most negative one. For a series where every value is negative, the seed
is larger than every element, so `max` never updates and the block reports
`0.00` as the maximum.

Changed to `-Double.MAX_VALUE` at both sites. The paired `min = Double.MAX_VALUE`
seeds were already correct and are untouched.

**Verified, not assumed.** The JVM test suite covers neither function (both need
an `EconomicHistoryDao`), so the fix was proven against the raw arithmetic:

```
all-negative series [-0.25, -0.1, -0.4]
  seed Double.MIN_VALUE  -> max = 0.00    WRONG (true max is -0.10)
  seed -Double.MAX_VALUE -> max = -0.10   ok
  all-positive unchanged: 5.90 == 5.90 -> ok
```

The third line is the one that matters for risk: for any series with at least one
non-negative value — which is every series the app fetches today — the two seeds
produce bit-identical results. This fix cannot change current behavior. It only
removes a wrong answer that would appear if a series ever went fully negative.

Which is not hypothetical for `appendYieldLine`: it formats Treasury yields and
rates, and negative nominal yields have occurred in developed markets.
`appendUnemploymentBlock` genuinely cannot go negative — fixed anyway, because
leaving one of two identical bugs in place invites someone to "fix" it back.

## Fix 2 — Turkish-locale keyword matching

`analyst/SmartPromptGenerator.kt` line 15 (`generatePromptsForHeadline`).
Headlines were lowercased with `Locale.getDefault()` and then compared against
English ASCII literals (`"fed"`, `"cpi"`, `"inflation"`, …). On a Turkish-locale
device, capital `I` lowercases to dotless `ı`, so every keyword containing an `I`
silently fails to match and the headline falls through to the generic
three-prompt fallback.

Changed to `Locale.US`. Verified:

```
headline "INFLATION Rises Again" on a Turkish-locale device
  getDefault() -> "ınflatıon rises again"  contains("inflation") = false
  Locale.US    -> "inflation rises again"  contains("inflation") = true
```

### Why `Locale.US` and not a bare `.lowercase()`

Kotlin's no-arg `lowercase()` is locale-independent and would fix the bug just as
well — it was my first instinct, and it would have let the `java.util.Locale`
import go too, since line 15 is the only remaining `Locale` use in the file.

A repo sweep decided it the other way. **Every other case-conversion site in the
app already passes `Locale.US`** — 17 of them across `AnalystToolExecutor`,
`HistoricalDataRepository`, `NewsAdapter`, `NewsRepository`, `RssParser`,
`AiAnalystBottomSheet`, `ChatAdapter`, and `MainActivity`.
`SmartPromptGenerator` was the sole outlier passing `getDefault()`. Standing rule
6 says reuse existing conventions, so `Locale.US` it is — matching sixteen
siblings beats being marginally more idiomatic in one file.

---

## Verification

```
./gradlew check   → BUILD SUCCESSFUL (lint + tests + checkBadges)
43 tests, 0 failures
```

Unchanged from 12a: 11 + 21 + 2 + 9 = 43. No test covers either fix — see the
follow-up below.

A repo-wide sweep confirms there are no remaining siblings of either bug: zero
other `MIN_VALUE` uses anywhere in `app/src`, and zero remaining
`getDefault()`-based case conversions.

---

## What to exercise on device

Small batch, and both changes are on paths that are hard to trigger deliberately.
Neither should produce any visible difference on a US-locale device with today's
data — that is the expected result, not a failed test.

1. **Gradle sync, then Build → Rebuild Project.**
2. **AI Analyst, ask a historical question** — e.g. "how has the 10-year yield
   trended over the past two years?" or "what's the unemployment range?". This is
   the `HistoricalContextBuilder` path. Confirm the Range/avg/Latest line still
   reads sensibly and the numbers match what the charts show. You are checking
   for *no change*.
3. **News tab, long-press a headline for AI prompts** — the
   `generatePromptsForHeadline` path. Confirm topic-specific prompts still appear
   (Fed headline → Fed prompts, CPI headline → inflation prompts), not the
   generic three-prompt fallback.
4. **Optional, only if you want to see fix 2 actually do something:** set the
   device language to Turkish, then repeat step 3 on a headline with a capital I
   ("INFLATION…", "Fed Signals…"). Before this batch those would have fallen
   through to the generic prompts; now they should bucket correctly. Set the
   language back afterwards.

If red: paste the exact error back and I will not start 12c.

---

## Follow-up items spotted, not fixed

1. **Neither fix has a regression test.** `generatePromptsForHeadline` is a pure
   static with no Android dependencies and is directly testable from the existing
   Java test suite — a `SmartPromptGeneratorTest` asserting Turkish-locale
   bucketing would lock fix 2 in permanently. `HistoricalContextBuilder`'s two
   functions need an `EconomicHistoryDao` and are harder. Out of the agreed 12b
   scope (which was the two fixes only); worth doing if you want the net widened.
2. **`appendUnemploymentBlock`'s loop is now a candidate for `rows.minOf`/
   `maxOf`** — it computes only min and max, and `rows` is guaranteed non-empty
   by the guard above it, so the seeds could disappear entirely. `appendYieldLine`
   also accumulates `sum`, so it would need a different shape. Deliberately not
   done: 12b's scope is correctness, and rewriting a loop that was just proven
   correct adds risk for style. Natural fit for 12c if you want it.
3. **`appendGdpBlock`'s separator is still off by one** (carried from 9b): it
   appends `" | "` when `i < rows.size - 1`, but the loop starts at
   `max(0, rows.size - 8)`, so with more than 8 cached quarters the last rendered
   quarter gets a trailing separator. Not in 12b's agreed scope. This one is
   user-visible in the analyst's context block, unlike the two fixed above —
   worth folding into 12c.

---

## Next

**12c** — the retry-chip helper: 13 identical lines × 10 chart fragments,
differing only in the `retryKeys` array. A `wireRetryChip(view, viewModel, vararg
keys)` helper collapses ~130 lines to ~10. That is the last item in the agreed
batch 12 scope, and the only one that is a genuine refactor rather than a
deletion or a one-liner — so it is the one where the on-device pass actually
matters.

Per standing rule 2: do not start 12c until this is confirmed built and run on
device.
