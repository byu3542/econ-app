# Kotlin Migration — Batch 7 (Adapters)

Branch: `kotlin/batch-7`

## Files converted (6)

| File | Shape | Lines |
|---|---|---|
| `EconomicPagerAdapter` | `class : FragmentStateAdapter` | 37 |
| `MarketsPagerAdapter` | `class : FragmentStateAdapter` | 27 |
| `FedFundsHistoryAdapter` | `class : RecyclerView.Adapter<...>` | 98 |
| `PromptAdapter` | `class : RecyclerView.Adapter<...>` | 88 |
| `NewsAdapter` | `class : RecyclerView.Adapter<...>` | ~390 |
| `ChatAdapter` | `class : RecyclerView.Adapter<...>` | ~635 |

Scope note: `EconomyFragment.java`/`TreasuryFragment.java` each have a
private nested `FragmentStateAdapter` — those stay Java, they'll convert
along with their fragment file in batch 10, not here.

## Two files are dead code, converted as-is

`PromptAdapter` and `EconomicPagerAdapter` have **zero callers anywhere in
the codebase** (confirmed via full-repo search) — same situation as
`NewsArticleRepository` in batch 4. Converted faithfully, not fixed or
removed; flagging as a follow-up.

## `PromptAdapter`'s `prompts` field became `MutableList<String>`

The original stored a `final List<String> prompts` but mutated its
*contents* via `.clear()`/`.addAll()` in `updatePrompts()`. Kotlin's plain
`List` is read-only (no `.clear()`), so the constructor parameter is now
typed `MutableList<String>`. This is source-only — `MutableList` and `List`
both erase to `java.util.List` in bytecode, so any future Java caller
passing an `ArrayList` (mutable or not) still compiles fine. Moot for now
since the class is unused, but noting it since it's a real type signature
change.

## `ChatAdapter` — the significant one

This is the file explicitly named in the batch table, and it's the most
complex file converted so far (~635 lines, nested interfaces, an anonymous
`Markwon` plugin, `ClickableSpan`/`ActionMode.Callback` anonymous classes,
a background-thread sparkline data fetch). Full external API surface
checked against both callers (`MainActivity.java`, `AiAnalystBottomSheet.java`)
— **zero caller edits needed**, every method/constructor/constant matches.

