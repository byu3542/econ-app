# Kotlin Migration — Batch 9c

Final commit of batch 9: **`AiAnalystBottomSheet` alone, 1,625 lines** — 58% of
batch 9 by itself, and the only file in it with unit-test coverage.

Branch: `kotlin/batch-9c`. Builds on 9a + 9b (both merged).

**Batch 9 is now complete.** The `analyst/` package and the analyst sheet are
fully Kotlin.

---

## Why this file got its own commit

It concentrates every remaining risk class in one place:
`BottomSheetDialogFragment` lifecycle, ViewBinding, `registerForActivityResult`,
OkHttp SSE streaming over `okio.BufferedSource`, anonymous `Callback`s running on
background threads, `org.json` builder chains, a `synchronized(history)` block,
a nested `StreamState`, and **eight functions pinned by a same-package Java
test**. Isolating it means any test failure maps to exactly one file.

`AiAnalystBottomSheetTest.java` is **deliberately left in Java**. That is what
makes it a genuine interop regression net for the traps below — ported to Kotlin,
every one of them would silently disappear from the test surface and resurface
later as a runtime bug.

---

## The test-pinned surface (all eight, and why each is shaped this way)

`AiAnalystBottomSheetTest` calls these with unqualified static syntax
(`AiAnalystBottomSheet.foo(...)`). All were **package-private static** in Java.

| Member | Kotlin form | Why not otherwise |
|---|---|---|
| `isLookupAnnouncementOnly(String?)` | `@JvmStatic` in `companion object` | — |
| `stripDanglingAnnouncement(String?)` | `@JvmStatic` | — |
| `isTransientFailure(Exception)` | `@JvmStatic` | param stays `Exception`, not `IOException` — the test passes a `RuntimeException` |
| `friendlyError(Exception)` | `@JvmStatic` | same |
| `chooseModel(StreamState, Int)` | `@JvmStatic` | — |
| `looksToolLikely(String?)` | `@JvmStatic` | — |
| `freshnessTag(String?, Int, Long)` | `@JvmStatic` | see below |
| `StreamState` | public nested class, **not** `inner` | see below |

**Kotlin has no package-private, and `internal` is not the substitute.**
`internal` name-mangles to `chooseModel$app_debug` at the bytecode level, so the
test's `AiAnalystBottomSheet.chooseModel(st, 0)` would stop resolving — even
same-module. These are `public` + `@JvmStatic` (migration lesson 5). Dropping
`@JvmStatic` is equally fatal: Java would have to write
`AiAnalystBottomSheet.Companion.chooseModel(...)`.

**`StreamState.routeStrong` needs `@JvmField` — the single highest-risk item in
this commit.** The test does `st.routeStrong = false;` — a **raw field
assignment**. A plain Kotlin `var` emits `getRouteStrong()`/`setRouteStrong()`
and **no field at all**, and the test stops compiling. All four properties carry
`@JvmField` for consistency. `StreamState` is also non-`inner` (an `inner` class
would require an outer instance and break `new AiAnalystBottomSheet.StreamState()`)
and keeps an implicit public no-arg constructor.

**`freshnessTag`'s first parameter must be `String?`.** The test asserts
`freshnessTag(null, 75, …) == ""`. A non-null `String` parameter still *compiles*
from Java — platform types ignore nullability — but Kotlin's generated
`checkNotNullParameter` would **throw at runtime**, so the test fails with an
exception instead of returning `""`. Compile-silent, test-visible (lesson 7).

**The two `freshnessTag` overloads stay separate functions** — 3-arg public
`@JvmStatic`, 2-arg private — rather than collapsing into one with
`now: Long = System.currentTimeMillis()`. `@JvmStatic` + `@JvmOverloads` on a
companion function is an awkward combination, and it would also make the 2-arg
form public.

**Four return strings are byte-pinned, em dash (U+2014) included.** Verified
character-for-character against the test:

```
"The response timed out — please try again."
"The connection dropped while receiving the response — please try again."
"Couldn't read the response — please try again."
" [STALE — this reading may be outdated]"
```

---

## Other interop decisions

**1. `const val TAG`, not `val`.** `MainActivity.java` reads
`AiAnalystBottomSheet.TAG` at six sites. `const val` in a companion emits a real
static field; a plain `val` would need `AiAnalystBottomSheet.Companion.getTAG()`.
Same reasoning as `ModelConfig` in 9a — and `private const val MODEL_FAST =
ModelConfig.MODEL_FAST` only compiles *because* 9a made those `const`.

**2. `newInstanceForImage(imageUri: Uri, prefillQuery: String?)` —
nullable second parameter.** `MainActivity.java:317` passes a literal `null` for
a bare image share. Non-null would compile and then throw at runtime.

**3. The `LiveSeriesProvider` lambda is now a Kotlin lambda — this is the payoff
from 9b.** `setLiveSeriesProvider { seriesId -> … }` only compiles because 9b
declared the interface `fun interface`. Had it stayed a plain Kotlin interface,
9b would still have built green (Java SAM-converts to anything single-method) and
*this* commit would have failed. The 13-branch `switch` became a `when`
expression returning `List<EconomicDataPoint>?`.

**4. `ChatMessage.text` is a property, `ChatAdapter.getLastIndex()` is a
function.** Both are already-Kotlin and the distinction is invisible at a glance:
`msg.getText()` (line 1369 in the Java) had to become `msg.text`, while
`chatAdapter().getLastIndex()` must keep its parens — there is no `.lastIndex`
property to modernize into. Same for `userMsg.setImageBitmap(…)` →
`userMsg.imageBitmap = …`.

