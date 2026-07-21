# AI Analyst Deep Integration

Summary of the July 2026 update (`b9635f5`) that wires the AI Analyst LLM deeper
into the Economic Dashboard app — richer context flowing *into* the model, and
more entry points for the model *throughout* the app.

---

## Part 1 — App data into the LLM

### Derived cross-metric readings
**New:** `analyst/DerivedMetricsBuilder.java`

The system prompt now includes computed values that unlock better analysis than
raw levels: real 10Y yield (10Y − CPI YoY), real policy rate (Fed Funds − CPI
YoY), 2Y–3M spread (near-term Fed expectations), mortgage spread vs the 10Y
(vs ~1.7% historical norm), and the HY-minus-BAA risk-premium gap.

### Upcoming release calendar
**New:** `analyst/ReleaseCalendar.java`

The model knows the next FOMC meeting, CPI release, and jobs report dates, and
is instructed to mention them when relevant ("watch Thursday's CPI print").

### Screen-aware context
**Changed:** `MainActivity.java`, `AiAnalystBottomSheet.java`

The system prompt tells the model which tab the user is currently viewing
(Overview / Markets / Economy / News). Entry points can also pass a
metric-specific context block via `newInstanceWithContext(...)`.

### Tool use — the model queries the app's own data
**New:** `analyst/AnalystToolExecutor.java` · **Changed:** `AiAnalystBottomSheet.java`

The chat request now includes two Anthropic tools, executed **on-device**:

| Tool | What it does |
|---|---|
| `get_series(series_id, months)` | Returns up to 24 months of cached history from Room (DGS10, DGS2, DGS3MO, MORTGAGE30US, LNS14000000, GDP_BEA_T10101) |
| `get_headlines(topic, count)` | Returns cached news headlines + summaries, impact-ranked, optionally keyword-filtered |

The streaming handler accumulates `tool_use` blocks, runs the tools, sends
`tool_result` messages back, and continues the turn (up to 3 rounds) — all
streamed into a single chat bubble. Existing prompt-stuffed context is kept, so
simple questions stay fast. No proxy changes were needed.

---

## Part 2 — LLM into the app

### Inline charts in replies
**New:** `views/SparklineView.java` · **Changed:** `ChatAdapter.java`, `item_chat_message.xml`

The model may emit one `[CHART:DGS10:24M]`-style tag per reply. The tag is
stripped from the text and rendered as a lightweight sparkline (with caption)
inside the bubble, using data loaded asynchronously from Room and memo-cached.

### Tappable metric citations
**Changed:** `ChatAdapter.java`, `MainActivity.java`

Known indicator names in analyst replies (unemployment, CPI, GDP, yield curve,
VIX, S&P 500, …) become underlined links. Tapping one closes the chat sheet and
navigates to the matching dashboard tab — chat doubles as navigation.

### Long-press anywhere → Ask AI
**New:** `analyst/AskAnalyst.java` · **Changed:** all 10 metric fragments

- **Metric cards** in every fragment (GDP, CPI, employment, inflation, spreads,
  stocks, bonds, wages) get the same long-press → "Ask AI Analyst" menu the
  Overview screen already had.
- **Charts:** long-press any MPAndroidChart → "✨ Explain this chart" sends the
  chart's description plus screen context to the analyst.

### News → analyst
**Changed:** `news/NewsAdapter.java`

Long-press any headline (hero or row) for a menu: "✨ Analyze with AI Analyst"
(sends title, source, summary) plus up to two topic-matched
`SmartPromptGenerator` prompts.

### Android share target
**Changed:** `AndroidManifest.xml` (singleTask + SEND filters), `MainActivity.java`

Share text, links, or headlines from any app (Chrome, X, news apps) straight
into the AI Analyst. Shared **images** open the analyst with the image queued
for vision analysis.

### Image analysis (vision)
**New:** `utils/ImageUtils.java` · **Changed:** `AiAnalystBottomSheet.java`, `ChatMessage.java`, `dialog_ai_chat.xml`

A paperclip button in the chat input opens the photo picker. Images are
downscaled to 1024px, JPEG-encoded, and sent as base64 blocks to the vision
API alongside the full economic context. A preview strip shows the pending
image; the sent image renders in the user's bubble (in-session only — images
are not persisted to Room, so no DB migration).

### Text-selection "Ask AI"
**Changed:** `ChatAdapter.java` · **New:** `res/values/ids.xml`

Selecting text in an analyst bubble adds an **Ask AI** item next to Copy/Share
that asks a follow-up about the selected passage.

### Daily AI morning brief
**New:** `workers/DailyBriefWorker.java` · **Changed:** `SettingsManager.java`, `SettingsBottomSheet.java`, `sheet_settings.xml`, `strings.xml`

Opt-in setting ("Daily AI morning brief", off by default — one API call/day).
A WorkManager job at ~7:30 AM builds a prompt from the Room history cache,
release calendar, and fresh headlines, asks for a 3–5 sentence brief, posts it
as a notification, and inserts it into the persisted chat so it's waiting when
the app opens.

---

## Notes

- **Testing:** changes were consistency-checked but not compiled (authored
  outside Android Studio) — build and smoke-test before release.
- **Quick test path:** long-press a chart → Explain; ask "show me the 10Y trend
  over 2 years" (tool call + chart tag); share a headline from Chrome into the
  app; enable the morning brief in Settings.
- Tool use, vision, and streaming all pass through the existing Cloudflare
  Worker proxy unchanged.
