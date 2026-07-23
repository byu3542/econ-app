package com.economic.dashboard.analyst;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.economic.dashboard.ui.AiAnalystBottomSheet;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import java.util.Locale;

/** Shared entry points into the AI Analyst from anywhere in the app. */
public class AskAnalyst {

    /** Opens the analyst sheet with a query; reuses an open sheet if present. */
    public static void openWithQuery(FragmentActivity activity, String query) {
        openWithQuery(activity, query, "", false);
    }

    /**
     * Opens the analyst sheet with a query, an optional screen-context block for
     * the system prompt, and a concise-mode flag for one-tap gesture queries
     * (AI Laws 7, 8, 12: gestures inject live values and get shorter answers).
     */
    public static void openWithQuery(FragmentActivity activity, String query,
                                     String screenContext, boolean concise) {
        if (activity == null || query == null || query.trim().isEmpty()) return;
        FragmentManager fm = activity.getSupportFragmentManager();
        androidx.fragment.app.Fragment existing = fm.findFragmentByTag(AiAnalystBottomSheet.TAG);
        if (existing instanceof AiAnalystBottomSheet && existing.isAdded()) {
            ((AiAnalystBottomSheet) existing).submitExternalQuery(query, screenContext, concise);
        } else {
            AiAnalystBottomSheet.newInstanceForGesture(query, screenContext, concise)
                    .show(fm, AiAnalystBottomSheet.TAG);
        }
    }

    /**
     * Long-press on a metric card → confirmation menu → ask the AI Analyst.
     * Mirrors the Overview screen's behavior so every card in the app works
     * the same way.
     *
     * AI Law 8: the card already shows the value the user is asking about, so
     * harvest its on-screen text at press time and hand it to the model inline.
     * A model that's been given the number has no reason to announce a lookup —
     * this closes the "it says it'll fetch data then ends" path at its source.
     */
    public static void wireCardLongPress(View card, FragmentActivity activity, String label) {
        if (card == null || activity == null) return;
        card.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(v.getContext(), v);
            menu.getMenu().add(0, 1, 0, "Ask AI Analyst");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    // Harvest at press time, not wire time — values load async.
                    String cardText = harvestCardText(card);
                    String query;
                    String context = "";
                    if (!cardText.isEmpty()) {
                        // AI Law 7: one focused ask, not a three-part essay prompt.
                        query = "The dashboard card for " + label + " currently shows: "
                                + cardText + ". What does this reading mean?";
                        context = "\nSCREEN CONTEXT: The user long-pressed the " + label
                                + " card, which currently shows: " + cardText
                                + ". These are the live on-screen values — use them "
                                + "directly and do not fetch them again.\n";
                    } else {
                        query = "What does the current reading for " + label
                                + " on the dashboard mean?";
                    }
                    openWithQuery(activity, query, context, true);
                    return true;
                }
                return false;
            });
            menu.show();
            return true;
        });
    }

    /**
     * Long-press on any MPAndroidChart → "Explain this chart" menu.
     * Uses the chart's own gesture listener so it doesn't fight pan/zoom.
     *
     * AI Law 8: the chart's latest value and range are read off the chart data
     * at press time and injected, so the model never needs to fetch what the
     * screen already shows.
     *
     * @param description e.g. "the 10-year Treasury yield over the past 24 months"
     */
    public static void attachChartExplain(LineChart chart, FragmentActivity activity, String description) {
        if (chart == null || activity == null) return;
        chart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartLongPressed(MotionEvent me) {
                PopupMenu menu = new PopupMenu(chart.getContext(), chart);
                menu.getMenu().add(0, 1, 0, "✨ Explain this chart");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        String stats = describeChartData(chart);
                        String query = "Explain the chart showing " + description
                                + ". What's the trend and what's driving it?";
                        String context = stats.isEmpty() ? "" :
                                "\nSCREEN CONTEXT: The user long-pressed a chart showing "
                                + description + ". On-screen chart data: " + stats
                                + ". Use these values directly and do not fetch them again.\n";
                        openWithQuery(activity, query, context, true);
                        return true;
                    }
                    return false;
                });
                menu.show();
            }
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture g) {
                // TICKET-23: clear the crosshair marker when a scrub gesture ends.
                chart.highlightValue(null);
            }
            @Override public void onChartDoubleTapped(MotionEvent me) {}
            @Override public void onChartSingleTapped(MotionEvent me) {}
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float vX, float vY) {}
            @Override public void onChartScale(MotionEvent me, float sX, float sY) {}
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
        });
    }

    /** Collects the visible text a metric card is showing (label, value, delta, date). */
    private static String harvestCardText(View card) {
        StringBuilder sb = new StringBuilder();
        collectText(card, sb);
        String s = sb.toString().trim();
        return s.endsWith("·") ? s.substring(0, s.length() - 1).trim() : s;
    }

    private static void collectText(View v, StringBuilder sb) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t.toString().trim());
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), sb);
        }
    }

    /** Latest value + range for each dataset currently plotted on the chart. */
    private static String describeChartData(LineChart chart) {
        try {
            LineData data = chart.getData();
            if (data == null || data.getDataSetCount() == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (ILineDataSet ds : data.getDataSets()) {
                int n = ds.getEntryCount();
                if (n == 0) continue;
                Entry last = ds.getEntryForIndex(n - 1);
                if (sb.length() > 0) sb.append("; ");
                String label = ds.getLabel();
                sb.append(label == null || label.isEmpty() ? "series" : label)
                  .append(String.format(Locale.US,
                          " — latest %.2f, range %.2f to %.2f across %d points",
                          last.getY(), ds.getYMin(), ds.getYMax(), n));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
