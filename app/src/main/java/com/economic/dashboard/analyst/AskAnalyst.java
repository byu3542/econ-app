package com.economic.dashboard.analyst;

import android.view.View;
import android.widget.PopupMenu;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.economic.dashboard.ui.AiAnalystBottomSheet;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import android.view.MotionEvent;

/** Shared entry points into the AI Analyst from anywhere in the app. */
public class AskAnalyst {

    /** Opens the analyst sheet with a query; reuses an open sheet if present. */
    public static void openWithQuery(FragmentActivity activity, String query) {
        if (activity == null || query == null || query.trim().isEmpty()) return;
        FragmentManager fm = activity.getSupportFragmentManager();
        androidx.fragment.app.Fragment existing = fm.findFragmentByTag(AiAnalystBottomSheet.TAG);
        if (existing instanceof AiAnalystBottomSheet && existing.isAdded()) {
            ((AiAnalystBottomSheet) existing).submitExternalQuery(query);
        } else {
            AiAnalystBottomSheet.newInstance(query).show(fm, AiAnalystBottomSheet.TAG);
        }
    }

    /**
     * Long-press on a metric card → confirmation menu → ask the AI Analyst.
     * Mirrors the Overview screen's behavior so every card in the app works
     * the same way.
     */
    public static void wireCardLongPress(View card, FragmentActivity activity, String label) {
        if (card == null || activity == null) return;
        card.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(v.getContext(), v);
            menu.getMenu().add(0, 1, 0, "Ask AI Analyst");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    openWithQuery(activity, "Tell me about the current reading for " + label
                            + " shown on the dashboard. What's driving it and what does it mean for the economy?");
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
                        openWithQuery(activity, "Explain the chart showing " + description
                                + ". Summarize the trend, what's been driving it, and what to watch next.");
                        return true;
                    }
                    return false;
                });
                menu.show();
            }
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartDoubleTapped(MotionEvent me) {}
            @Override public void onChartSingleTapped(MotionEvent me) {}
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float vX, float vY) {}
            @Override public void onChartScale(MotionEvent me, float sX, float sY) {}
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
        });
    }
}
