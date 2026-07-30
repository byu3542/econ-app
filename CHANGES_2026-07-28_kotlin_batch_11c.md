# Kotlin Migration — Batch 11c

**`MainActivity` alone**, 663 lines — the last Java file in `app/src/main`.

Branch: `kotlin/batch-11c`. Builds on 11a + 11b (both merged).

---

## `app/src/main` is now 100% Kotlin

**92 Kotlin files, zero Java.** Batches 0–11 are complete; the only conversion
work remaining is batch 12, which is an idiomatic-cleanup pass rather than a
port.

The four files under `app/src/test` stay Java **by design** — they are the
interop regression net that has caught the `@JvmField` / `@JvmStatic` /
package-private traps throughout, and porting them would delete that signal.
Verified they still bind to the same surface: `BenchmarksTest` reads
`Benchmarks.mortgage30(2.5).word` raw and calls the once-package-private
`mortgageAffordabilityIndex`; `AiAnalystBottomSheetTest` constructs
`new AiAnalystBottomSheet.StreamState()` and calls all eight pinned statics.

---

## Interop decisions

**1. The host accessors stay FUNCTIONS.** `getChatAdapter()`,
`getConversationHistory()`, `getHttpClient()`, `getLastUserQuery()`,
`getViewModel()`, `getCurrentScreenLabel()` — 16 Kotlin call sites reach them as
`host().getViewModel()` with parens, and Kotlin's property sugar does not apply
to a Kotlin-declared `getX()`. Third batch running where the call sites, not the
idiom, decide the accessor shape (batch 8 went the other way for the same
reason).

**2. The package-private fields become `private`.** `chatAdapter`,
`conversationHistory`, `lastUserQuery`, and `httpClient` had no access modifier
in the Java. Grep confirms nothing outside the class reads them — including the
same-package tests, which never touch `MainActivity` — so lesson 5 does not
apply and `private` is correct. Everything goes through the accessors.

**3. `ChatAdapter`'s two listeners take different shapes, and that asymmetry is
load-bearing.** `OnRetryListener` is a `fun interface`, so `this::retryLastQuery`
becomes the lambda `{ retryLastQuery() }`. `OnAnalystActionListener` declares
**three** methods, so it is not a SAM type and stays an object expression —
exactly as the Java had it. Checked both before converting.

**4. `binding` is `lateinit var` with `::binding.isInitialized` guards.** The
Java's `if (binding == null) return` checks in `refreshNewsBadge`,
`clearNewsBadge`, `refreshAnalystBadge`, and `clearAnalystBadge` guard against
the News-fetch listener firing before/after the Activity's binding exists. Same
`isInitialized` translation used in batch 10b.

**5. Explicit setters on `BadgeDrawable`.** `setBackgroundColor`,
`setBadgeTextColor`, `setMaxCharacterCount`, `setNumber`, `setVisible` are all
called explicitly rather than via property syntax. `BadgeDrawable` is Material
(third-party, unbuildable here) and `Drawable` additionally declares a two-arg
`setVisible(boolean, boolean)`, so property syntax would be needlessly ambiguous
(lesson 12).

**6. Manifest reference preserved.** `android:name=".ui.MainActivity"` resolves
against `com.economic.dashboard` — package and class name unchanged.

---

## The one place a subtle bug could have hidden

`rebuildConversationHistory` used a Java `for` loop with a **conditional extra
increment**:

```java
for (int i = 0; i < restored.size() - 1; i++) {
    ChatMessage q = restored.get(i), a = restored.get(i + 1);
    if (q.isUser() && !a.isUser()) {
        …
        i++;          // advance past the pair
    }
}                     // plus the loop's own i++
```

So a matched user→assistant pair advances `i` by **2**, and a non-match by
**1**. Kotlin's `for (i in …)` cannot mutate its induction variable, so this is
now an explicit `while` loop with the same two increment sites:

```kotlin
var i = 0
while (i < restored.size - 1) {
    …
    if (q.isUser && !a.isUser) { …; i++ }
    i++
}
```

Traced both forms against a mixed transcript to confirm identical pairing.
Getting this wrong would silently corrupt the Claude-facing history (the API
requires strictly alternating roles), and it would only show up as a 400 on a
restored conversation — worth the extra care.

Also note `ChatMessage.isUser` / `.text` / `.isTyping` / `.isError` are Kotlin
**properties** (since batch 3), so `q.isUser()` → `q.isUser` throughout.

---

## `!!` usage

**None.** Zero occurrences.

---

## Follow-up items spotted, not fixed (rule 4)

1. **`updateHeader()` is an empty method** called from five places. Its comment
   explains why (the compact title is set on tab switch instead), but it is dead
   weight. Batch 12.
2. **`describeMetricSnapshot`'s `when` has ten branches that each repeat the
   same shape** — fetch LiveData, filter, format. ~90 lines that a small table
   of (key → series name → format string) would collapse. Deliberately left
   alone: that is a refactor, not a port.
3. **`navigateToTab` and the `setOnItemSelectedListener` both map tab keys to
   `R.id.*`**, in two separate if-chains that must agree. A shared mapping would
   remove the drift risk.
4. **`onCreateOptionsMenu` / `onOptionsItemSelected` are still wired** even
   though the action bar is hidden (`supportActionBar?.hide()`), so the refresh
   and clear-cache menu items are unreachable from the UI. Their functionality
   is duplicated in the Settings sheet. Possibly dead since the header redesign.

---

## What to exercise on device

This is the Activity, so essentially the whole app exercises it. A clean
rebuild plus:

1. **Launch, and switch between all four tabs** (Overview / Markets / Economy /
   News). The gold active-indicator must slide to the right slot, the header
   title must change, and each tab must load its fragment.
2. **`./gradlew test`** — all four Java test files must still compile and pass.
   That is the interop net for the whole migration.
3. **Open the AI Analyst via the centre FAB**, ask something, and confirm the
   reply renders. Then **tap a metric citation** in a reply — the sheet should
   close and jump to that tab (`navigateToTab`).
4. **Select a metric name inside an analyst reply → "Ask AI"** — this is
   `describeMetricSnapshot`, ten branches of formatting, and the answer should
   quote a current reading.
5. **Kill and relaunch with chat history present.** The conversation must
   restore, and a follow-up question must still work — that is
   `rebuildConversationHistory`, the loop called out above. A pairing bug shows
   up here as an API error on the next message, not at restore time.
6. **Clear chat history** from Settings, confirm the welcome message returns.
7. **Share text and an image into the app** from another app (share sheet →
   Econ Monitor). Both route into the Analyst.
8. **News unread badge**: leave News alone until a fetch lands, confirm the gold
   count appears on the tab, then open News and confirm it clears.
9. **Notification permission**: toggle a notification switch in Settings on
   Android 13+, deny the prompt, and confirm the switches revert and the
   "blocked" banner appears.
10. **Rotate the device** on each tab — no crash, no tab reset (the
    `freshLaunch` gate).
11. **Overview hero card → Fed Funds history sheet** — the one call into
    `MetricBottomSheet.show` that uses the returned view.

Items 2 and 5 are the highest-value: the first is the migration's whole
regression net, the second is the only non-transliteration change in the file.
