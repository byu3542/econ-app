package com.economic.dashboard.analyst;

import android.content.Context;

import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.news.NewsItem;
import com.economic.dashboard.news.NewsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server side of the AI Analyst's tool calls, executed on-device.
 *
 * When Claude's response stops with stop_reason "tool_use", the app runs the
 * requested tool here (against the Room cache / news cache / live ViewModel
 * data) and sends the result back as a tool_result block. Never call from the
 * main thread — get_series runs synchronous Room queries.
 */
public class AnalystToolExecutor {

    /** Room-cached series ids (matches HistoricalDataRepository). */
    public static final String[] SERIES_IDS = {
            "DGS10", "DGS2", "DGS3MO", "MORTGAGE30US", "LNS14000000", "GDP_BEA_T10101"
    };

    /**
     * AI Law 3 (tool coverage matches trigger surfaces): series served straight
     * from the ViewModel data the app already charts, so a long-press on ANY
     * fragment's card has real history behind it. Ids map to lists supplied by
     * the LiveSeriesProvider wired up in AiAnalystBottomSheet.
     */
    public static final Map<String, String> LIVE_SERIES = new LinkedHashMap<>();
    static {
        LIVE_SERIES.put("CPI",            "CPI-U All Items index");
        LIVE_SERIES.put("PCE",            "PCE Price Index");
        LIVE_SERIES.put("CORE_PCE",       "Core PCE Price Index");
        LIVE_SERIES.put("SP500",          "S&P 500 index");
        LIVE_SERIES.put("NASDAQ",         "Nasdaq Composite index");
        LIVE_SERIES.put("VIX",            "VIX volatility index");
        LIVE_SERIES.put("WAGES",          "Average Hourly Earnings (private)");
        LIVE_SERIES.put("HOUSING_STARTS", "Housing Starts");
        LIVE_SERIES.put("HOME_SALES",     "Existing Home Sales");
        LIVE_SERIES.put("BAA_SPREAD",     "BAA Corporate Bond Spread");
        LIVE_SERIES.put("HY_SPREAD",      "High Yield Bond Spread");
    }

    /** Supplies live ViewModel-backed series lists; set by AiAnalystBottomSheet. */
    public interface LiveSeriesProvider {
        List<EconomicDataPoint> get(String seriesId);
    }

    private static volatile LiveSeriesProvider liveSeriesProvider;

    public static void setLiveSeriesProvider(LiveSeriesProvider p) {
        liveSeriesProvider = p;
    }

    /**
     * TICKET-2 (AI Law 13): read access for renderers (inline chat sparklines)
     * so a [CHART:CPI:24M] tag draws from the same points the get_series tool
     * serves. Returns null when no provider is wired or the series is empty.
     */
    public static List<EconomicDataPoint> getLiveSeriesPoints(String seriesId) {
        LiveSeriesProvider p = liveSeriesProvider;
        return p != null ? p.get(seriesId) : null;
    }

