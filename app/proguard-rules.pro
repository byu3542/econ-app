# ── Economic Dashboard R8/ProGuard keep rules ────────────────────────────────
# Added when minifyEnabled was turned on. Rules cover everything that relies on
# reflection at runtime (Gson JSON parsing, Retrofit, Room) so release builds
# behave identically to debug builds.

# Keep generic type signatures + annotations (required by Gson TypeToken,
# Retrofit method parsing, and Room).
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ── Gson models ──────────────────────────────────────────────────────────────
# Gson maps JSON fields to these classes by (reflected) field name. Obfuscating
# or stripping them silently breaks FRED/BLS/BEA/News parsing.
# Covers: FredResponse, BlsResponse, BeaResponse, NewsArticle, TreasuryYield,
# EconomicHistoryEntry, EconomicDataPoint, ChatMessage, etc.
-keep class com.economic.dashboard.models.** { *; }

# ── Gson-serialized classes OUTSIDE models/ ──────────────────────────────────
# AlertRule is persisted to SharedPreferences as JSON (SettingsManager
# get/saveAlertRules) but lives in alerts/, so the models rule above never
# covered it. Without this, R8 renames seriesKey/label/op/threshold and Gson
# writes the obfuscated names into the user's stored rules — which then fail to
# deserialize after any release whose R8 name assignment differs. The failure is
# silent: rules load as all-null, render as "• null", and stop firing.
# Found during the batch-11a Kotlin conversion (pre-existing, not introduced by
# it — converting the class just made a name shift likely on the next release).
-keep class com.economic.dashboard.alerts.AlertRule { *; }

# ── Retrofit ─────────────────────────────────────────────────────────────────
-keep interface com.economic.dashboard.api.EconomicApiService { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ── Gson internals ───────────────────────────────────────────────────────────
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**

# ── Room ─────────────────────────────────────────────────────────────────────
# Room's generated implementations are referenced by name; entities are kept
# above via the models rule. DAOs/Database classes:
-keep class com.economic.dashboard.database.** { *; }

# ── WorkManager workers (instantiated reflectively by class name) ────────────
-keep class com.economic.dashboard.workers.** { *; }

# ── MPAndroidChart ───────────────────────────────────────────────────────────
-dontwarn com.github.mikephil.charting.**
