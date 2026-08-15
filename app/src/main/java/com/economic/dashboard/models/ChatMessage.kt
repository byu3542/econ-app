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
}
