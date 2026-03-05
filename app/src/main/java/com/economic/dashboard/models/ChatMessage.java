package com.economic.dashboard.models;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage {
    private String text;
    private boolean isUser;
    private String timestamp;

    public ChatMessage(String text, boolean isUser) {
        this.text = text;
        this.isUser = isUser;
        this.timestamp = new SimpleDateFormat("h:mm a", Locale.US).format(new Date());
    }

    public String getText() { return text; }
    public boolean isUser() { return isUser; }
    public String getTimestamp() { return timestamp; }
}
