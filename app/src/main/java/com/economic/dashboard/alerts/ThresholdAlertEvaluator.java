package com.economic.dashboard.alerts;

import android.content.Context;

import com.economic.dashboard.utils.NotificationHelper;
import com.economic.dashboard.utils.SettingsManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TICKET-24: evaluates the user's threshold alert rules against the latest
 * fetched values and fires notifications through {@link NotificationHelper}.
 *
 * Called from the refresh path (EconomicViewModel, after all fetches settle).
 * Fires on *crossings* — a rule that is already past its threshold does not
 * re-notify on every refresh; it must reset below/above and cross again.
 * Evaluation state is persisted back into the rule storage.
 */
public final class ThresholdAlertEvaluator {

    /** Notification id base — keeps alert ids clear of the daily-brief ids. */
    private static final int NOTIF_ID_BASE = 3000;

    private ThresholdAlertEvaluator() {}

    /**
     * @param latest map of seriesKey → latest value for every series that
     *               refreshed successfully this cycle. Missing keys are skipped
     *               (a failed fetch must not fire or reset an alert).
     */
    public static void evaluate(Context ctx, Map<String, Double> latest) {
        if (ctx == null || latest == null || latest.isEmpty()) return;
        List<AlertRule> rules = SettingsManager.getAlertRules(ctx);
        if (rules.isEmpty()) return;

        boolean dirty = false;
        for (int i = 0; i < rules.size(); i++) {
            AlertRule r = rules.get(i);
            Double value = latest.get(r.seriesKey);
            if (value == null) continue;

            switch (r.op == null ? "" : r.op) {
                case AlertRule.OP_ABOVE: {
                    boolean now = value > r.threshold;
                    if (now && !r.lastState) fire(ctx, i, r, value);
                    if (now != r.lastState) { r.lastState = now; dirty = true; }
                    break;
                }
                case AlertRule.OP_BELOW: {
                    boolean now = value < r.threshold;
                    if (now && !r.lastState) fire(ctx, i, r, value);
                    if (now != r.lastState) { r.lastState = now; dirty = true; }
                    break;
                }
                case AlertRule.OP_CHANGE: {
                    if (r.lastValue != null && Math.abs(value - r.lastValue) > 1e-9) {
                        fire(ctx, i, r, value);
                    }
                    if (r.lastValue == null || Math.abs(value - r.lastValue) > 1e-9) {
                        r.lastValue = value; dirty = true;
                    }
                    break;
                }
                default: break;
            }
        }
        if (dirty) SettingsManager.saveAlertRules(ctx, rules);
    }

    private static void fire(Context ctx, int index, AlertRule r, double value) {
        String title = r.label + " alert";
        String text;
        switch (r.op) {
            case AlertRule.OP_ABOVE:
                text = String.format(Locale.US, "%s is now %.2f — above your %.2f threshold.",
                        r.label, value, r.threshold);
                break;
            case AlertRule.OP_BELOW:
                text = String.format(Locale.US, "%s is now %.2f — below your %.2f threshold.",
                        r.label, value, r.threshold);
                break;
            default:
                text = String.format(Locale.US, "%s changed to %.2f.", r.label, value);
                break;
        }
        NotificationHelper.notify(ctx, NOTIF_ID_BASE + index, title, text);
    }
}
