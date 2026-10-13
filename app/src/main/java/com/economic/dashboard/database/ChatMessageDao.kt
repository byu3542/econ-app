package com.economic.dashboard.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.economic.dashboard.models.ChatMessageEntity

@Dao
interface ChatMessageDao {
    @Insert
    fun insert(message: ChatMessageEntity)

    /** AIC-08: the active thread only; archived chats stay in the table. */
    @Query("SELECT * FROM chat_messages WHERE archived = 0 ORDER BY timeMillis ASC, id ASC")
    fun getAll(): List<ChatMessageEntity>

    /** AIC-08: New chat files the active thread away instead of deleting it.
     *  U8: the active thread is one conversation by definition, so every row
     *  in it takes [conversationId] — including rows written before v9 or by
     *  DailyBriefWorker, which carry no key. */
    @Query("UPDATE chat_messages SET archived = 1, conversationId = :conversationId WHERE archived = 0")
    fun archiveActive(conversationId: Long)

    /** U8: every archived row, in insertion order, for ConversationSummary
     *  to group. Insertion order is what read-time legacy grouping relies on. */
    @Query("SELECT * FROM chat_messages WHERE archived = 1 ORDER BY id ASC")
    fun getArchived(): List<ChatMessageEntity>

    /** U8: brings one archived conversation back as the active thread. Takes
     *  row ids, so a legacy (NULL-key) group reopens the same way, and stamps
     *  the key so it stays one conversation from then on. */
    @Query("UPDATE chat_messages SET archived = 0, conversationId = :conversationId WHERE id IN (:ids)")
    fun restore(ids: List<Long>, conversationId: Long)

    /** U8: history's swipe-to-delete, after its Undo window has passed. */
    @Query("DELETE FROM chat_messages WHERE archived = 1 AND id IN (:ids)")
    fun deleteArchived(ids: List<Long>)

    /** U8: the active thread's key after a restart (null for a legacy thread). */
    @Query("SELECT conversationId FROM chat_messages WHERE archived = 0 AND conversationId IS NOT NULL ORDER BY id DESC LIMIT 1")
    fun activeConversationId(): Long?

    /** Pairs [archiveActive] and [restore] in one transaction, so reopening a
     *  chat can never leave both threads active or both archived. */
    @Transaction
    fun swapActive(currentId: Long, ids: List<Long>, reopenedId: Long) {
        archiveActive(currentId)
        restore(ids, reopenedId)
    }

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
