package com.economic.dashboard.models

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessage @JvmOverloads constructor(
    var text: String?,
    val isUser: Boolean,
    val isTyping: Boolean = false,
    val isError: Boolean = false,
    val timeMillis: Long = System.currentTimeMillis()
) {
    val timestamp: String = SimpleDateFormat("h:mm a", Locale.US).format(Date(timeMillis))

    /** Attached image preview (vision messages). Transient — not persisted to Room. */
    var imageBitmap: Bitmap? = null

    /**
     * Charts the analyst asked for via `render_chart`, in the order it asked
     * (TICKET-P2-2). A list, not a single optional chart: one tool call per
     * chart makes several natural where one inline tag did not.
     *
     * Unlike [imageBitmap] this IS persisted — see ChatMessageEntity.charts.
     * The old tag rode inside [text], so surviving a restart used to be free;
     * a typed list does not get that for free.
     */
    var charts: List<ChatChart> = emptyList()

    // ── MOT-02: thinking trace row. Transient — never persisted to Room. ──────

    /** This row is the analyst's "Thinking / Thought for N.Ns" trace. */
    var isTrace: Boolean = false

    /** Send time, for the elapsed label. */
    var traceStartMs: Long = 0L

    /** Send → first output; negative while the trace is still running. */
    var traceElapsedMs: Long = -1L

    /** Label verb once finished: "Thought" (normal) or e.g. "Stopped". */
    var traceVerb: String = "Thought"

    /** Step lines shown beneath the label (tool calls). */
    val traceSteps: MutableList<String> = ArrayList()

    /** Whether a finished trace has been tapped open again. */
    var traceExpanded: Boolean = false

    val isTraceRunning: Boolean get() = isTrace && traceElapsedMs < 0

    // ── MOT-04: sources stack. Transient — never persisted to Room. ───────────

    /** What this answer's turn actually fetched, in fetch order. */
    var sources: List<TurnSource> = emptyList()

    /** Whether the stack under the answer has been tapped open. */
    var sourcesExpanded: Boolean = false
}
