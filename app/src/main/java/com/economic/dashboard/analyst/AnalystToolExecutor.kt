package com.economic.dashboard.analyst

import android.content.Context

import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.models.EconomicHistoryEntry
import com.economic.dashboard.news.NewsItem
import com.economic.dashboard.news.NewsRepository

import org.json.JSONArray
import org.json.JSONObject

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Server side of the AI Analyst's tool calls, executed on-device.
 *
 * When Claude's response stops with stop_reason "tool_use", the app runs the
 * requested tool here (against the Room cache / news cache / live ViewModel
 * data) and sends the result back as a tool_result block. Never call from the
 * main thread — get_series runs synchronous Room queries.
 */
object AnalystToolExecutor {

    /** Room-cached series ids (matches HistoricalDataRepository). Derived from
     *  EconomicSeriesRegistry, the single source of truth for series ids
     *  (TICKET-F / review F6) — was hand-duplicated here, in ChatAdapter, and
     *  in buildSystemPrompt()'s INLINE CHARTS sentence. */
    @JvmField
    val SERIES_IDS: Array<String> = EconomicSeriesRegistry.roomCachedIds()

    /**
     * AI Law 3 (tool coverage matches trigger surfaces): series served straight
     * from the ViewModel data the app already charts, so a long-press on ANY
     * fragment's card has real history behind it. Ids map to lists supplied by
     * the LiveSeriesProvider wired up in AiAnalystBottomSheet.
     */
    @JvmField
    val LIVE_SERIES: Map<String, String> = EconomicSeriesRegistry.liveToolHints()

    /**
     * Supplies live ViewModel-backed series lists; set by AiAnalystBottomSheet.
     *
     * Declared `fun interface` deliberately. Its only implementation is a lambda
     * in AiAnalystBottomSheet — which is still Java today (Java SAM-converts to
     * any single-method interface regardless of source language), but becomes
     * Kotlin in batch 9c, and a Kotlin lambda will NOT convert to a plain
     * Kotlin-declared interface. Without `fun` here, 9b builds green and 9c
     * breaks.
     */
    fun interface LiveSeriesProvider {
        fun get(seriesId: String): List<EconomicDataPoint>?
    }

    /**
     * Backing field is deliberately NOT named `liveSeriesProvider`: a Kotlin
     * property of that name synthesizes a `setLiveSeriesProvider` accessor that
     * collides with the explicit function below on the same JVM signature
     * ("platform declaration clash"). The public function name is load-bearing
     * for AiAnalystBottomSheet, so the field is what gets renamed.
     */
    @Volatile
    private var liveProvider: LiveSeriesProvider? = null

    @JvmStatic
    fun setLiveSeriesProvider(p: LiveSeriesProvider?) {
        liveProvider = p
    }

    /**
     * TICKET-2 (AI Law 13): read access for renderers (inline chat sparklines)
     * so a [CHART:CPI:24M] tag draws from the same points the get_series tool
     * serves. Returns null when no provider is wired or the series is empty.
     */
    @JvmStatic
    fun getLiveSeriesPoints(seriesId: String): List<EconomicDataPoint>? {
        val p = liveProvider
        return if (p != null) p.get(seriesId) else null
    }

