package com.economic.dashboard.alerts;

/**
 * TICKET-24: a user-configured metric threshold alert.
 *
 * Serialized to JSON (Gson) inside SharedPreferences via
 * {@link com.economic.dashboard.utils.SettingsManager#getAlertRules}. Keep the
 * field names stable — they are the storage schema.
 */
public class AlertRule {

    /** Rule operators. */
    public static final String OP_ABOVE  = "above";   // fire when value > threshold
    public static final String OP_BELOW  = "below";   // fire when value < threshold
    public static final String OP_CHANGE = "change";  // fire on any value change

    /** Series key from {@link com.economic.dashboard.ui.EconomicViewModel} constants. */
    public String seriesKey;
    /** Human label used in the notification, e.g. "Fed Funds". */
    public String label;
    /** One of OP_ABOVE / OP_BELOW / OP_CHANGE. */
    public String op;
    /** Threshold value; ignored for OP_CHANGE. */
    public double threshold;

    // ── Evaluation state (persisted so we fire on crossings, not every refresh) ──
    /** Whether the condition held at the last evaluation (above/below ops). */
    public boolean lastState = false;
    /** Last observed value (change op); null-ish sentinel = never observed. */
    public Double lastValue = null;

    public AlertRule() {} // Gson

    public AlertRule(String seriesKey, String label, String op, double threshold) {
        this.seriesKey = seriesKey;
        this.label = label;
        this.op = op;
        this.threshold = threshold;
    }

    /** Short human description, e.g. "Fed Funds &gt; 4.50". */
    public String describe() {
        switch (op == null ? "" : op) {
            case OP_ABOVE:  return label + " > " + threshold;
            case OP_BELOW:  return label + " < " + threshold;
            case OP_CHANGE: return label + " changes";
            default:        return label;
        }
    }
}
