package com.economic.dashboard.analyst;

import android.content.Context;

import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.news.NewsItem;
import com.economic.dashboard.news.NewsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Server side of the AI Analyst's tool calls, executed on-device.
 *
 * When Claude's response stops with stop_reason "tool_use", the app runs the
 * requested tool here (against the Room cache / news cache) and sends the
 * result back as a tool_result block. Never call from the main thread —
 * get_series runs synchronous Room queries.
 */
public class AnalystToolExecutor {

    /** Valid series ids the model may request (matches HistoricalDataRepository). */
    public static final String[] SERIES_IDS = {
            "DGS10", "DGS2", "DGS3MO", "MORTGAGE30US", "LNS14000000", "GDP_BEA_T10101"
    };

    /** JSON "tools" array for the Anthropic request body. */
    public static JSONArray buildToolsJson() throws Exception {
        JSONArray tools = new JSONArray();

        JSONObject seriesParam = new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray()
                        .put("DGS10").put("DGS2").put("DGS3MO")
                        .put("MORTGAGE30US").put("LNS14000000").put("GDP_BEA_T10101"))
                .put("description", "Series to fetch: DGS10=10Y Treasury, DGS2=2Y Treasury, "
                        + "DGS3MO=3M T-Bill, MORTGAGE30US=30Y mortgage rate, "
                        + "LNS14000000=unemployment rate, GDP_BEA_T10101=GDP growth QoQ annualized");
        JSONObject monthsParam = new JSONObject()
                .put("type", "integer")
                .put("description", "How many months of history to return (1-24). Default 24.");
        tools.put(new JSONObject()
                .put("name", "get_series")
                .put("description", "Fetch up to 24 months of cached historical data points for one "
                        + "economic series. Use when the user asks about specific past values, "
                        + "turning points, or trends the summary block doesn't cover.")
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
        boolean valid = false;
        for (String s : SERIES_IDS) if (s.equals(seriesId)) { valid = true; break; }
        if (!valid) return "Invalid series_id: " + seriesId;

        int months = Math.max(1, Math.min(24, input.optInt("months", 24)));
        java.util.Calendar cutoffCal = java.util.Calendar.getInstance();
        cutoffCal.add(java.util.Calendar.MONTH, -months);
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.getTime());

        List<EconomicHistoryEntry> rows = YieldDatabase.getInstance(ctx)
                .economicHistoryDao().getSeriesSync(seriesId);
        if (rows == null || rows.isEmpty())
            return "No cached data for " + seriesId + ". The cache may still be populating.";

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
