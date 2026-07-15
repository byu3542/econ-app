package com.economic.dashboard.models;

import android.graphics.Bitmap;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage {
    private String text;
    private boolean isUser;
    private String timestamp;
    private long timeMillis;
    private boolean isTyping;
    private boolean isError;

    /** Attached image preview (vision messages). Transient — not persisted to Room. */
    private Bitmap imageBitmap;

    public ChatMessage(String text, boolean isUser) {
        this(text, isUser, false, false);
    }

    public ChatMessage(String text, boolean isUser, boolean isTyping) {
        this(text, isUser, isTyping, false);
    }

    public ChatMessage(String text, boolean isUser, boolean isTyping, boolean isError) {
        this(text, isUser, isTyping, isError, System.currentTimeMillis());
    }

    /** Restores a message with its original send time (Room persistence). */
    public ChatMessage(String text, boolean isUser, boolean isTyping, boolean isError, long timeMillis) {
        this.text       = text;
        this.isUser     = isUser;
        this.isTyping   = isTyping;
        this.isError    = isError;
        this.timeMillis = timeMillis;
        this.timestamp  = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(timeMillis));
    }

    public String getText()      { return text; }
    public boolean isUser()      { return isUser; }
    public String getTimestamp() { return timestamp; }
    public long getTimeMillis()  { return timeMillis; }
    public boolean isTyping()    { return isTyping; }
    public boolean isError()     { return isError; }

    public Bitmap getImageBitmap()            { return imageBitmap; }
    public void setImageBitmap(Bitmap bitmap) { this.imageBitmap = bitmap; }

    /** Used while streaming a response into an existing bubble. */
    public void setText(String text) { this.text = text; }
}
