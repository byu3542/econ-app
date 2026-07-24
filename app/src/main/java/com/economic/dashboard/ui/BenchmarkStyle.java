package com.economic.dashboard.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;
import com.economic.dashboard.utils.SettingsManager;

/**
 * Single source of truth for how a status/benchmark badge is coloured.
 *
 * <p><b>The ordered severity scale (TICKET-02).</b> Every badge in the app maps to exactly one
 * {@link Tier}, and each tier owns exactly one colour meaning app-wide:
 *
 * <pre>
 *   GOOD        green   (#6FA97A)  healthy / on-target / calm  -- the ONLY "good" tier
 *   WATCH       yellow  (#DCC873)  mild concern, either direction
 *   ELEVATED    orange  (#D98E4F)  elevated concern
 *   ALERT       red     (#C75B4E)  critical
 *   ALERT_DEEP  d-red   (#8E3B32)  most severe
 *   IMBALANCE   purple  (#8A6E9E)  "too much of a good thing" -- GDP overheating,
 *                                  extreme curve steepness. NEVER reused for "best".
 * </pre>
 *
 * Blue is intentionally NOT a tier: it previously meant both "deflation risk" (bad) and
 * "very strong" (better-than-green), so it was dropped. Green never means both "best" and
 * "middle" -- growth metrics collapse STRONG/VERY STRONG onto {@code GOOD}.
 *
 * <p>Colours are read from {@code colors.xml} via resources (never {@code Color.parseColor}),
 * so badges re-theme under {@code values-night} and can swap to a colourblind-safe set.
 */
public final class BenchmarkStyle {

    private BenchmarkStyle() {}

    public enum Tier { GOOD, WATCH, ELEVATED, ALERT, ALERT_DEEP, IMBALANCE }

    /** @return the ARGB colour for {@code tier}, honouring colourblind mode. */
    public static int colorFor(Context context, Tier tier) {
        boolean cb = SettingsManager.getBool(context, SettingsManager.KEY_COLORBLIND, false);
        int res;
        switch (tier) {
            case GOOD:       res = cb ? R.color.signal_good_cb       : R.color.signal_good;       break;
            case WATCH:      res = cb ? R.color.signal_watch_cb      : R.color.signal_watch;      break;
            case ELEVATED:   res = cb ? R.color.signal_elevated_cb   : R.color.signal_elevated;   break;
            case ALERT:      res = cb ? R.color.signal_alert_cb      : R.color.signal_alert;      break;
            case ALERT_DEEP: res = cb ? R.color.signal_alert_deep_cb : R.color.signal_alert_deep; break;
            case IMBALANCE:  res = cb ? R.color.signal_imbalance_cb  : R.color.signal_imbalance;  break;
            default:         res = cb ? R.color.signal_good_cb       : R.color.signal_good;       break;
        }
        return ContextCompat.getColor(context, res);
    }

    /** Render a status dot as a filled oval in the tier colour. Centralises dot drawing. */
    public static void tintDot(View dot, Context context, Tier tier) {
        if (dot == null || context == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(colorFor(context, tier));
        dot.setBackground(gd);
    }

    /**
     * Apply a benchmark result to a status row: colour the dot and set the status word.
     * The dot view + status TextView are looked up by the given ids on {@code card}.
     */
    public static void apply(View card, Context context, Benchmarks.Result r,
                             int dotId, int statusTextId) {
        if (card == null || r == null) return;
        tintDot(card.findViewById(dotId), context, r.tier);
        TextView tv = card.findViewById(statusTextId);
        if (tv != null) tv.setText(r.word);
    }
}
