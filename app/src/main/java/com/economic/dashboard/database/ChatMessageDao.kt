package com.economic.dashboard.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.economic.dashboard.models.ChatMessageEntity

@Dao
interface ChatMessageDao {
    @Insert
    fun insert(message: ChatMessageEntity)

    /** AIC-08: the active thread only; archived chats stay in the table. */
    @Query("SELECT * FROM chat_messages WHERE archived = 0 ORDER BY timeMillis ASC, id ASC")
    fun getAll(): List<ChatMessageEntity>

    /** AIC-08: New chat files the active thread away instead of deleting it. */
    @Query("UPDATE chat_messages SET archived = 1 WHERE archived = 0")
    fun archiveActive()

    @Query("DELETE FROM chat_messages")
    fun clearAll()

    /** AIC-05: Regenerate replaces the newest analyst answer, so the stored
     *  conversation keeps one answer per question across a restart. */
    @Query("DELETE FROM chat_messages WHERE id = (SELECT id FROM chat_messages WHERE isUser = 0 AND archived = 0 ORDER BY id DESC LIMIT 1)")
    fun deleteLastAnalyst()

    /** Keeps the active thread bounded — retains its newest `keep` rows.
     *  AIC-08: scoped to the active thread, so archived chats are never trimmed. */
    @Query("DELETE FROM chat_messages WHERE archived = 0 AND id NOT IN (SELECT id FROM chat_messages WHERE archived = 0 ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)
}
