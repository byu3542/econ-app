package com.economic.dashboard.alerts

/**
 * TICKET-24: a user-configured metric threshold alert.
 *
 * Serialized to JSON (Gson) inside SharedPreferences via
 * [com.economic.dashboard.utils.SettingsManager.getAlertRules]. Keep the
 * field names stable — they are the storage schema.
 *
 * Every property carries `@JvmField` so the JVM shape stays a plain public
 * field. Two reasons, and they both matter:
 *  1. This is a Gson serialization schema — the class doc above says the field
 *     names ARE the storage format, so keeping real fields removes any question
 *     about reflection behaviour.
 *  2. `MetricBottomSheet.java` reads `r.seriesKey` as a raw field and stays
 *     Java until batch 11b.
 * (This is the opposite call from `NewsItem` in batch 8, which took getters —
 * that class is neither serialized nor read from surviving Java.)
 *
 * Reference types are nullable because Gson can leave any field unset when
 * deserializing older stored JSON.
 */
class AlertRule {

    /** Series key from [com.economic.dashboard.ui.EconomicViewModel] constants. */
    @JvmField var seriesKey: String? = null

    /** Human label used in the notification, e.g. "Fed Funds". */
    @JvmField var label: String? = null

    /** One of OP_ABOVE / OP_BELOW / OP_CHANGE. */
    @JvmField var op: String? = null

    /** Threshold value; ignored for OP_CHANGE. */
    @JvmField var threshold: Double = 0.0

    // ── Evaluation state (persisted so we fire on crossings, not every refresh) ──

    /** Whether the condition held at the last evaluation (above/below ops). */
    @JvmField var lastState: Boolean = false

    /** Last observed value (change op); null-ish sentinel = never observed. */
    @JvmField var lastValue: Double? = null

    constructor() // Gson

    constructor(seriesKey: String?, label: String?, op: String?, threshold: Double) {
        this.seriesKey = seriesKey
        this.label = label
        this.op = op
        this.threshold = threshold
    }

    /**
     * Short human description, e.g. "Fed Funds &gt; 4.50".
     *
     * Return type is nullable because the Java returned a bare `label` on the
     * default branch, and `label` can be null. The one caller
     * (`MetricBottomSheet`) passes it to `StringBuilder.append`, which renders
     * null as "null" — preserved rather than "fixed" to "".
     */
    fun describe(): String? {
        return when (op ?: "") {
            OP_ABOVE -> label + " > " + threshold
            OP_BELOW -> label + " < " + threshold
            OP_CHANGE -> label + " changes"
            else -> label
        }
    }

    companion object {
        /** Rule operators. */
        const val OP_ABOVE = "above"   // fire when value > threshold
        const val OP_BELOW = "below"   // fire when value < threshold
        const val OP_CHANGE = "change" // fire on any value change
    }
}
