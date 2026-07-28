# Kotlin Migration — Batch 3 (`models/` — data models / Room entities)

Branch: `kotlin/batch-3`

## Files converted (9, all `.java` deleted, matching `.kt` added)

| File | Shape |
|---|---|
| `ChatMessageEntity` | Room `@Entity`, `data class` |
| `EconomicHistoryEntry` | Room `@Entity`, `data class` |
| `TreasuryYield` | Room `@Entity`, `data class` + secondary constructor |
| `NewsArticle` | Room `@Entity`, `data class` |
| `BeaResponse` / `BlsResponse` / `FredResponse` | Gson response DTOs, plain `class` |
| `ChatMessage` | Plain `class` (mutable UI state) |
| `EconomicDataPoint` | Immutable `data class` |

## Scope grew significantly beyond the original plan — read this first

Phase 1 only checked how the 4 Room entities and 3 Gson DTOs were *constructed*.
It missed that several of them are also *read* via raw Java field syntax
(`y.series`, `e.value`, `beaResp.BEAAPI.Results.Data`, etc.) at call sites I
hadn't inventoried. This surfaced twice more after the batch was already
underway — once for `TreasuryYield`/`EconomicHistoryEntry`, once for the
three Gson DTOs. Both times you chose "rewrite the call sites to
getters/setters" over `@JvmField`, so that's what's applied throughout,
consistently. Net result: **12 files touched, not the ~9 originally scoped**:

- `api/NewsApiClient.java` — 11 field writes + 3 field reads → setters/getters
- `analyst/SmartPromptGenerator.java` — 2 field reads → getters
- `api/NewsArticleRepository.java` — 1 field write → setter
- `workers/YieldRefreshWorker.java` — 4 field reads → getters
- `analyst/AnalystToolExecutor.java` — 4 field reads → getters
- `analyst/HistoricalContextBuilder.java` — ~14 field reads → getters
- `ui/ChatAdapter.java` — 2 field reads → getters
- `api/EconomicRepository.java` — ~17 field reads (FRED/BEA/BLS parsing, 4 separate blocks) → getters
- `api/HistoricalDataRepository.java` — ~13 field reads (same 3 APIs, historical variant) → getters

Every one of these was a **mechanical rename only** — `x.field` → `x.getField()`
(or `x.setField(v)` for the 12 writes in `NewsApiClient.java`). No logic,
null-check structure, or short-circuit order was changed. I verified this
with a final exhaustive grep across the whole repo for every field name on
every converted class — zero raw field access remains anywhere.

## Room schema-fidelity — the real risk in this batch

`YieldDatabase` uses `.fallbackToDestructiveMigration()`, and its own code
comment documents a past incident (v3→v4) where an entity/schema mismatch
tripped Room's identity-hash check and forced a destructive table rebuild.
Three of the four tables are pure API caches (fine to lose), but
**`chat_messages` holds real, non-refetchable conversation history** — a
mismatch here could silently wipe it on next launch with no error.

To avoid that, nullability on every entity field was chosen to **exactly
match the existing schema**, not just "what looks right":

- `ChatMessageEntity.text` and every `NewsArticle` `String` field are
  **nullable** (`String? = null`) — the originals had no `@NonNull` and no
  field initializer (Java default `null`), and the `MIGRATION_1_2` SQL shows
  those columns as plain `TEXT` (no `NOT NULL`). I checked the actual
  migration SQL in `YieldDatabase.java` to confirm this, not just the Java
  source.
- `EconomicHistoryEntry` and `TreasuryYield`'s `String` fields stay
  **non-null** with `= ""` defaults — the originals had `@NonNull` +
  `= ""`, matching `NOT NULL DEFAULT ''` in the migration SQL.
- `TreasuryYield.toString()` is preserved as an explicit override, verbatim,
  rather than letting the data class auto-generate one (which would include
  different fields in a different format) — nothing currently calls it, but
  no reason to change observable behavior for free.

**You already ran the canary test before I started this batch** (sent a chat
message, rebuilt, relaunched, confirmed it survived) — that's a *baseline*,
not a post-change verification. Please repeat it after this batch's build;
see the checklist below.

## Interop annotations added and why

