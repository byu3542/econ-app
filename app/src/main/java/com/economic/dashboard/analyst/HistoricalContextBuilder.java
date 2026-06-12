package com.economic.dashboard.analyst;

import android.content.Context;
import android.util.Log;

import com.economic.dashboard.api.HistoricalDataRepository;
import com.economic.dashboard.database.EconomicHistoryDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.EconomicHistoryEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads cached economic_history rows from Room and formats a compact
 * historical-trend block for injection into the AI Analyst system prompt.
 *
 * Example output (appended after the live snapshot):
 *
 *   HISTORICAL TRENDS — 2-Year Window
 *   ──────────────────────────────────
 *   TREASURY YIELDS (weekly FRED data)
 *     10Y: Range 3.51–5.02%, 2yr avg 4.32%, Latest 4.25% (2025-05-16) ▼ declining
 *     2Y:  Range 3.75–5.27%, 2yr avg 4.48%, Latest 4.10% (2025-05-16) ▼ declining
 *     3M:  Range 4.28–5.54%, 2yr avg 4.92%, Latest 4.30% (2025-05-16) ▼ declining
 *
 *   MORTGAGE RATES (weekly FRED data)
 *     30-Yr Fixed: Range 6.12–7.79%, 2yr avg 6.95%, Latest 6.85% (2025-05-15) ▼ declining
 *
 *   UNEMPLOYMENT RATE (monthly BLS data)
 *     Range 3.4–4.2%, Latest 4.1% (2025-04-01) ↑ rising
 *
 *   GDP GROWTH — QoQ Annualized (BEA quarterly)
 *     Q2'23 2.1% | Q3'23 4.9% | Q4'23 3.3% | Q1'24 1.6% | Q2'24 3.0% | Q3'24 2.8% | Q4'24 2.3% | Q1'25 -0.3%
 *     2yr avg: 2.5%
 *
 * All queries are synchronous — call this method only from a background thread.
 */
public class HistoricalContextBuilder {

    private static final String TAG = "HistoricalContextBuilder";