    /** JSON "tools" array for the Anthropic request body. */
    @JvmStatic
    @Throws(Exception::class)
    fun buildToolsJson(): JSONArray {
        val tools = JSONArray()

        val seriesEnum = JSONArray()
        for (id in EconomicSeriesRegistry.allIds()) seriesEnum.put(id)

        val hintDesc = StringBuilder()
        for (e in EconomicSeriesRegistry.entries())
            hintDesc.append(e.id).append("=").append(e.toolHint).append(", ")

        val seriesParam = JSONObject()
            .put("type", "string")
            .put("enum", seriesEnum)
            .put("description", "Series to fetch: " + hintDesc)
        val monthsParam = JSONObject()
            .put("type", "integer")
            .put("description", "How many months of history to return (1-24). Default 24.")
        tools.put(JSONObject()
            .put("name", "get_series")
            .put("description", "Fetch up to 24 months of historical data points for one " +
                "economic series (cached or live dashboard data). Use when the user " +
                "asks about specific past values, turning points, or trends the " +
                "summary block doesn't cover.")
            .put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("series_id", seriesParam)
                    .put("months", monthsParam))
                .put("required", JSONArray().put("series_id"))))

        val topicParam = JSONObject()
            .put("type", "string")
            .put("description", "Optional keyword filter (e.g. 'fed', 'inflation', 'jobs'). " +
                "Omit for the top headlines across all topics.")
        val countParam = JSONObject()
            .put("type", "integer")
            .put("description", "Max headlines to return (1-25). Default 10.")
        tools.put(JSONObject()
            .put("name", "get_headlines")
            .put("description", "Fetch cached economic news headlines with summaries, beyond the " +
                "few in the prompt. Use when the user asks what's in the news about a topic.")
            .put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("topic", topicParam)
                    .put("count", countParam))))
        return tools
    }

    /** Runs one tool call; returns the text payload for the tool_result block. */
    @JvmStatic
    fun execute(ctx: Context, name: String, input: JSONObject): String {
        return try {
            when (name) {
                "get_series" -> getSeries(ctx, input)
                "get_headlines" -> getHeadlines(input)
                else -> "Unknown tool: " + name
            }
        } catch (e: Exception) {
            "Tool error: " + e.message
        }
    }

    private fun getSeries(ctx: Context, input: JSONObject): String {
        val seriesId = input.optString("series_id", "")
        val months = Math.max(1, Math.min(24, input.optInt("months", 24)))

        for (s in SERIES_IDS)
            if (s == seriesId) return getCachedSeries(ctx, seriesId, months)
        if (LIVE_SERIES.containsKey(seriesId)) return getLiveSeries(seriesId, months)

        // AI Law 16: be honest about limits instead of inviting a retry loop.
        return "Invalid series_id: " + seriesId + ". No such series is available — do not " +
            "retry; answer from the data snapshot in the system prompt instead."
    }

    /** Room-cached series (the original six). */
    private fun getCachedSeries(ctx: Context, seriesId: String, months: Int): String {
        val cutoffCal = Calendar.getInstance()
        cutoffCal.add(Calendar.MONTH, -months)
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.time)

        val rows: List<EconomicHistoryEntry> = YieldDatabase.getInstance(ctx)
            .economicHistoryDao().getSeriesSync(seriesId)
        if (rows.isEmpty())
            return "No cached data for " + seriesId + " yet — do not retry; answer from the " +
                "data snapshot in the system prompt and say the history isn't loaded."

        val sb = StringBuilder()
        val seriesName = rows[0].seriesName
        sb.append(seriesName).append(" (").append(seriesId).append("), last ")
            .append(months).append(" months:\n")
        var count = 0
        for (e in rows) {
            if (e.date.compareTo(cutoff) < 0) continue
            sb.append(e.date).append(": ")
                .append(String.format(Locale.US, "%.2f", e.value)).append(e.unit).append("\n")
            count++
        }
        return if (count == 0) "No data points in the requested window." else sb.toString()
    }

    /** Live ViewModel-backed series (everything else the app charts). */
    private fun getLiveSeries(seriesId: String, months: Int): String {
        val p = liveProvider
        val rows: List<EconomicDataPoint>? = if (p != null) p.get(seriesId) else null
        if (rows == null || rows.isEmpty())
            return "No data loaded for " + seriesId + " right now — do not retry; answer from " +
                "the data snapshot in the system prompt and say the history isn't available."

        // Date-window filter. Dates are "yyyy-MM-dd" or "yyyy-MM" strings, so a
        // lexicographic compare against a cutoff works; unparseable dates pass.
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -months)
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val filtered = ArrayList<EconomicDataPoint>()
        for (dp in rows) {
            val d = dp.date
            if (d.length < 7) { filtered.add(dp); continue }
            val norm = if (d.length == 7) d + "-28" else d
            if (norm.compareTo(cutoff) >= 0) filtered.add(dp)
        }
        // Reassigned to a subList view below, so the reference is `var` while the
        // type stays read-only.
        var window: List<EconomicDataPoint> = filtered
        if (window.isEmpty())
            window = rows.subList(Math.max(0, rows.size - months), rows.size)

        // Daily series would be hundreds of points — sample down to keep the
        // tool result a reasonable size for the model.
        val maxPoints = 40
        val step = Math.max(1, Math.ceil(window.size / maxPoints.toDouble()).toInt())

        val sb = StringBuilder()
        sb.append(LIVE_SERIES.get(seriesId)).append(" (").append(seriesId).append("), last ")
            .append(months).append(" months, ").append(window.size).append(" points")
            .append(if (step > 1) " (every " + step + getOrdinalSuffix(step) + " point shown)" else "")
            .append(":\n")
        for (i in window.indices) {
            if (i % step != 0 && i != window.size - 1) continue
            val dp = window[i]
            sb.append(dp.date).append(": ")
                .append(String.format(Locale.US, "%.2f", dp.value))
            if (dp.unit.isNotEmpty())
                sb.append(" ").append(dp.unit)
            sb.append("\n")
        }
        // TICKET-4 (AI Laws 15, 16): pre-compute the trailing YoY for index-level
        // series from the FULL row list (never the sampled window), so the model
        // reports a guaranteed-correct number instead of deriving one from points
        // the down-sampler may not have kept 12 months apart.
        if ("CPI" == seriesId || "PCE" == seriesId || "CORE_PCE" == seriesId) {
            sb.append("(These are index LEVELS, not percentages.)\n")
            var yoyAppended = false
            if (rows.size >= 13) {
                val latest = rows[rows.size - 1]
                val anchor = rows[rows.size - 13]
                if (Math.abs(anchor.value) > 1e-9) {
                    val yoy = ((latest.value - anchor.value) / anchor.value) * 100.0
                    sb.append(String.format(Locale.US,
                        "Trailing YoY change (pre-computed, %s -> %s): %+.2f%% — use this " +
                            "figure directly; do not derive YoY from the sampled points above.\n",
                        anchor.date, latest.date, yoy))
                    yoyAppended = true
                }
            }
            if (!yoyAppended)
                sb.append("(Fewer than 13 monthly points loaded — YoY is not computable; " +
                    "describe the level trend only and say the YoY figure isn't available.)\n")
        }
        return sb.toString()
    }

    private fun getOrdinalSuffix(n: Int): String {
        if (n % 100 >= 11 && n % 100 <= 13) return "th"
        return when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

    private fun getHeadlines(input: JSONObject): String {
        val topic = input.optString("topic", "").lowercase(Locale.US).trim()
        val count = Math.max(1, Math.min(25, input.optInt("count", 10)))

        val items: List<NewsItem> = NewsRepository.getInstance().cachedItems
        if (items.isEmpty())
            return "No cached headlines available right now."

        val sb = StringBuilder()
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        var added = 0
        // High-impact first (tier 2 → 0), matching the app's own ranking.
        var tier = 2
        while (tier >= 0 && added < count) {
            for (item in items) {
                if (added >= count) break
                val title = item.title
                if (item.impactLevel != tier || title == null) continue
                val summary = item.summary
                if (!topic.isEmpty()) {
                    val hay = (title + " " + (if (summary == null) "" else summary) +
                        " " + (if (item.tag == null) "" else item.tag)).lowercase(Locale.US)
                    if (!hay.contains(topic)) continue
                }
                sb.append("[").append(item.source).append(", ")
                    .append(fmt.format(Date(item.pubDateMillis))).append("] ")
                    .append(title)
                if (summary != null && !summary.isEmpty()) {
                    val sum = if (summary.length > 200) summary.substring(0, 200) + "…" else summary
                    sb.append(" — ").append(sum)
                }
                sb.append("\n")
                added++
            }
            tier--
        }
        return if (added == 0) "No cached headlines match '" + topic + "'." else sb.toString()
    }
}
