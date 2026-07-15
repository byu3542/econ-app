# Implementation Task: Security, Efficiency & UI Improvements — EconomicDashboard (Android/Java)

You are working on an existing native Android app written in **Java** (not Kotlin). It is an economic-data monitoring app: 15 fragments, MPAndroidChart, Retrofit + OkHttp, Room, WorkManager, LiveData/ViewModel. Package root: `com.economic.dashboard`. Do NOT convert the project to Kotlin. Match the existing style and keep changes minimally invasive.

Work through the tasks below **in order**. After each task, build the project (`./gradlew assembleDebug`) and confirm it compiles before moving on. Do not batch unrelated changes into one commit — one logical commit per task with a clear message.

---

## Task 1 — Move hardcoded API keys out of source (HIGH PRIORITY)

**Problem:** `app/src/main/java/com/economic/dashboard/api/ApiConfig.java` hardcodes three keys directly in source:
- `BEA_API_KEY`
- `BLS_API_KEY`
- `NEWS_API_KEY`

FRED and Anthropic already read from `local.properties` via `BuildConfig` — replicate that exact pattern for these three.

**Steps:**
1. In `app/build.gradle`, inside `defaultConfig`, add `buildConfigField` lines for `BEA_API_KEY`, `BLS_API_KEY`, and `NEWS_API_KEY`, reading from `properties.getProperty(...)` the same way `ANTHROPIC_API_KEY` and `FRED_API_KEY` already do. Use a placeholder default like `"YOUR_BEA_API_KEY"` (do NOT copy the real key into build.gradle as the default — the current FRED default hardcodes the real key; use a placeholder instead).
2. In `ApiConfig.java`, change the three constants to read `BuildConfig.BEA_API_KEY`, etc.
3. Change the existing `FRED_API_KEY` default in `build.gradle` from the real key string to a placeholder (`"YOUR_FRED_API_KEY"`).
4. Add the real key values to `local.properties` (which is git-ignored). Confirm `local.properties` is in `.gitignore` — it should be by default.
5. Preserve the existing values by writing them into `local.properties`:
   - `BEA_API_KEY=13425E5B-2ECF-4378-9786-E48A81B1AEAE`
   - `BLS_API_KEY=5198938c150e4e93a417f3769a5cc077`
   - `NEWS_API_KEY=c0d06ae231f14276ab056967aeb7ac0e`
   - `FRED_API_KEY=02e1293e2b997b87df09df66c0e8fb86`

**Acceptance:** No literal API key strings remain in any `.java` file or in `build.gradle`. App builds and all data sources still load. Also add a `local.properties.example` file listing every required key name with empty values, so the setup is documented.

**Note for the user:** `BuildConfig` fields are still recoverable from a compiled APK. This step removes keys from the git repo (good hygiene) but is not a defense against decompilation. The real protection for the paid Anthropic key is Task 5.

---

## Task 2 — Enable View Binding and remove findViewById calls (HIGH VALUE / LOW RISK)

**Problem:** `build.gradle` has `viewBinding false`, and the codebase has ~189 `findViewById` calls across activities, fragments, and adapters — verbose and not null-safe.

**Steps:**
1. In `app/build.gradle`, set `viewBinding true` under `buildFeatures`.
2. Migrate `findViewById` usage to generated binding classes, **fragment by fragment** (start with `MainActivity`, then one fragment at a time). For each: inflate with the binding, replace `findViewById` lookups with `binding.viewId`, and null out the binding in `onDestroyView()` for fragments to avoid leaks.
3. Do this incrementally and build after each file. Do NOT attempt all 189 at once.

**Acceptance:** App builds and runs. No behavioral change. Prefer converting every fragment/activity, but if any file is risky, leave it on `findViewById` — mixing is fine. Report which files were migrated and which were left.

---

## Task 3 — Replace unbounded thread spawning with a shared ExecutorService (EFFICIENCY)

**Problem:** `api/EconomicRepository.java` (and ~22 sites total) uses raw `new Thread(() -> ...)` per network call. `EconomicViewModel.fetchAllData()` launches 18 concurrently, each its own thread.

**Steps:**
1. Create a small `AppExecutors` helper (e.g. `com.economic.dashboard.util.AppExecutors`) exposing a shared `ExecutorService` — `Executors.newFixedThreadPool(4)` for network I/O — plus a main-thread handler for posting results. Make it a singleton.
2. Replace every `new Thread(() -> ...).start()` in the repositories with submission to the shared network executor.
3. Do NOT change the public method signatures or the `DataCallback<T>` interface — this is an internal swap. Result delivery to LiveData already uses `postValue`, which is thread-safe, so callback semantics stay the same.