**5. OkHttp accessor shapes.** `Response.body` / `.code` / `.isSuccessful` are
Kotlin **properties**; `Call.isCanceled()` and `ResponseBody.source()` /
`.string()` are **functions**. Request building uses the modern extension form
`body.toString().toRequestBody("application/json".toMediaType())` rather than
`RequestBody.create(MediaType.parse(...), …)`, which can fail to resolve from
Kotlin (migration lesson 3) — matching what `DailyBriefWorker.kt` already does.

---

## Nullability: zero `!!` across 1,625 lines

`binding` is a genuinely nullable `var` (set in `onCreateView`, nulled in
`onDestroyView`), and it is read in ~40 places. Every one captures it into a
local `val` first — `val b = binding ?: return` — rather than asserting.
Listeners that fire later re-read `binding` at callback time instead of closing
over a stale local, preserving the original's `if (binding == null) return`
guards exactly.

`pickImageLauncher` is `lateinit var`: it is assigned in `onCreate` (the only
point the Activity Result API permits registration) and read only from a click
listener, so it is never observed unset.

The `@Volatile` fields (`historicalContext`, `appContext`, `conciseNextTurn`,
`pendingImage`, `currentCall`, `pendingAutoSendQuery`) keep their volatility —
they are written on the main thread and read from OkHttp threads. Each is
captured into a local `val` before use rather than relying on smart-cast, which
Kotlin will not apply to a mutable property (lesson 6).

---

## One forced behavior change, flagged

`handleJsonResponse` previously passed `block.optJSONObject("input")` — which can
be `null` — straight into `AnalystToolExecutor.execute(…, JSONObject)`. Java
relied on `execute()`'s own `catch` turning the resulting NPE into
`"Tool error: …"`. Kotlin will not pass a nullable into a non-null parameter, so
this is now `?: JSONObject()`.

Both paths land in the same error branch; the empty object produces a clearer
message (`"Invalid series_id: ."`) instead of an NPE string, and it matches what
the **streamed** path already substitutes when the tool-input JSON fails to
parse. Called out because it is the one place the port is not byte-identical.

---

## Deliberately left non-idiomatic

- **`String.format(Locale.US, …)` everywhere** rather than string templates —
  the format specifiers (`%+.2f%%`, `%.0f`) carry the display contract.
- **`constructEconomicContext()` keeps its dense one-line-per-metric shape**,
  including the `if/else` blocks that write `"Unavailable"`. It is 120 lines of
  near-identical repetition and reformatting it would bury the real diff.
- **`Math.max` / `Math.min` / `Math.abs` kept** over Kotlin's `maxOf`/`minOf`/
  `abs` — no behavioural difference, smaller diff.
- **The `while (tier >= 0)` loops** in `buildRecentNewsQuery` mirror the Java
  `for (int tier = 2; …; tier--)` exactly rather than becoming `downTo` ranges,
  because the loop condition also tests an accumulator (`count < 3`).

---

## Follow-up items spotted, not fixed (rule 4)

1. **`AnalystToolExecutor.setLiveSeriesProvider` is never cleared.** It is set in
   `onViewCreated` and the static reference outlives the sheet. The provider
   holds a `WeakReference` to the ViewModel so it does not leak, but clearing it
   in `onDestroyView` would be tidier. (Also noted in 9b.)

2. **`newInstanceWithContext(String, String)` and the 1-arg
   `submitExternalQuery(String)` have no callers anywhere.** Ported verbatim per
   the batch-9 dead-code decision; candidates for batch 12.

3. **`sendQuery` reassigns its parameter in Java** (`userQuery = userQuery.trim()`).
   Kotlin parameters are `val`, so this became a local `val q`. Behaviourally
   identical, but worth knowing the local name differs from the Java.

4. **The keyboard-resize `OnGlobalLayoutListener` is never removed.** It is added
   to the decor view's `ViewTreeObserver` in `onViewCreated` and not detached in
   `onDestroyView`. Pre-existing; unchanged.

---

## What to exercise on device

`./gradlew test` is the first gate — it exercises all eight pinned functions and
would catch a `@JvmField`, `internal`, or nullability mistake immediately. Then:

1. **Open the Analyst and ask a plain typed question.** Confirm text streams in
   token-by-token rather than appearing all at once — that is the SSE path.
2. **Ask a history question** ("how has the 10Y trended this year?") so the tool
   loop runs. Watch for the italic *"Checking the data…"* status during the tool
   round, then the real answer replacing it.
3. **Long-press a card or chart** → the answer should be short (60–90 words) and
   should quote the on-screen number. That is the concise-gesture path plus
   `newInstanceForGesture`.
4. **Tap the attach-image button, pick a photo, send.** This is
   `registerForActivityResult` — a registration-timing mistake throws at
   `onCreate`, not at tap time.
5. **Share an image into the app from another app** — the auto-send path
   (`pendingAutoSendQuery`).
6. **Start a long answer and hit Stop mid-stream.** Confirm it halts cleanly with
   no error bubble.
7. **Open the keyboard in the sheet** and confirm the sheet resizes and the chat
   stays scrolled to the bottom.
8. **Rotate or dismiss the sheet while a response is streaming** — should not
   crash (the `onDestroyView` retry-cancellation path).
9. **Tap an error bubble to retry** (`resendLastQuery`, called from
   `MainActivity`).
10. **Clear chat history** from the sheet's toolbar.

Items 4 and 8 are the highest-value: both fail at *runtime* in ways a clean build
cannot predict.
