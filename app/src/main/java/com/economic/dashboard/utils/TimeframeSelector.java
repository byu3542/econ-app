package com.economic.dashboard.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;

/**
 * Controller for {@code view_timeframe_selector.xml} (TICKET-11).
 *
 * Wires the segmented control's buttons, highlights the active range, persists
 * the choice per chart, and notifies the caller so it can rebuild that chart.
 * Works purely off each segment's {@code android:tag} (the month window), so
 * the same control can be included above several charts on one screen without
 * id collisions.
 */
public final class TimeframeSelector {

    /** Fired when the user picks a new range; {@code months} is the window. */
    public interface OnTimeframeChange {
        void onMonths(int months);
    }

    private TimeframeSelector() {}

    /**
     * @param selector the included control's root LinearLayout
     * @param chartId  stable per-chart slug used for persistence (e.g. "gdp")
     * @param cb       invoked immediately with the remembered range, then again
     *                 on each user change
     */
    public static void attach(LinearLayout selector, String chartId, OnTimeframeChange cb) {
        if (selector == null) return;
        Context ctx = selector.getContext();
        int active = SettingsManager.getChartTimeframeMonths(ctx, chartId);

        for (int i = 0; i < selector.getChildCount(); i++) {
            View child = selector.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            final TextView seg = (TextView) child;
            final int months = parseTag(seg.getTag());
            if (months <= 0) continue;
            seg.setOnClickListener(v -> {
                SettingsManager.setChartTimeframeMonths(ctx, chartId, months);
                restyle(selector, months);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                if (cb != null) cb.onMonths(months);
            });
        }
        restyle(selector, active);
        if (cb != null) cb.onMonths(active);
    }

    /** Paints the selected segment as a gold pill; others stay quiet. */
    private static void restyle(LinearLayout selector, int activeMonths) {
        Context ctx = selector.getContext();
        int gold      = ContextCompat.getColor(ctx, R.color.accent_gold);
        int navy      = ContextCompat.getColor(ctx, R.color.header_navy);
        int inactive  = ContextCompat.getColor(ctx, R.color.text_secondary);
        float density = ctx.getResources().getDisplayMetrics().density;

        for (int i = 0; i < selector.getChildCount(); i++) {
            View child = selector.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView seg = (TextView) child;
            boolean on = parseTag(seg.getTag()) == activeMonths;
            if (on) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(gold);
                pill.setCornerRadius(8f * density);
                seg.setBackground(pill);
                seg.setTextColor(navy);
            } else {
                seg.setBackground(null);
                seg.setTextColor(inactive);
            }
        }
    }

    private static int parseTag(Object tag) {
        if (tag == null) return -1;
        try { return Integer.parseInt(tag.toString()); }
        catch (NumberFormatException e) { return -1; }
    }
}
