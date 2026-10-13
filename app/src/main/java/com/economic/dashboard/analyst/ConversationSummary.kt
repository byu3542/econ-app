package com.economic.dashboard.analyst

import com.economic.dashboard.models.ChatMessageEntity

/**
 * Pantrii port U8 (R7, KTD8): one row of the chat-history screen.
 *
 * Pure Kotlin so the grouping and title rules stay JVM-testable. [rowIds] is
 * what reopen and delete act on, so a legacy group (no stored key) behaves
 * exactly like a keyed one.
 */
data class ConversationSummary(
    /** The stored key, or null for a group of pre-v9 rows found by [group]. */
    val conversationId: Long?,
    val rowIds: List<Long>,
    val title: String,
    val firstMillis: Long,
    val lastMillis: Long,
    val messageCount: Int
) {
    companion object {
        const val TITLE_MAX = 60
        const val IMAGE_TITLE = "Image question"
        const val FALLBACK_TITLE = "Conversation"

        /** Legacy rows split into a new conversation when a user turn follows
         *  the previous row by at least this long. Aaron's call, 2026-10-13:
         *  group at read time, never write a guessed key back. */
        const val LEGACY_GAP_MILLIS = 30L * 60 * 1000

        /**
         * Groups archived rows into conversations, newest first by last
         * message. [rows] must be in insertion (id) order, which is what
         * ChatMessageDao.getArchived returns. Every row lands in exactly one
         * summary, so nothing is dropped.
         */
        @JvmStatic
        fun group(rows: List<ChatMessageEntity>): List<ConversationSummary> {
            val groups = LinkedHashMap<Any, MutableList<ChatMessageEntity>>()
            var legacy = 0
            var prevLegacy: ChatMessageEntity? = null
            for (r in rows) {
                val key: Any = r.conversationId ?: run {
                    val p = prevLegacy
                    val split = p == null ||
                        (r.isUser && r.timeMillis - p.timeMillis >= LEGACY_GAP_MILLIS)
                    prevLegacy = r
                    if (split) legacy++
                    "legacy-$legacy"
                }
                groups.getOrPut(key) { ArrayList() }.add(r)
            }
            return groups.values.map { of(it) }.sortedByDescending { it.lastMillis }
        }

        /** One conversation's summary; [rows] in insertion order, non-empty. */
        @JvmStatic
        fun of(rows: List<ChatMessageEntity>): ConversationSummary =
            ConversationSummary(
                conversationId = rows.firstNotNullOfOrNull { it.conversationId },
                rowIds = rows.map { it.id },
                title = titleFor(rows),
                firstMillis = rows.minOf { it.timeMillis },
                lastMillis = rows.maxOf { it.timeMillis },
                messageCount = rows.size
            )

        /** The first user message, on one line, cut at [TITLE_MAX] with an
         *  ellipsis. An image-only first turn has no text, so it reads as
         *  [IMAGE_TITLE]. A thread with no user turn (a Daily Brief alone)
         *  falls back to its first message. */
        @JvmStatic
        fun titleFor(rows: List<ChatMessageEntity>): String {
            val first = rows.firstOrNull { it.isUser }
            if (first != null) {
                val t = oneLine(first.text)
                return if (t.isEmpty()) IMAGE_TITLE else truncate(t)
            }
            val t = oneLine(rows.firstOrNull()?.text?.replace("*", ""))
            return if (t.isEmpty()) FALLBACK_TITLE else truncate(t)
        }

        private fun oneLine(s: String?): String =
            (s ?: "").replace(Regex("\\s+"), " ").trim()

        private fun truncate(t: String): String =
            if (t.length <= TITLE_MAX) t else t.substring(0, TITLE_MAX).trimEnd() + "…"
    }
}
