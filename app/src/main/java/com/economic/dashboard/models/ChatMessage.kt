package com.economic.dashboard.models

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessage @JvmOverloads constructor(
    var text: String?,
    val isUser: Boolean,
    /** U7 (R5): the loader row standing in for a pending reply. */
    val isLoader: Boolean = false,
    val isError: Boolean = false,
    val timeMillis: Long = System.currentTimeMillis()
) {
    /** AIC-05: time alone for today's messages; older turns carry the date. */
    val timestamp: String
        get() {
            val date = Date(timeMillis)
            val day = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = day.format(date) == day.format(Date())
            return SimpleDateFormat(if (today) "h:mm a" else "MMM d, h:mm a", Locale.US).format(date)
        }

    // ── AIC-05: per-reply action row. Transient — never persisted to Room. ────

    /** Short model name for the answer ("Haiku 4.5"); null when unknown,
     *  e.g. for answers restored from Room. */
    var modelLabel: String? = null

    /** Earlier answers to the same question, kept by Regenerate, oldest
     *  first; the current answer is the last entry. Empty until the first
     *  regenerate, so a single answer shows no toggle. */
    val variants: MutableList<AnswerVariant> = ArrayList()

    /** Which of [variants] is on screen. */
    var variantIndex: Int = 0

    /** AIC-03 wrote the stop marker into the text, so it survives Room. */
    val isStopped: Boolean get() = text?.endsWith(STOPPED_SUFFIX) == true

    class AnswerVariant(val text: String?, val charts: List<ChatChart>, val modelLabel: String?)

    companion object {
        /** AIC-03: appended to an answer cut short by Stop. */
        const val STOPPED_SUFFIX = "\n\n— Stopped. This answer is incomplete."
    }

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

    // ── MOT-04: sources stack. AIC-11: persisted (ChatMessageEntity.sources). ──

    /** What this answer's turn actually fetched, in fetch order. */
    var sources: List<TurnSource> = emptyList()

    /** Whether the stack under the answer has been tapped open. */
    var sourcesExpanded: Boolean = false

    // ── U7: loader row. Transient — never persisted to Room. ─────────────────

    /** The tool phrase overriding the loader's themed rotation (AE1), or null. */
    var loaderTool: String? = null
}