    /**
     * Queries Room synchronously and returns the formatted trend block.
     * Returns an empty string if the cache is empty.
     *
     * Must be called from a background thread.
     */
    public static String build(Context context) {
        try {
            EconomicHistoryDao dao = YieldDatabase.getInstance(context).economicHistoryDao();
            if (dao.getCount() == 0) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("\n\nHISTORICAL TRENDS — 2-Year Window\n");
            sb.append("──────────────────────────────────\n");

            // ── Treasury yields ───────────────────────────────────────────────
            sb.append("TREASURY YIELDS (weekly FRED data)\n");
            appendYieldLine(sb, dao, HistoricalDataRepository.SERIES_10Y_TREASURY, "10Y");
            appendYieldLine(sb, dao, HistoricalDataRepository.SERIES_2Y_TREASURY,  "2Y ");
            appendYieldLine(sb, dao, HistoricalDataRepository.SERIES_3M_TREASURY,  "3M ");

            // ── Mortgage rates ────────────────────────────────────────────────
            sb.append("\nMORTGAGE RATES (weekly FRED data)\n");
            appendYieldLine(sb, dao, HistoricalDataRepository.SERIES_MORTGAGE30, "30-Yr Fixed");

            // ── Unemployment rate ─────────────────────────────────────────────
            sb.append("\nUNEMPLOYMENT RATE (monthly BLS data)\n");
            appendUnemploymentBlock(sb, dao);

            // ── GDP growth ────────────────────────────────────────────────────
            sb.append("\nGDP GROWTH — QoQ Annualized % (BEA quarterly)\n");
            appendGdpBlock(sb, dao);

            // ── Full series detail (monthly resolution) ──────────────────────
            // Gives the AI the actual time series, not just summary stats, so it
            // can answer questions like "what was the 10Y yield last September?"
            sb.append("\nFULL SERIES DETAIL — month-by-month, 24-month window");
            sb.append(" (weekly series show the last reading of each month)\n");
            appendMonthlySeries(sb, dao, HistoricalDataRepository.SERIES_10Y_TREASURY, "10Y Treasury %");
            appendMonthlySeries(sb, dao, HistoricalDataRepository.SERIES_2Y_TREASURY,  "2Y Treasury %");
            appendMonthlySeries(sb, dao, HistoricalDataRepository.SERIES_3M_TREASURY,  "3M T-Bill %");
            appendMonthlySeries(sb, dao, HistoricalDataRepository.SERIES_MORTGAGE30,   "30-Yr Mortgage %");
            appendMonthlySeries(sb, dao, HistoricalDataRepository.SERIES_UNEMPLOYMENT, "Unemployment %");

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "Failed to build historical context", e);
            return "";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Section builders
    // ──────────────────────────────────────────────────────────────────────────

    /** Formats one yield/rate line: label, range, avg, latest, trend arrow. */
    private static void appendYieldLine(StringBuilder sb, EconomicHistoryDao dao,
                                         String seriesId, String label) {
        List<EconomicHistoryEntry> rows = dao.getSeriesSync(seriesId);
        if (rows == null || rows.isEmpty()) {
            sb.append("  ").append(label).append(": Unavailable\n");
            return;
        }

        double min  = Double.MAX_VALUE;
        double max  = Double.MIN_VALUE;
        double sum  = 0;
        for (EconomicHistoryEntry e : rows) {
            if (e.value < min) min = e.value;
            if (e.value > max) max = e.value;
            sum += e.value;
        }
        double avg    = sum / rows.size();
        EconomicHistoryEntry latest = rows.get(rows.size() - 1);
        String trend  = trendArrow(rows);

        sb.append(String.format(Locale.US,
                "  %s: Range %.2f–%.2f%%, 2yr avg %.2f%%, Latest %.2f%% (%s) %s\n",
                label, min, max, avg, latest.value, latest.date, trend));
    }

    /** Formats the unemployment block (similar to yield line but no %). */
    private static void appendUnemploymentBlock(StringBuilder sb, EconomicHistoryDao dao) {
        List<EconomicHistoryEntry> rows =
                dao.getSeriesSync(HistoricalDataRepository.SERIES_UNEMPLOYMENT);
        if (rows == null || rows.isEmpty()) {
            sb.append("  Unavailable\n");
            return;
        }

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (EconomicHistoryEntry e : rows) {
            if (e.value < min) min = e.value;
            if (e.value > max) max = e.value;
        }
        EconomicHistoryEntry latest = rows.get(rows.size() - 1);
        String trend = trendArrow(rows);

        sb.append(String.format(Locale.US,
                "  Range %.1f–%.1f%%, Latest %.1f%% (%s) %s\n",
                min, max, latest.value, latest.date, trend));
    }

    /**
     * Formats the GDP block: shows up to the last 8 quarters inline,
     * followed by the 2-year average.
     */
    private static void appendGdpBlock(StringBuilder sb, EconomicHistoryDao dao) {
        List<EconomicHistoryEntry> rows =
                dao.getSeriesSync(HistoricalDataRepository.SERIES_GDP_GROWTH);
        if (rows == null || rows.isEmpty()) {
            sb.append("  Unavailable\n");
            return;
        }

        // Show at most the last 8 quarters
        int start = Math.max(0, rows.size() - 8);
        double sum = 0;
        int count  = 0;
        StringBuilder qSb = new StringBuilder("  ");

        for (int i = start; i < rows.size(); i++) {
            EconomicHistoryEntry e = rows.get(i);
            String qLabel = dateToQuarterLabel(e.date);
            qSb.append(String.format(Locale.US, "%s %.1f%%", qLabel, e.value));
            if (i < rows.size() - 1) qSb.append(" | ");
            sum += e.value;
            count++;
        }
        sb.append(qSb).append("\n");

        if (count > 0) {
            sb.append(String.format(Locale.US,
                    "  2yr avg: %.2f%%\n", sum / count));
        }
    }

    /**
     * Emits one line per series with the last cached value of each month:
     *   "10Y Treasury %: 2024-07 4.25 | 2024-08 3.92 | ... | 2026-06 4.41"
     * Rows arrive sorted ASC by date, so the last value seen for a month wins.
     */
    private static void appendMonthlySeries(StringBuilder sb, EconomicHistoryDao dao,
                                            String seriesId, String label) {
        List<EconomicHistoryEntry> rows = dao.getSeriesSync(seriesId);
        if (rows == null || rows.isEmpty()) return;

        // LinkedHashMap preserves chronological order; later rows overwrite
        // earlier ones within the same month (= last reading of the month).
        Map<String, Double> byMonth = new LinkedHashMap<>();
        for (EconomicHistoryEntry e : rows) {
            if (e.date != null && e.date.length() >= 7) {
                byMonth.put(e.date.substring(0, 7), e.value);
            }
        }
        if (byMonth.isEmpty()) return;

        sb.append(label).append(": ");
        boolean first = true;
        for (Map.Entry<String, Double> m : byMonth.entrySet()) {
            if (!first) sb.append(" | ");
            sb.append(m.getKey()).append(' ')
              .append(String.format(Locale.US, "%.2f", m.getValue()));
            first = false;
        }
        sb.append('\n');
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Compares the last 4 data points to the 4 before them and returns a
     * directional label.  Falls back to "→ stable" for very small moves.
     */
    private static String trendArrow(List<EconomicHistoryEntry> rows) {
        if (rows.size() < 8) {
            // Not enough history — compare first vs last
            double first = rows.get(0).value;
            double last  = rows.get(rows.size() - 1).value;
            double diff  = last - first;
            if (Math.abs(diff) < 0.10) return "→ stable";
            return diff > 0 ? "↑ rising" : "▼ declining";
        }

        // Compare avg of last 4 vs avg of preceding 4
        double recentSum = 0, priorSum = 0;
        int n = rows.size();
        for (int i = n - 4; i < n;       i++) recentSum += rows.get(i).value;
        for (int i = n - 8; i < n - 4;   i++) priorSum  += rows.get(i).value;
        double diff = (recentSum - priorSum) / 4.0;

        if (Math.abs(diff) < 0.10) return "→ stable";
        return diff > 0 ? "↑ rising" : "▼ declining";
    }

    /**
     * Convert "YYYY-MM-01" → "Q#'YY" label, e.g. "2024-07-01" → "Q3'24".
     * Falls back to the raw date on parse error.
     */
    private static String dateToQuarterLabel(String date) {
        if (date == null || date.length() < 7) return date;
        try {
            String yearStr  = date.substring(0, 4);
            String monthStr = date.substring(5, 7);
            int month  = Integer.parseInt(monthStr);
            int quarter = (month - 1) / 3 + 1;
            String yy  = yearStr.substring(2);
            return "Q" + quarter + "'" + yy;
        } catch (Exception e) {
            return date;
        }
    }
}