**Acceptance:** No `new Thread(` remains in `api/` repositories. App builds; data still loads on all tabs. Concurrency is now bounded by the pool size.

---

## Task 4 — Replace the fragile manual fetch counter (ROBUSTNESS)

**Problem:** `ui/EconomicViewModel.java` tracks completion of the bulk load with a hardcoded `pendingFetches = 18` and a `synchronized (lock)` block, decrementing as each fetch returns. The magic number `18` silently breaks if a data source is added or removed.

**Steps:**
1. Replace the manual counter with an `AtomicInteger` (or `CountDownLatch`) whose initial count is derived from the actual number of fetch tasks launched — not a hardcoded literal. Ideally build a list of fetch tasks and set the count to `tasks.size()`.
2. When the count reaches zero, post `isLoading = false` exactly as it does today.
3. Keep behavior identical from the UI's perspective (loading spinner shows during fetch, hides when all complete, errors still surface via `errorMsg`).

**Acceptance:** No hardcoded fetch count remains. Adding/removing a fetch call automatically adjusts the loading logic. Loading indicator behaves as before.

---

## Task 5 — Anthropic API proxy (MOST IMPORTANT SECURITY FIX — needs a server component)

**Problem:** `ui/AiAnalystBottomSheet.java` calls `https://api.anthropic.com/v1/messages` directly (around line 231), passing the Anthropic key via the `x-api-key` header. The key ships inside the APK and can be extracted by decompiling, exposing the user to unauthorized charges.

**This task has two parts. Do the Android part; scaffold the server part but flag clearly that the user must deploy it.**

**Server (scaffold only — put in a new top-level `proxy/` folder with a README):**
1. Create a minimal serverless proxy (Cloudflare Workers is a good default; a Node/Express example is also fine). It should:
   - Accept a POST from the app with the message payload.
   - Hold the Anthropic key as a server-side secret/env var (never in the repo).
   - Forward to `https://api.anthropic.com/v1/messages` with the `x-api-key` and `anthropic-version` headers, and return the response.
   - Optionally add basic rate limiting / an app-level shared secret so random callers can't abuse it.
2. Include a README with deploy steps and where to set the secret.

**Android:**
3. Change `AiAnalystBottomSheet` to call the proxy URL instead of `api.anthropic.com`, and stop sending the Anthropic key from the client. Read the proxy base URL from `BuildConfig` (add `PROXY_BASE_URL` via `local.properties`, same pattern as Task 1).
4. Remove `ANTHROPIC_API_KEY` from the client build config and from `ApiConfig` once the app no longer references it directly.

**Acceptance:** The Android app no longer contains the Anthropic key anywhere. The AI Analyst feature works against the proxy. Clearly document in the response that the user must deploy the proxy and set the secret before the AI feature will function. If deploying a server is out of scope for this session, leave the Android code behind a config flag and clearly mark it TODO rather than half-migrating.

---

## Task 6 — Enable R8/minification for release (SECURITY / SIZE)

**Problem:** `build.gradle` release build has `minifyEnabled false` — no shrinking or obfuscation.

**Steps:**
1. Set `minifyEnabled true` (and consider `shrinkResources true`) in the `release` build type.
2. Add any necessary keep rules to `proguard-rules.pro` — pay attention to Gson model classes (`models/` package: `FredResponse`, `BlsResponse`, `BeaResponse`, `NewsArticle`, etc.), Retrofit interfaces, and Room entities/DAOs, which reflection/annotation processing depends on. Add `-keep` rules so JSON parsing and Room don't break under obfuscation.
3. Build a **release** variant (`./gradlew assembleRelease`) and verify it compiles. If you can't fully test runtime, list exactly which classes you added keep rules for and why, so the user can smoke-test the release build.

**Acceptance:** Release build compiles with minification on. Keep rules documented. Note in the response that the user should manually test a release build (especially the API/JSON parsing and AI Analyst paths) since obfuscation issues surface at runtime.

---

## General constraints
- **Java only.** Do not migrate to Kotlin, coroutines, Hilt/Dagger, or RxJava — keep the existing patterns.
- Match existing code style, indentation, and naming.
- One commit per task; clear commit messages (e.g. `security: move BEA/BLS/News keys to local.properties`).
- After all tasks, produce a short summary: what changed, which files, anything left as TODO (especially the Task 5 proxy deployment), and any manual testing the user must do.
- Do not delete the existing real key values without first preserving them in `local.properties`.
- If a task turns out to be riskier or larger than expected, stop and report rather than making sweeping speculative changes.
