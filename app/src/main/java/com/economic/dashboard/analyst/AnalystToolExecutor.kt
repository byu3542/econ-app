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
     *
     * Since the 2026-07-30 cache expansion every catalogued series is
     * ROOM_CACHED, so this map is empty in practice — but the live provider is
     * still wired and still load-bearing: [getCachedSeries] falls back to it
     * whenever Room has no rows yet (first launch, before the catalog refresh
     * lands), which is exactly the case the live path was built for.
     */
    @JvmField
    val LIVE_SERIES: Map<String, String> = EconomicSeriesRegistry.liveToolHints()

    /** Max points emitted in one tool result before down-sampling kicks in. */
    private const val MAX_POINTS = 40

    /** Normalized (date, value) pair so the Room and live paths share a renderer. */
    private class Pt(@JvmField val date: String, @JvmField val value: Double)

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
        val months = input.optInt("months", 24).coerceIn(1, 24)

        for (s in SERIES_IDS)
            if (s == seriesId) return getCachedSeries(ctx, seriesId, months)
        if (LIVE_SERIES.containsKey(seriesId)) return getLiveSeries(seriesId, months)

        // AI Law 16: be honest about limits instead of inviting a retry loop.
        return "Invalid series_id: " + seriesId + ". No such series is available — do not " +
            "retry; answer from the data snapshot in the system prompt instead."
    }

    /**
     * Room-cached series — as of the 2026-07-30 cache expansion that is the
     * whole catalog, including daily market series with ~550 rows over the
     * 24-month window. [render] down-samples, so a get_series call costs the
     * model a bounded number of lines regardless of the series' frequency.
     *
     * Falls back to the live ViewModel provider when Room has nothing yet — the
     * catalog refresh is asynchronous, so on a cold first launch the table can
     * still be empty while the dashboard already has points in memory.
     */
    private fun getCachedSeries(ctx: Context, seriesId: String, months: Int): String {
        val cutoff = cutoffDate(months)

        val rows: List<EconomicHistoryEntry> = YieldDatabase.getInstance(ctx)
            .economicHistoryDao().getSeriesSync(seriesId)

        val all = ArrayList<Pt>(rows.size)
        val window = ArrayList<Pt>()
        var unit = ""
        var seriesName = seriesId
        for (e in rows) {
            all.add(Pt(e.date, e.value))
            if (e.date.compareTo(cutoff) >= 0) window.add(Pt(e.date, e.value))
            unit = e.unit
            seriesName = e.seriesName
        }

        if (window.isEmpty()) {
            val live = buildLiveSeries(seriesId, months)
            if (live != null) return live
            return if (rows.isEmpty())
                "No cached data for " + seriesId + " yet — do not retry; answer from the " +
                    "data snapshot in the system prompt and say the history isn't loaded."
            else
                "No data points in the requested window."
        }

        return render(seriesName, seriesId, months, window, all, unit)
    }

    /** Live ViewModel-backed series (used as the cache-cold fallback). */
    private fun getLiveSeries(seriesId: String, months: Int): String =
        buildLiveSeries(seriesId, months)
            ?: ("No data loaded for " + seriesId + " right now — do not retry; answer from " +
                "the data snapshot in the system prompt and say the history isn't available.")

    /** Renders the live series, or null when the provider has nothing to show. */
    private fun buildLiveSeries(seriesId: String, months: Int): String? {
        val p = liveProvider
        val rows: List<EconomicDataPoint>? = if (p != null) p.get(seriesId) else null
        if (rows == null || rows.isEmpty()) return null

        // Date-window filter. Dates are "yyyy-MM-dd" or "yyyy-MM" strings, so a
        // lexicographic compare against a cutoff works; unparseable dates pass.
        val cutoff = cutoffDate(months)
        val all = ArrayList<Pt>(rows.size)
        val filtered = ArrayList<Pt>()
        var unit = ""
        for (dp in rows) {
            val pt = Pt(dp.date, dp.value)
            all.add(pt)
            if (dp.unit.isNotEmpty()) unit = dp.unit
            val d = dp.date
            if (d.length < 7) { filtered.add(pt); continue }
            val norm = if (d.length == 7) d + "-28" else d
            if (norm.compareTo(cutoff) >= 0) filtered.add(pt)
        }
        // Reassigned to a subList view below, so the reference is `var` while the
        // type stays read-only.
        var window: List<Pt> = filtered
        if (window.isEmpty()) window = all.subList(Math.max(0, all.size - months), all.size)

        val name = LIVE_SERIES[seriesId]
            ?: EconomicSeriesRegistry.byId(seriesId)?.toolHint
            ?: seriesId
        return render(name, seriesId, months, window, all, unit)
    }

    /**
     * Shared renderer for both paths.
     *
     * @param window the points to print (already date-filtered)
     * @param all    the complete series — YoY is always computed from this, never
     *               from the sampled window
     */
    private fun render(
        seriesName: String, seriesId: String, months: Int,
        window: List<Pt>, all: List<Pt>, unit: String
    ): String {
        // Daily series would be hundreds of points — sample down to keep the
        // tool result a reasonable size for the model.
        val step = Math.max(1, Math.ceil(window.size / MAX_POINTS.toDouble()).toInt())

        val sb = StringBuilder()
        sb.append(seriesName).append(" (").append(seriesId).append("), last ")
            .append(months).append(" months, ").append(window.size).append(" points")
            .append(if (step > 1) " (every " + step + getOrdinalSuffix(step) + " point shown)" else "")
            .append(":\n")
        for (i in window.indices) {
            if (i % step != 0 && i != window.size - 1) continue
            val pt = window[i]
            sb.append(pt.date).append(": ")
                .append(String.format(Locale.US, "%.2f", pt.value))
            if (unit.isNotEmpty()) {
                // "%" reads as a suffix; every other unit needs a separator so
                // an index level doesn't render as "5432.10Index".
                if ("%" != unit) sb.append(" ")
                sb.append(unit)
            }
            sb.append("\n")
        }
        appendYoyIfIndexLevel(sb, seriesId, all)
        return sb.toString()
    }

    /**
     * TICKET-4 (AI Laws 15, 16): pre-compute the trailing YoY for index-level
     * series from the FULL row list (never the sampled window), so the model
     * reports a guaranteed-correct number instead of deriving one from points
     * the down-sampler may not have kept 12 months apart.
     *
     * These three series are monthly in both Room and the ViewModel, so the
     * 13th-from-last point is exactly 12 months back.
     */
    private fun appendYoyIfIndexLevel(sb: StringBuilder, seriesId: String, all: List<Pt>) {
        if ("CPI" != seriesId && "PCE" != seriesId && "CORE_PCE" != seriesId) return

        sb.append("(These are index LEVELS, not percentages.)\n")
        if (all.size >= 13) {
            val latest = all[all.size - 1]
            val anchor = all[all.size - 13]
            if (Math.abs(anchor.value) > 1e-9) {
                val yoy = ((latest.value - anchor.value) / anchor.value) * 100.0
                sb.append(String.format(Locale.US,
                    "Trailing YoY change (pre-computed, %s -> %s): %+.2f%% — use this " +
                        "figure directly; do not derive YoY from the sampled points above.\n",
                    anchor.date, latest.date, yoy))
                return
            }
        }
        sb.append("(Fewer than 13 monthly points loaded — YoY is not computable; " +
            "describe the level trend only and say the YoY figure isn't available.)\n")
    }

    /** "yyyy-MM-dd" cutoff `months` back from today. */
    private fun cutoffDate(months: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -months)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
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
        val count = input.optInt("count", 10).coerceIn(1, 25)

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
