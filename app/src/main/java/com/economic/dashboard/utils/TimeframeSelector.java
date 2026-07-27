package com.economic.dashboard.utils;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;
import com.economic.dashboard.ui.views.SegmentedControl;

/**
 * Controller for {@code view_timeframe_selector.xml} (TICKET-11 / IOS-05).
 *
 * Wires the segmented control's buttons, springs a sliding thumb to the
 * active range (SegmentedControl), persists the choice per chart, and
 * notifies the caller so it can rebuild that chart. Works purely off each
 * segment's {@code android:tag} (the month window), so the same control can
 * be included above several charts on one screen without id collisions.
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
        // IOS-05: the sliding thumb is a sibling in view_timeframe_selector.xml's
        // wrapping FrameLayout, not a child of the segment row itself.
        View thumb = (selector.getParent() instanceof View)
                ? ((View) selector.getParent()).findViewById(R.id.timeframeThumb) : null;

        for (int i = 0; i < selector.getChildCount(); i++) {
            View child = selector.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            final TextView seg = (TextView) child;
            final int months = parseTag(seg.getTag());
            if (months <= 0) continue;
            seg.setOnClickListener(v -> {
                SettingsManager.setChartTimeframeMonths(ctx, chartId, months);
                restyle(selector, thumb, months, true);
                HapticUtil.selection(v); // IOS-09
                if (cb != null) cb.onMonths(months);
            });
        }
        restyle(selector, thumb, active, false);
        if (cb != null) cb.onMonths(active);
    }

    /**
     * Text-colors the active segment and springs the thumb under it.
     * {@code animate=false} (first attach) snaps instead — segment widths
     * aren't known until layout, so the snap runs in a post().
     */
    private static void restyle(LinearLayout selector, View thumb, int activeMonths, boolean animate) {
        Context ctx = selector.getContext();
        int navy      = ContextCompat.getColor(ctx, R.color.header_navy);
        int inactive  = ContextCompat.getColor(ctx, R.color.text_secondary);
        TextView activeSeg = null;

        for (int i = 0; i < selector.getChildCount(); i++) {
            View child = selector.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView seg = (TextView) child;
            boolean on = parseTag(seg.getTag()) == activeMonths;
            seg.setTextColor(on ? navy : inactive);
            if (on) activeSeg = seg;
        }

        if (thumb == null || activeSeg == null) return;
        final TextView target = activeSeg;
        if (animate) {
            SegmentedControl.slideTo(thumb, target);
        } else {
            selector.post(() -> SegmentedControl.snapTo(thumb, target));
        }
    }

    private static int parseTag(Object tag) {
        if (tag == null) return -1;
        try { return Integer.parseInt(tag.toString()); }
        catch (NumberFormatException e) { return -1; }
    }
}