- **`@JvmOverloads`** on:
  - `ChatMessage`'s constructor — replicates its original 4 overloaded
    Java constructors (2/3/4/5-arg, each delegating to the next with a
    default) exactly.
  - `NewsArticle`'s constructor — the only external call is a bare
    `new NewsArticle()`; needed so Java gets a zero-arg overload.
  - `EconomicHistoryEntry`'s constructor — the 3 existing callers in
    `HistoricalDataRepository.java` pass exactly 7 positional args, no `id`.
    Since `@JvmOverloads` only drops *trailing* defaulted parameters, `id`
    was moved to the **last** position (from first) so the 7-arg Java
    overload exists. Room doesn't care about constructor parameter order
    (it maps by name), so this is safe.
- **`@JvmStatic`** on `ChatMessageEntity.from()` (companion factory) — same
  reasoning as prior batches.
- **No `@JvmField` anywhere in this batch** — every raw-field-access site
  found was rewritten to getter/setter calls instead, per your explicit
  choice (asked and confirmed 3 separate times as new instances of the same
  pattern kept surfacing).

## Left non-idiomatic / deliberately not touched

- `BeaResponse`/`BlsResponse`/`FredResponse` and their nested classes keep
  the original **uppercase-first field names** (`BEAAPI`, `Results`, `Data`,
  `LineDescription`, etc.) exactly as in the Java source, even though this
  isn't idiomatic Kotlin style. Renaming would only be a cosmetic win and
  would touch the `@SerializedName` JSON-key mapping logic unnecessarily —
  not worth the risk for zero functional benefit.
- Gson DTO fields use `var` (not `val`), despite the "val over var" rule —
  deliberate exception. Gson deserializes via reflection that bypasses
  Kotlin's normal property semantics; `val`'s `final` backing field is a
  known source of JVM reflection fragility with Gson specifically. `var` is
  the standard, documented-safe choice for Gson model classes.
- All Gson DTO fields are nullable regardless of what JSON always seems to
  contain in practice — Gson's reflective deserialization bypasses Kotlin's
  constructor-based non-null enforcement entirely, so a non-null field here
  would let a missing/malformed JSON response silently plant a null in a
  "guaranteed non-null" property, surfacing as a confusing NPE far from the
  real cause. This isn't a style choice, it's the only safe option.

## Zero `!!`

Grepped all 9 new files — none needed anywhere.

## Follow-up bugs spotted, not fixed

- None. All touched call sites were doing exactly what the original Java did.

## What Aaron needs to exercise on device

1. **Gradle sync, then Build → Rebuild Project.**
2. **Chat history canary (do this first, it's the one that catches silent
   data loss):** open the AI Analyst chat, confirm the message you sent
   before this batch is still there. If it's gone, `fallbackToDestructiveMigration()`
   fired — stop immediately and tell me, don't send new messages yet.
3. **Any dashboard tab load** (Overview, or any of the 10 fragment tabs):
   exercises `TreasuryYield`/`EconomicHistoryEntry` read/write through Room,
   and the FRED/BEA/BLS Gson parsing paths in `EconomicRepository`/
   `HistoricalDataRepository` — this is the highest-volume-of-edits path in
   this batch, so worth confirming numbers actually populate (not blank,
   not "—" placeholders where real data should be).
4. **AI Analyst: ask a historical question** (e.g. "how has the 10-year
   yield trended this year") — exercises `AnalystToolExecutor`/
   `HistoricalContextBuilder`'s reads of `EconomicHistoryEntry`.
5. **News tab**: load articles (exercises `NewsApiClient.parseArticleJson`'s
   rewritten setter calls) and tap into an article for AI prompts (exercises
   `SmartPromptGenerator`'s rewritten getter calls).
6. **Background yield refresh**: if practical, trigger `YieldRefreshWorker`
   (or just leave the app to run its scheduled refresh) — exercises its
   rewritten `TreasuryYield` reads.
7. **AI Analyst sparkline**: long-press a metric card to open the analyst,
   check that inline sparklines render (exercises `ChatAdapter`'s
   `EconomicHistoryEntry` read).

If red, or if the chat canary fails: paste the exact error back and I will
not start batch 4.
