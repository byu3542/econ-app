package com.economic.dashboard.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persisted chat message so conversations survive app restarts. */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var text: String? = null,
    var isUser: Boolean = false,
    var timeMillis: Long = 0
) {
    fun toChatMessage(): ChatMessage = ChatMessage(text, isUser, false, false, timeMillis)

    companion object {
        @JvmStatic
        fun from(m: ChatMessage): ChatMessageEntity =
            ChatMessageEntity(text = m.text, isUser = m.isUser, timeMillis = m.timeMillis)
    }
}
