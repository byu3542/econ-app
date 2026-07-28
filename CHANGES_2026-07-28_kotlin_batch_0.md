# Kotlin Migration — Batch 0 (Gradle + KSP setup)

Branch: `kotlin/batch-0`

## Correction after first build attempt

Aaron's first sync failed:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
support since AGP 9.0.
```

This project is on **AGP 9.1.0**, which has *built-in* Kotlin support —
applying the separate `org.jetbrains.kotlin.android` plugin on top of it is
no longer just redundant, it's a hard error. I hadn't accounted for that
(assumed the classic two-plugin setup this repo would have had on an older
AGP). Fixed by:

- Removing `id 'org.jetbrains.kotlin.android' version '2.2.21' apply false`
  from [build.gradle](build.gradle) entirely — no Kotlin plugin/version is
  declared at all now; AGP 9.1.0 supplies Kotlin itself.
- Removing `id 'org.jetbrains.kotlin.android'` from
  [app/build.gradle](app/build.gradle)'s `plugins {}` block.
- Removing the `kotlinOptions { jvmTarget = '1.8' }` block from `app/build.gradle`
  — that DSL belongs to the old plugin and no longer exists once it's not
  applied. Per Google's built-in-Kotlin migration guide, `jvmTarget` defaults
  to `compileOptions.targetCompatibility`, which is already `VERSION_1_8`, so
  nothing was lost.

**Residual uncertainty — please watch for this on next sync:** KSP plugin
versions are normally paired to an exact Kotlin compiler version
(`<kotlin>-<ksp>`, e.g. the `2.2.21-2.0.5` I used originally). With built-in
Kotlin, AGP bundles its own internal Kotlin version that isn't user-selectable,
and I could not find published documentation of exactly which Kotlin version
AGP 9.1.0's built-in support uses. Google's own built-in-Kotlin + KSP example
uses a plain (non-paired) version string, `com.google.devtools.ksp:2.3.4`, so
I used that. **If sync now fails on a KSP/Kotlin version mismatch, paste the
exact error** — it will name the Kotlin version AGP 9.1.0 actually bundles,
and I'll pin KSP to the matching release immediately.

## Files converted

None — this batch touches build config only, plus one throwaway file.

## Files changed

- [build.gradle](build.gradle) — bumped the already-declared Kotlin plugin
  version from `2.2.10` to `2.2.21`, and added the KSP plugin
  (`com.google.devtools.ksp` `2.2.21-2.0.5`).

  **Deviation from what was already on disk:** `2.2.10` had no matching KSP
  release at all (checked the KSP GitHub tags: nearest are `2.2.20-2.0.4` and
  `2.2.21-2.0.5`; nothing for `2.2.10`). Since Phase 0's whole job is getting
  Room onto KSP, I bumped the patch version to `2.2.21` to land on a real KSP
  release rather than leave a config that can't resolve. This is a version
  bump, not a behavior change, and it's isolated to this batch — flagging it
  because Aaron didn't ask for it and it wasn't in the plan as written.

- [app/build.gradle](app/build.gradle):
  - Added `org.jetbrains.kotlin.android` and `com.google.devtools.ksp` plugin
    applications.
  - Added `kotlinOptions { jvmTarget = '1.8' }`, matching the existing
    `sourceCompatibility` / `targetCompatibility` (`VERSION_1_8`) so javac and
    kotlinc agree on bytecode target.
  - Added `implementation 'androidx.core:core-ktx:1.13.1'`. Picked 1.13.1
    deliberately over newer releases (1.15.0+) because those raise the
    required `compileSdk` past the project's current `compileSdk 34`, and a
    compileSdk bump is out of scope for this batch.
  - Changed Room from `annotationProcessor 'androidx.room:room-compiler:2.6.1'`
    to `ksp 'androidx.room:room-compiler:2.6.1'`. This was the only
    annotation processor in the project (grepped `app/build.gradle` for
    `annotationProcessor`/`kapt` — no other hits), so no other processor
    needs migrating.

## Throwaway interop check (per Phase 0 acceptance criteria)

- Added [app/src/main/java/com/economic/dashboard/Hello.kt](app/src/main/java/com/economic/dashboard/Hello.kt) —
  an `object` with `@JvmStatic fun greet()`.
- Added one line in [MainActivity.java](app/src/main/java/com/economic/dashboard/ui/MainActivity.java)'s
  `onCreate`, right after `super.onCreate(...)`:
  `Log.d("KotlinMigration", Hello.greet())`.
- Both are marked with `// Phase 0 throwaway` comments at the call site and
  in the file itself.

**These must be deleted once the build is confirmed** — do not carry them
into batch 1. That's a two-line removal (the Kotlin file and the one Log
line in MainActivity), small enough to do yourself or hand back to me in a
follow-up prompt.

## Interop annotations used and why

- `@JvmStatic` on `Hello.greet()` — otherwise Java would have to write
  `Hello.INSTANCE.greet()`. This is the same annotation every later batch
  will need on `companion object` members / `object` functions that Java
  still calls (see Interop rules in KOTLIN_MIGRATION.md).

## Left non-idiomatic / deliberately not touched

- Nothing else in the codebase was touched. No `.java` files were converted,
  none were deleted, no batch-1+ scope was started.

## Follow-up bugs spotted, not fixed

- None spotted — batch scope was config-only.

## What Aaron needs to exercise on device

1. **Gradle sync**, then **Build → Rebuild Project** (clean rebuild, not
   just Run — per your own checklist, this is what catches KSP/annotation
   processing failures that incremental builds hide).
2. Confirm the sync resolves `com.google.devtools.ksp:2.2.21-2.0.5` and the
   Kotlin `2.2.21` plugin without version-conflict errors — these are new
   coordinates I could not resolve/build against locally (no Android SDK in
   the sandbox).
3. Launch the app, open **Overview** (MainActivity's onCreate runs
   immediately), then check Logcat for tag `KotlinMigration` — you should
   see `Kotlin interop OK`. That confirms Java is calling into compiled
   Kotlin correctly, including inflation/runtime paths, not just javac
   compiling against it.
4. Confirm Room still generates its DAO implementations under KSP — the app
   uses `YieldDatabase` at startup (referenced in MainActivity), so if KSP
   silently failed to generate Room code, you'd expect a crash or missing
   generated class near app launch, not a compile error.
5. Once all the above is green: delete `Hello.kt` and the one `Log.d` line
   in `MainActivity.java`, then tell me to proceed to batch 1 (`utils/`
   leaves).

If red: paste the exact Gradle/Logcat error back and I will not start
batch 1.
