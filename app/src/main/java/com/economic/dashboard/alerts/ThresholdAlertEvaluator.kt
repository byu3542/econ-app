package com.economic.dashboard.alerts

import android.content.Context

import com.economic.dashboard.utils.NotificationHelper
import com.economic.dashboard.utils.SettingsManager

import java.util.Locale

/**
 * TICKET-24: evaluates the user's threshold alert rules against the latest
 * fetched values and fires notifications through [NotificationHelper].
 *
 * Called from the refresh path (EconomicViewModel, after all fetches settle).
 * Fires on *crossings* — a rule that is already past its threshold does not
 * re-notify on every refresh; it must reset below/above and cross again.
 * Evaluation state is persisted back into the rule storage.
 */
object ThresholdAlertEvaluator {

    /** Notification id base — keeps alert ids clear of the daily-brief ids. */
    private const val NOTIF_ID_BASE = 3000

    /**
     * @param latest map of seriesKey → latest value for every series that
     *               refreshed successfully this cycle. Missing keys are skipped
     *               (a failed fetch must not fire or reset an alert).
     */
    @JvmStatic
    fun evaluate(ctx: Context?, latest: Map<String, Double>?) {
        if (ctx == null || latest == null || latest.isEmpty()) return
        val rules = SettingsManager.getAlertRules(ctx)
        if (rules.isEmpty()) return

        var dirty = false
        for (i in rules.indices) {
            val r = rules[i]
            // The key is captured and null-checked before the lookup rather than
            // indexing the map with a `String?`. Whether `latest[r.seriesKey]`
            // resolves (member get takes a non-null String; the stdlib
            // `Map<out K, V>.get` extension would have to pick it up) is subtle
            // enough that it is not worth relying on in a batch that cannot be
            // compiled here. Behaviour is identical: the Java's
            // `latest.get(null)` returned null for these maps, which only ever
            // receive non-null constant keys, so both paths skip the rule.
            val key = r.seriesKey ?: continue
            val value = latest[key] ?: continue

            when (r.op ?: "") {
                AlertRule.OP_ABOVE -> {
                    val now = value > r.threshold
                    if (now && !r.lastState) fire(ctx, i, r, value)
                    if (now != r.lastState) { r.lastState = now; dirty = true }
                }
                AlertRule.OP_BELOW -> {
                    val now = value < r.threshold
                    if (now && !r.lastState) fire(ctx, i, r, value)
                    if (now != r.lastState) { r.lastState = now; dirty = true }
                }
                AlertRule.OP_CHANGE -> {
                    // Captured into a local val: `lastValue` is a mutable
                    // property on another class, which Kotlin will not smart-cast
                    // across statements (migration lesson 6).
                    val last = r.lastValue
                    if (last != null && Math.abs(value - last) > 1e-9) {
                        fire(ctx, i, r, value)
                    }
                    if (last == null || Math.abs(value - last) > 1e-9) {
                        r.lastValue = value; dirty = true
                    }
                }
                else -> {}
            }
        }
        if (dirty) SettingsManager.saveAlertRules(ctx, rules)
    }

    private fun fire(ctx: Context, index: Int, r: AlertRule, value: Double) {
        val title = r.label + " alert"
        val text: String = when (r.op) {
            AlertRule.OP_ABOVE ->
                String.format(Locale.US, "%s is now %.2f — above your %.2f threshold.",
                    r.label, value, r.threshold)
            AlertRule.OP_BELOW ->
                String.format(Locale.US, "%s is now %.2f — below your %.2f threshold.",
                    r.label, value, r.threshold)
            else ->
                String.format(Locale.US, "%s changed to %.2f.", r.label, value)
        }
        NotificationHelper.notify(ctx, NOTIF_ID_BASE + index, title, text)
    }
}
