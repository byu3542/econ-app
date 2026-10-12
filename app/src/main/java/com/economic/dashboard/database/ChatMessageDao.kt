package com.economic.dashboard.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.economic.dashboard.models.ChatMessageEntity

@Dao
interface ChatMessageDao {
    @Insert
    fun insert(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages ORDER BY timeMillis ASC, id ASC")
    fun getAll(): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages")
    fun clearAll()

    /** AIC-05: Regenerate replaces the newest analyst answer, so the stored
     *  conversation keeps one answer per question across a restart. */
    @Query("DELETE FROM chat_messages WHERE id = (SELECT id FROM chat_messages WHERE isUser = 0 ORDER BY id DESC LIMIT 1)")
    fun deleteLastAnalyst()

    /** Keeps the table bounded — retains the newest `keep` rows. */
    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)
}
