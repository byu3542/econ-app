package com.economic.dashboard.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.economic.dashboard.models.ChatMessageEntity;

import java.util.List;

@Dao
public interface ChatMessageDao {
    @Insert
    void insert(ChatMessageEntity message);

    @Query("SELECT * FROM chat_messages ORDER BY timeMillis ASC, id ASC")
    List<ChatMessageEntity> getAll();

    @Query("DELETE FROM chat_messages")
    void clearAll();

    /** Keeps the table bounded — retains the newest {@code keep} rows. */
    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY id DESC LIMIT :keep)")
    void trim(int keep);
}
