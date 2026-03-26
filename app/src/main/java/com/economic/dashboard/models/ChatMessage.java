package com.economic.dashboard.models;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage {
    private String text;
    private boolean isUser;
    private String timestamp;
    private boolean isTyping;
    private boolean isError;

    public ChatMessage(String text, boolean isUser) {
        this(text, isUser, false, false);
    }

    public ChatMessage(String text, boolean isUser, boolean isTyping) {
        this(text, isUser, isTyping, false);
    }

    public ChatMessage(String text, boolean isUser, boolean isTyping, boolean isError) {
        this.text      = text;
        this.isUser    = isUser;
        this.isTyping  = isTyping;
        this.isError   = isError;
        this.timestamp = new SimpleDateFormat("h:mm a", Locale.US).format(new Date());
    }

    public String getText()      { return text; }
    public boolean isUser()      { return isUser; }
    public String getTimestamp() { return timestamp; }
    public boolean isTyping()    { return isTyping; }
    public boolean isError()     { return isError; }
}
