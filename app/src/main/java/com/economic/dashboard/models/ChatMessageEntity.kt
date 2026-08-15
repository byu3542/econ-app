package com.economic.dashboard.models

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
    var charts: String? = null
) {
    fun toChatMessage(): ChatMessage {
        val m = ChatMessage(text, isUser, false, false, timeMillis)
        m.charts = decodeCharts(charts)
        return m
    }

    companion object {
        @JvmStatic
        fun from(m: ChatMessage): ChatMessageEntity =
            ChatMessageEntity(
                text = m.text, isUser = m.isUser, timeMillis = m.timeMillis,
                charts = encodeCharts(m.charts)
            )

        /** Null for an empty list, so unchanged rows stay NULL rather than "[]". */
        @JvmStatic
        fun encodeCharts(charts: List<ChatChart>?): String? {
            if (charts == null || charts.isEmpty()) return null
            val arr = JSONArray()
            for (c in charts)
                arr.put(JSONObject().put("series_id", c.seriesId).put("months", c.months))
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
                    out.add(ChatChart(id, o.optInt("months", 24).coerceIn(1, 24)))
                }
                out
            } catch (ignored: Exception) {
                emptyList()
            }
        }
    }
}
