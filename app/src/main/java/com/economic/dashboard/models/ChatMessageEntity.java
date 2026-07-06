package com.economic.dashboard.models;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Persisted chat message so conversations survive app restarts. */
@Entity(tableName = "chat_messages")
public class ChatMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String text;
    public boolean isUser;
    public long timeMillis;

    public ChatMessageEntity() {}

    public static ChatMessageEntity from(ChatMessage m) {
        ChatMessageEntity e = new ChatMessageEntity();
        e.text = m.getText();
        e.isUser = m.isUser();
        e.timeMillis = m.getTimeMillis();
        return e;
    }

    public ChatMessage toChatMessage() {
        return new ChatMessage(text, isUser, false, false, timeMillis);
    }
}
