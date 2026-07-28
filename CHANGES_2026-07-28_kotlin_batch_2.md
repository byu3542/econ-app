# Kotlin Migration — Batch 2 (`ui/views/`)

Branch: `kotlin/batch-2`

## Files converted

| File | What it does |
|---|---|
| `PressFeedback` | Spring-based press-in/out feedback for tappable cards |
| `SegmentedControl` | Sliding-thumb spring animation under a segmented control |
| `SkeletonController` | Per-fragment shimmer-skeleton overlay controller |

Batch table correction (flagged in Phase 1): despite the `ui/views/`
package name, none of these three subclasses a View/ViewGroup — they're
helper classes that operate *on* views, not custom View subclasses. No
`@JvmOverloads`/constructor concerns applied.

## Caller edits

**None.** Unlike batch 1's `AppExecutors`, every public method here kept its
exact call shape (`PressFeedback.attach(view)`, `SegmentedControl.slideTo/snapTo(...)`,
`SkeletonController.wrap(...)` + instance `.getRoot()`/`.reveal()`), so all
13 caller files (8 fragments using `SkeletonController`, 4 using
`PressFeedback`, `TimeframeSelector` using `SegmentedControl`) compile
unchanged.

## Interop annotations added and why

- `@JvmStatic` on `PressFeedback.attach`, `SegmentedControl.slideTo`/`snapTo`,
  and `SkeletonController`'s companion `wrap` — same reasoning as batch 1,
  keeps the static-style Java call sites working.
- **`SkeletonController` is a real `class`, not an `object`** — it's the one
  exception in the migration so far. 8 fragments each hold their own
  `private SkeletonController skeleton` field and call instance methods
  (`skeleton.reveal()`, `skeleton.getRoot()`). Only `wrap()` is a static-style
  factory call, so it lives in a `companion object`; `reveal()` and
  `getRoot()` are ordinary instance methods needing no annotation (Java
  already calls them on an instance).
- Kept an explicit `fun getRoot(): View = root` rather than relying on
  Kotlin's auto-generated getter for a public `root` property — both would
  produce an identical `getRoot()` bytecode signature for Java callers, but
  the explicit function makes the Java-visible API unambiguous to verify by
  reading the file, and matches rule 6 (reuse existing shapes) most literally.

## Nullability / judgment calls

- `PressFeedback.attach(view: View?)` and `SegmentedControl.slideTo`/`snapTo`
  (`View?` params) — preserved nullable, matching the originals' explicit
  `== null` guards.
- `SkeletonController.wrap(content: View)` — kept **non-nullable**. The
  original never null-checked `content` (would NPE immediately on
  `content.getContext()` if null), so this reflects actual reality, not a
  defensive posture that isn't really there.
- `SegmentedControl.spring(v)`: original did an unchecked Java cast
  `(SpringAnimation) v.getTag()` (would throw `ClassCastException` if the
  tag were ever something else — never happens in practice, since only this
  code ever tags these views). Translated to Kotlin's safe cast `as?` rather
  than a forced `as`, so a currently-impossible mismatch would silently
  build a fresh `SpringAnimation` instead of crashing. Flagging this as a
  deliberate, low-risk idiomatic choice rather than a literal one-for-one
  translation — happy to change to a forced `as` if you'd rather match the
  original crash-on-mismatch behavior exactly.

## Zero `!!`

Grepped all 3 files — none needed.

## Left non-idiomatic / deliberately not touched

- Nothing else — straightforward translations throughout.

## Follow-up bugs spotted, not fixed

- None.

## What Aaron needs to exercise on device

1. **Dashboard tab**: tap any KPI card (GDP, CPI YoY, Spread, Unemployment,
   Mortgage, VIX, Fed Funds hero) — confirms `PressFeedback.attach` press-in/
   spring-back still works, and that touch events still pass through to the
   card's click/long-click listeners (the "never consume the event" comment
   in the file — if this regressed, tapping a card wouldn't navigate/open
   anything).
2. **TimeframeSelector** (the timeframe chip row wherever it appears, e.g.
   chart timeframe pickers): switch timeframes and confirm the sliding thumb
   animates smoothly under the active chip (`SegmentedControl`), including
   rapid alternating taps (tests the spring-retargeting path, and the
   `as?` cast change above).
3. **Bonds / Employment / GDP / Housing / Inflation / Spreads / Stocks /
   Yields tabs**: confirm the shimmer skeleton shows on first load and
   cross-fades into real content once data arrives (`SkeletonController`).
4. **Settings → Reduce motion toggle**: confirm both the press-feedback and
   skeleton-reveal paths swap instantly instead of animating when enabled.

If red: paste the exact error back and I will not start batch 3.