    /** JSON "tools" array for the Anthropic request body. */
    public static JSONArray buildToolsJson() throws Exception {
        JSONArray tools = new JSONArray();

        JSONArray seriesEnum = new JSONArray()
                .put("DGS10").put("DGS2").put("DGS3MO")
                .put("MORTGAGE30US").put("LNS14000000").put("GDP_BEA_T10101");
        for (String id : LIVE_SERIES.keySet()) seriesEnum.put(id);

        StringBuilder liveDesc = new StringBuilder();
        for (Map.Entry<String, String> e : LIVE_SERIES.entrySet())
            liveDesc.append(e.getKey()).append("=").append(e.getValue()).append(", ");

        JSONObject seriesParam = new JSONObject()
                .put("type", "string")
                .put("enum", seriesEnum)
                .put("description", "Series to fetch: DGS10=10Y Treasury, DGS2=2Y Treasury, "
                        + "DGS3MO=3M T-Bill, MORTGAGE30US=30Y mortgage rate, "
                        + "LNS14000000=unemployment rate, GDP_BEA_T10101=GDP growth QoQ annualized, "
                        + liveDesc);
        JSONObject monthsParam = new JSONObject()
                .put("type", "integer")
                .put("description", "How many months of history to return (1-24). Default 24.");
        tools.put(new JSONObject()
                .put("name", "get_series")
                .put("description", "Fetch up to 24 months of historical data points for one "
                        + "economic series (cached or live dashboard data). Use when the user "
                        + "asks about specific past values, turning points, or trends the "
                        + "summary block doesn't cover.")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("series_id", seriesParam)
                                .put("months", monthsParam))
                        .put("required", new JSONArray().put("series_id"))));

        JSONObject topicParam = new JSONObject()
                .put("type", "string")
                .put("description", "Optional keyword filter (e.g. 'fed', 'inflation', 'jobs'). "
                        + "Omit for the top headlines across all topics.");
        JSONObject countParam = new JSONObject()
                .put("type", "integer")
                .put("description", "Max headlines to return (1-25). Default 10.");
        tools.put(new JSONObject()
                .put("name", "get_headlines")
                .put("description", "Fetch cached economic news headlines with summaries, beyond the "
                        + "few in the prompt. Use when the user asks what's in the news about a topic.")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("topic", topicParam)
                                .put("count", countParam))));
        return tools;
    }

    /** Runs one tool call; returns the text payload for the tool_result block. */
    public static String execute(Context ctx, String name, JSONObject input) {
        try {
            if ("get_series".equals(name))    return getSeries(ctx, input);
            if ("get_headlines".equals(name)) return getHeadlines(input);
            return "Unknown tool: " + name;
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    private static String getSeries(Context ctx, JSONObject input) {
        String seriesId = input.optString("series_id", "");
        int months = Math.max(1, Math.min(24, input.optInt("months", 24)));

        for (String s : SERIES_IDS)
            if (s.equals(seriesId)) return getCachedSeries(ctx, seriesId, months);
        if (LIVE_SERIES.containsKey(seriesId)) return getLiveSeries(seriesId, months);

        // AI Law 16: be honest about limits instead of inviting a retry loop.
        return "Invalid series_id: " + seriesId + ". No such series is available — do not "
                + "retry; answer from the data snapshot in the system prompt instead.";
    }

    /** Room-cached series (the original six). */
    private static String getCachedSeries(Context ctx, String seriesId, int months) {
        java.util.Calendar cutoffCal = java.util.Calendar.getInstance();
        cutoffCal.add(java.util.Calendar.MONTH, -months);
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.getTime());

        List<EconomicHistoryEntry> rows = YieldDatabase.getInstance(ctx)
                .economicHistoryDao().getSeriesSync(seriesId);
        if (rows == null || rows.isEmpty())
            return "No cached data for " + seriesId + " yet — do not retry; answer from the "
                    + "data snapshot in the system prompt and say the history isn't loaded.";

        StringBuilder sb = new StringBuilder();
        String seriesName = rows.get(0).seriesName;
        sb.append(seriesName).append(" (").append(seriesId).append("), last ")
          .append(months).append(" months:\n");
        int count = 0;
        for (EconomicHistoryEntry e : rows) {
            if (e.date.compareTo(cutoff) < 0) continue;
            sb.append(e.date).append(": ")
              .append(String.format(Locale.US, "%.2f", e.value)).append(e.unit).append("\n");
            count++;
        }
        return count == 0 ? "No data points in the requested window." : sb.toString();
    }

    /** Live ViewModel-backed series (everything else the app charts). */
    private static String getLiveSeries(String seriesId, int months) {
        LiveSeriesProvider p = liveSeriesProvider;
        List<EconomicDataPoint> rows = p != null ? p.get(seriesId) : null;
        if (rows == null || rows.isEmpty())
            return "No data loaded for " + seriesId + " right now — do not retry; answer from "
                    + "the data snapshot in the system prompt and say the history isn't available.";

        // Date-window filter. Dates are "yyyy-MM-dd" or "yyyy-MM" strings, so a
        // lexicographic compare against a cutoff works; unparseable dates pass.
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.MONTH, -months);
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        List<EconomicDataPoint> window = new ArrayList<>();
        for (EconomicDataPoint dp : rows) {
            String d = dp.getDate();
            if (d == null || d.length() < 7) { window.add(dp); continue; }
            String norm = d.length() == 7 ? d + "-28" : d;
            if (norm.compareTo(cutoff) >= 0) window.add(dp);
        }
        if (window.isEmpty())
            window = rows.subList(Math.max(0, rows.size() - months), rows.size());

        // Daily series would be hundreds of points — sample down to keep the
        // tool result a reasonable size for the model.
        int maxPoints = 40;
        int step = Math.max(1, (int) Math.ceil(window.size() / (double) maxPoints));

        StringBuilder sb = new StringBuilder();
        sb.append(LIVE_SERIES.get(seriesId)).append(" (").append(seriesId).append("), last ")
          .append(months).append(" months, ").append(window.size()).append(" points")
          .append(step > 1 ? " (every " + step + getOrdinalSuffix(step) + " point shown)" : "")
          .append(":\n");
        for (int i = 0; i < window.size(); i++) {
            if (i % step != 0 && i != window.size() - 1) continue;
            EconomicDataPoint dp = window.get(i);
            sb.append(dp.getDate()).append(": ")
              .append(String.format(Locale.US, "%.2f", dp.getValue()));
            if (dp.getUnit() != null && !dp.getUnit().isEmpty())
                sb.append(" ").append(dp.getUnit());
            sb.append("\n");
        }
        // TICKET-4 (AI Laws 15, 16): pre-compute the trailing YoY for index-level
        // series from the FULL row list (never the sampled window), so the model
        // reports a guaranteed-correct number instead of deriving one from points
        // the down-sampler may not have kept 12 months apart.
        if ("CPI".equals(seriesId) || "PCE".equals(seriesId) || "CORE_PCE".equals(seriesId)) {
            sb.append("(These are index LEVELS, not percentages.)\n");
            boolean yoyAppended = false;
            if (rows.size() >= 13) {
                EconomicDataPoint latest = rows.get(rows.size() - 1);
                EconomicDataPoint anchor = rows.get(rows.size() - 13);
                if (Math.abs(anchor.getValue()) > 1e-9) {
                    double yoy = ((latest.getValue() - anchor.getValue())
                            / anchor.getValue()) * 100.0;
                    sb.append(String.format(Locale.US,
                            "Trailing YoY change (pre-computed, %s -> %s): %+.2f%% — use this "
                            + "figure directly; do not derive YoY from the sampled points above.\n",
                            anchor.getDate(), latest.getDate(), yoy));
                    yoyAppended = true;
                }
            }
            if (!yoyAppended)
                sb.append("(Fewer than 13 monthly points loaded — YoY is not computable; "
                        + "describe the level trend only and say the YoY figure isn't available.)\n");
        }
        return sb.toString();
    }

    private static String getOrdinalSuffix(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return "th";
        switch (n % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }

    private static String getHeadlines(JSONObject input) {
        String topic = input.optString("topic", "").toLowerCase(Locale.US).trim();
        int count = Math.max(1, Math.min(25, input.optInt("count", 10)));

        List<NewsItem> items = NewsRepository.getInstance().getCachedItems();
        if (items == null || items.isEmpty())
            return "No cached headlines available right now.";

        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d", Locale.US);
        int added = 0;
        // High-impact first (tier 2 → 0), matching the app's own ranking.
        for (int tier = 2; tier >= 0 && added < count; tier--) {
            for (NewsItem item : items) {
                if (added >= count) break;
                if (item.impactLevel != tier || item.title == null) continue;
                if (!topic.isEmpty()) {
                    String hay = (item.title + " " + (item.summary == null ? "" : item.summary)
                            + " " + (item.tag == null ? "" : item.tag)).toLowerCase(Locale.US);
                    if (!hay.contains(topic)) continue;
                }
                sb.append("[").append(item.source).append(", ")
                  .append(fmt.format(new Date(item.pubDateMillis))).append("] ")
                  .append(item.title);
                if (item.summary != null && !item.summary.isEmpty()) {
                    String sum = item.summary.length() > 200
                            ? item.summary.substring(0, 200) + "…" : item.summary;
                    sb.append(" — ").append(sum);
                }
                sb.append("\n");
                added++;
            }
        }
        return added == 0 ? "No cached headlines match '" + topic + "'." : sb.toString();
    }
}
