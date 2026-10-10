# AI Analyst chat tickets (AIC)

Source: 2026-10-09 chat UI checklist audit (16 fail, 17 partial, 8 pass, 3 not tested).
Full report: https://claude.ai/artifact/YGgmraZDHVWahzj6fhrVPn
File refs: Sheet = ui/AiAnalystBottomSheet.kt, Ask = analyst/AskAnalyst.kt.


## AIC-01 · Long-press context sends rolling-digit garbage (G3) · P0
The card text harvest in AskAnalyst.kt:102-117 reads every digit of the rolling-number view.
Fix: pass value, date and seriesId from the card's data model; never scrape view text.
Also add date + seriesId to the chart long-press (Ask:207-214).
Accept: long-press 10Y-3M shows "10Y-3M Spread 0.99% (Oct 9, 2026)" in the user bubble.
Status (2026-10-09, feat/aic-01-ask-context): harvest now reads RollingNumberView.getText()
(the value set from data) instead of its digit columns; chart context dates the latest point
and attachChartExplain takes an optional seriesId. Chart callers do not pass seriesId yet.
Compiles; on-device check pending.

## AIC-02 · Scroll to bottom on send; follow by pixels (C2, C3) · P0
Sheet:664 measures "near bottom" by item count. On send: always scroll to the end. While
streaming: follow only if within 100dp of the bottom. Hide the pill right after the user's
own send, and place it above the composer so it never covers a bubble.

## AIC-03 · Stop keeps, labels and saves the partial answer (B5) · P1
Sheet:551-566. Mark the message interrupted, persist it, add it to history, and show
"Stopped" plus Continue / Regenerate on that bubble.

## AIC-04 · Copy captures stale text (bug) · P1
ChatAdapter.kt:848-853 captures m.text in a closure; read the current item at click time.

## AIC-05 · Per-reply action row: Copy, Regenerate, model label (A2, A3, B7) · P1
Visible row under each finished assistant reply: Copy · Regenerate · "Haiku 4.5" / "Sonnet 5".
Regenerate keeps the previous answer (a "1/2" toggle). Show the date with the time on
older turns.

## AIC-06 · TalkBack support (E1) · P1
Call announceForAccessibility with the final text on completion; make the trace row a
polite live region.

## AIC-07 · Name errors plainly (B6) · P1
429 → "Busy, try again in a minute"; 5xx / overloaded → "The analyst service is down";
no network → "You're offline". Never show the raw "API error" or "overloaded_error" text.

## AIC-08 · Show starters; new chat without deleting (D5, F4, A1) · P2
Show STARTER_PROMPTS when only the welcome message is present. When HISTORY_CAP trims, add
a divider: "Older messages aren't sent to the analyst. Start new chat". New chat archives
the old thread instead of deleting it.

## AIC-09 · Streaming polish (B3, C4, C5, B2) · P2
Batch text updates every 50ms; skip bindCharts on text-only updates; close a dangling **
before rendering; show a caret while streaming that dims after 1.5s with no new text; keep
the trace open until tool rounds finish.

## AIC-10 · Keyboard and layout (D2, D4, D3, E7, E6) · P2
Switch to WindowInsets / imePadding; keep the list pinned to the bottom when the keyboard
opens; collapse the chips while the keyboard shows; 48dp hit areas on Attach, Clear and
Close; wrap_content header height; send on KEYCODE_ENTER.

## AIC-11 · Trust surface (F1, F2, F3, A5) · P3
Save sources with the message; show a "stale" chip in the reply; put the disclaimer in the
welcome message once; write the bubble-vs-flat decision into UX_DESIGN_LAWS.md; make
follow-ups specific to the answer and remove the duplicate chip row.
