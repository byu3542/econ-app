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
}
