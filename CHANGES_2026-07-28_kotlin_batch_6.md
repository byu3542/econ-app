# Kotlin Migration — Batch 6 (`workers/`)

Branch: `kotlin/batch-6`

## Files converted

| File | Shape |
|---|---|
| `CacheCleanupWorker` | `class : Worker(...)`, companion `scheduleWeekly` |
| `DailyBriefWorker` | `class : Worker(...)`, companion `scheduleDaily` |
| `ReleaseReminderWorker` | `class : Worker(...)`, companion `scheduleDaily` |
| `YieldRefreshWorker` | `class : Worker(...)` (no scheduler — scheduled externally by `YieldRefreshScheduler`) |
| `YieldRefreshScheduler` | `object` |

## The `Result` interop trap — learned from batch 4's `Transformations` issue

`Worker.doWork()` returns `androidx.work.ListenableWorker.Result`. In Java,
subclasses of `Worker` reference this unqualified (`Result`) because Java
gives subclasses unqualified visibility into a superclass's nested types.
**Kotlin does not do this** — and worse, `kotlin.Result` is in Kotlin's
default imports, so an unqualified `Result` in a Kotlin `Worker` subclass
would silently resolve to the *wrong* type instead of failing to compile.
Added an explicit `import androidx.work.ListenableWorker.Result` to all 4
`Worker` subclasses to force the correct resolution. Flagging this
explicitly since it's the same category of surprise as `Transformations` in
batch 4 (a Java pattern that relies on JVM/Java-specific visibility rules
Kotlin doesn't replicate) — worth watching for in any future batch that
touches a class extending an Android framework base class.

## Also proactively avoided the same category of trap in OkHttp

`DailyBriefWorker` builds a POST request body. The original Java used the
old static factories `MediaType.parse(...)` / `RequestBody.create(...)`.
Since OkHttp 4.x (already Kotlin-native, same library version confirmed
safe to use idiomatically in batch 4) documents Kotlin extension-function
replacements for exactly these two calls, and given the `Transformations`
surprise, this batch uses `"application/json".toMediaType()` and
`body.toString().toRequestBody(mediaType)` directly rather than risk the
old static-factory forms being hidden from Kotlin the same way.
Behaviorally identical — same content-type header, same request body.

## Interop annotations added and why

- **`@JvmStatic`** on `scheduleWeekly`/`scheduleDaily` (×2)/
  `scheduleDailyYieldRefresh`/`cancelYieldRefresh`/`getNextRefreshTimestamp`
  — all 4 external call sites (`EconomicDashboardApplication.java` ×3,
  `MainActivity.java` ×1) use static-style calls, verified unchanged.
- The `Worker` subclasses' constructors (`context: Context, params: WorkerParameters`)
  need **no special annotation** — Kotlin primary constructors compile to
  ordinary JVM constructors, and WorkManager's reflection-based
  instantiation (`Class.getConstructor(Context::class.java, WorkerParameters::class.java)`)
  finds them exactly as it would a Java constructor. Confirmed this isn't
  the same class of problem as `@JvmOverloads`-requiring custom `View`
  constructors, since there's no default-value elision involved here.

## Zero `!!`

`DailyBriefWorker` was the trickiest — a `var brief: String? = null` is
mutated inside a `.use { }` lambda (OkHttp response handling), then read
after the block closes. Copied to `val finalBrief = brief` right after the
`.use { }` block so the subsequent `finalBrief.isNullOrEmpty()` check
(which carries a compiler contract enabling smart-cast, same as a direct
`== null` check) lets the rest of the function use `finalBrief` as
smart-cast non-null with zero `!!`.

## Left non-idiomatic / deliberately not touched

- Nothing — all 5 were straightforward mechanical translations once the
  `Result` import issue was accounted for.

## Follow-up bugs spotted, not fixed

- None.

## What Aaron needs to exercise on device

These are all background/scheduled workers, hard to trigger on demand, so
testing is necessarily partial:

1. **Gradle sync, then Build → Rebuild Project** — this is the one that
   actually validates the `Result` import fix; if it were wrong, every
   `Worker` subclass would fail to compile with a type-mismatch on
   `doWork()`'s return type.
2. **App launch**: confirm `EconomicDashboardApplication.onCreate()` runs
   without crashing (it calls `scheduleDailyYieldRefresh`,
   `scheduleDaily` (release reminders), `scheduleWeekly` on every cold
   start) — a crash here would be immediately obvious (app won't open).
3. **Settings → enable "Daily AI morning brief"**, then, if practical,
   trigger `DailyBriefWorker` early (e.g. via `adb shell am broadcast` for
   WorkManager test, or just wait for the ~7:30 AM window) — confirm a
   notification appears and the brief lands in the AI Analyst chat history.
4. **Settings → enable "Data release reminders" / "Big rate moves"**: harder
   to trigger on demand (date/data-dependent conditions), but worth leaving
   enabled and checking back later that neither crashes anything in the
   background (check Logcat for `CacheCleanupWorker`/`DailyBriefWorker`/
   `ReleaseReminderWorker`/`YieldRefreshWorker` tags over the next few days).

If red: paste the exact error back and I will not start batch 7.