Also found and preserved: `stripChartTags`/`stripStrayMarkdown` are called
directly by `app/src/test/java/.../ChatAdapterTest.java` (a JUnit test in
the same original package, relying on Java's package-private visibility).
Kotlin has no package-private equivalent — `internal` would name-mangle the
method and break the test's `ChatAdapter.stripChartTags(...)` call syntax.
Made them `public` (Kotlin's default) with `@JvmStatic` instead, matching
the same "default-visibility widening is low-risk when nothing depends on
the original tighter scope" reasoning used elsewhere in this migration —
confirmed the test file's exact call syntax is now satisfied.

### A real behavior-preservation catch in `renderText`

My first draft added `&& markwon != null` to the rendering branch condition
— but the original never checked that (`!m.isUser() && !m.isError()` only),
relying on `ensureMarkwon()` always running first. Adding the null check
would have *silently* changed behavior: if that calling invariant were ever
violated, the original crashes (NPE) but my version would have quietly
rendered raw unformatted markdown instead — masking a real bug as a cosmetic
one. Fixed to use `checkNotNull(markwon) { "..." }` instead, which preserves
the original's "fail loudly if the invariant breaks" behavior while being
more debuggable than a bare `!!` or Java's implicit NPE.

### `bindSparkline`'s regex groups

`matcher.group(1)`/`matcher.group(2)` are technically nullable per Java's
`Matcher` API, even though they're guaranteed non-null in practice (both
capturing groups are mandatory in `CHART_TAG`'s pattern, so a successful
`find()` always populates them). Rather than `!!` (which would crash on the
never-happens case), used the same graceful-fallback shape the original
already had for this exact scenario: a null `seriesId` falls through to the
existing `label == null` bailout, and a null `group(2)` falls through to
the existing broad catch block that leaves `months` at its default. Net
result: identical observable behavior, zero `!!`, and the never-happens
case degrades gracefully instead of crashing — matching the original.

## Interop annotations added and why

- **`@JvmStatic`** on `ChatAdapter.stripChartTags`/`stripStrayMarkdown`
  (companion object) — needed for the test file's call syntax, see above.
- **`@JvmField`** on `ChatAdapter.TOOL_STATUS` — read as a raw field by
  `AiAnalystBottomSheet.java` (`ChatAdapter.TOOL_STATUS`).
- `ChatAdapter.OnRetryListener` is `fun interface` — `MainActivity.java`
  passes a method reference (`this::retryLastQuery`) as an implementation.
- `ChatAdapter.ViewHolder` is `public` (forced, not a choice) — since
  `RecyclerView.Adapter<ChatAdapter.ViewHolder>` uses it as the generic
  parameter, Kotlin's override-visibility rules require it to be at least
  as visible as the (public) `onCreateViewHolder`/`onBindViewHolder`
  overrides that reference it in their signatures. This differs from
  `NewsAdapter`, where the 3 ViewHolder subclasses could stay `private`
  because that adapter's generic parameter is the base `RecyclerView.ViewHolder`
  type, not a specific subtype — the subclasses never appear in a public
  method signature there.

## Zero `!!`

Grepped all 6 files — none. The two spots that would have naturally wanted
one (`ChatAdapter`'s regex groups) were restructured to match the
original's existing graceful-degradation paths instead, per above.

## Left non-idiomatic / deliberately not touched

- `NewsAdapter`'s and `ChatAdapter`'s color literals (`0xFFB9C6E0`, etc.)
  keep explicit `.toInt()` calls in places where Kotlin's Int-context
  inference would likely have handled the hex-literal reinterpretation
  automatically. Redundant in some cases, but harmless, and safer than
  guessing given this session already hit two real interop surprises
  (`Transformations`, `BlsResponse.Results` casing) — not worth the risk of
  "cleaning up" without being able to compile-check.

## Follow-up bugs spotted, not fixed

- `PromptAdapter` and `EconomicPagerAdapter` are dead code (noted above).

## What Aaron needs to exercise on device

1. **Gradle sync, then Build → Rebuild Project.**
2. **Economy tab** (uses `EconomicPagerAdapter`... actually check: is this
   still wired up, or does the app use a different pager now? If the tab
   loads and swipes between its sub-screens normally, that's the check —
   if it's genuinely unused as the search suggests, this may be a no-op).
3. **Markets tab**: swipe between Stocks/Bonds/Yields/Spreads
   (`MarketsPagerAdapter`).
4. **Dashboard → Fed Funds history** (wherever `FedFundsHistoryAdapter` is
   shown — a history list/chart): confirm rows render with correct
   date/rate/bps-change formatting and hike/cut color coding.
5. **News tab — the big one**: hero story + section-header grouping
   (Today/Yesterday/This week/Earlier), tap a story (marks read, dims on
   return), long-press a story (Analyze menu + smart prompts), tag pill
   colors in both light and dark mode, thumbnail loading.
6. **AI Analyst chat — the other big one**: send a message, watch it
   stream in (SSE partials via `updateMessageText`), confirm markdown
   bold-gold rendering, tap a metric-name citation link (navigates tabs),
   select text in an analyst bubble and use "Ask AI" from the selection
   toolbar, long-press a bubble for copy/share, trigger an error + retry,
   ask about a metric with `[CHART:...]` tags to confirm the inline
   sparkline renders (both the live-series path and the Room-history path),
   and confirm the typing-indicator dots animate/stop correctly.
7. **Run the JUnit unit tests** (`ChatAdapterTest`) if you have a way to
   from Android Studio — this directly exercises the `stripChartTags`/
   `stripStrayMarkdown` visibility fix.

If red: paste the exact error back and I will not start batch 8.
