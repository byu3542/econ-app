package com.economic.dashboard.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

import org.json.JSONArray
import org.json.JSONObject

/** Persisted chat message so conversations survive app restarts. */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var text: String? = null,
    var isUser: Boolean = false,
    var timeMillis: Long = 0,
    /**
     * TICKET-P2-2: the reply's charts, as a JSON array of
     * `{"series_id": "...", "months": N}`. Added in schema v6 (MIGRATION_5_6).
     *
     * Nullable, and every decode failure degrades to "no charts" — rows written
     * before v6 carry NULL here, and a restored conversation must never crash on
     * one.
     */
    var charts: String? = null,
    /** AIC-08: true once New chat has archived this row's thread. Schema v7
     *  (MIGRATION_6_7); the default matches the migration's column. */
    @ColumnInfo(defaultValue = "0")
    var archived: Boolean = false,
    /** AIC-11: what the answer fetched (MOT-04 sources), as a JSON array, so
     *  the sources stack survives a restart. Schema v8 (MIGRATION_7_8);
     *  nullable, and a decode failure degrades to no sources. */
    var sources: String? = null,
    /** Pantrii port U8 (KTD8): which conversation the row belongs to, so
     *  history can tell archived chats apart. Schema v9 (MIGRATION_8_9);
     *  nullable, and legacy rows stay NULL — ConversationSummary groups those
     *  at read time instead of guessing a key into the database. */
    var conversationId: Long? = null
) {
    fun toChatMessage(): ChatMessage {
        val m = ChatMessage(text, isUser, false, false, timeMillis)
        m.charts = decodeCharts(charts)
        m.sources = decodeSources(sources)
        return m
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun from(m: ChatMessage, conversationId: Long? = null): ChatMessageEntity =
            ChatMessageEntity(
                text = m.text, isUser = m.isUser, timeMillis = m.timeMillis,
                charts = encodeCharts(m.charts),
                sources = encodeSources(m.sources),
                conversationId = conversationId
            )

        /** AIC-11: null for an empty list, like [encodeCharts]. */
        @JvmStatic
        fun encodeSources(sources: List<TurnSource>?): String? {
            if (sources == null || sources.isEmpty()) return null
            val arr = JSONArray()
            for (s in sources)
                arr.put(JSONObject().put("kind", s.kind.name).put("key", s.key)
                    .put("title", s.title).put("provider", s.provider)
                    .put("series_id", s.seriesId).put("url", s.url)
                    .put("latest", s.latestDate))
            return arr.toString()
        }

        /** AIC-11: tolerant like [decodeCharts] — a bad entry is dropped. */
        @JvmStatic
        fun decodeSources(json: String?): List<TurnSource> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<TurnSource>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = try {
                        TurnSource.Kind.valueOf(o.optString("kind"))
                    } catch (e: IllegalArgumentException) { continue }
                    val key = o.optString("key", "")
                    if (key.isEmpty()) continue
                    out.add(TurnSource(kind, key, o.optString("title", ""),
                        o.optString("provider", ""),
                        o.optString("series_id", "").ifEmpty { null },
                        o.optString("url", "").ifEmpty { null },
                        o.optString("latest", "").ifEmpty { null }))
                }
                out
            } catch (ignored: Exception) {
                emptyList()
            }
        }

        /** Null for an empty list, so unchanged rows stay NULL rather than "[]". */
        @JvmStatic
        fun encodeCharts(charts: List<ChatChart>?): String? {
            if (charts == null || charts.isEmpty()) return null
            val arr = JSONArray()
            for (c in charts) {
                val o = JSONObject().put("series_id", c.seriesId).put("months", c.months)
                // Tables only, so chart rows keep their exact v6 shape.
                if (c.isTable) o.put("view", ChatChart.VIEW_TABLE)
                arr.put(o)
            }
            return arr.toString()
        }

        /**
         * Tolerant by design: a malformed or unknown entry is dropped rather
         * than thrown, because this runs on the restore path where the
         * alternative is losing the whole conversation. Months are re-clamped
         * on read — the renderer trusts this list.
         */
        @JvmStatic
        fun decodeCharts(json: String?): List<ChatChart> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<ChatChart>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("series_id", "")
                    if (id.isEmpty()) continue
                    val view = if (o.optString("view") == ChatChart.VIEW_TABLE)
                        ChatChart.VIEW_TABLE else ChatChart.VIEW_CHART
                    out.add(ChatChart(id, o.optInt("months", 24).coerceIn(1, 24), view))
                }
                out
            } catch (ignored: Exception) {
                emptyList()
            }
        }
    }
}
