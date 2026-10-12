package com.economic.dashboard.models

import java.util.LinkedHashMap
import java.util.Locale

/**
 * MOT-04 — one thing the analyst actually fetched during a turn: a series a
 * `get_series` call returned points for, or a headline a `get_headlines` call
 * returned. Built only from tool results, never from the headlines
 * NewsContextBuilder injects into the prompt, so a badge never claims
 * provenance the answer doesn't have.
 *
 * Transient like the MOT-02 trace fields: it rides [ChatMessage.sources] for
 * the session and is never persisted.
 *
 * Kept free of Android types so the dedupe and badge rules can be unit-tested.
 */
class TurnSource(
    @JvmField val kind: Kind,
    /** Dedupe key: the series id, or the article URL (title when it has none). */
    @JvmField val key: String,
    /** Series label ("10-Year Treasury Yield") or the headline itself. */
    @JvmField val title: String,
    /** Who supplied it: "FRED", "BLS", … for series; the outlet for headlines. */
    @JvmField val provider: String,
    @JvmField val seriesId: String?,
    @JvmField val url: String?,
    /** AIC-11: the series' newest observation ("yyyy-MM-dd" or "yyyy-MM") when
     *  the fetch knew it; ReleaseCalendar.isStale reads it. */
    @JvmField val latestDate: String? = null
) {
    enum class Kind { SERIES, HEADLINE }

    companion object {

        @JvmStatic
        @JvmOverloads
        fun series(
            seriesId: String, label: String, provider: String, latestDate: String? = null
        ): TurnSource =
            TurnSource(Kind.SERIES, "series:$seriesId", label, provider, seriesId, null, latestDate)

        @JvmStatic
        fun headline(title: String, outlet: String?, url: String?): TurnSource {
            val u = url?.trim()?.takeIf { it.isNotEmpty() }
            val t = title.trim()
            val key = if (u != null) "url:$u" else "title:" + t.lowercase(Locale.US)
            val o = outlet?.trim().orEmpty()
            return TurnSource(Kind.HEADLINE, key, t, if (o.isEmpty()) "News" else o, null, u)
        }

        /** Distinct providers in first-seen order — one badge each. */
        @JvmStatic
        fun providers(sources: List<TurnSource>): List<String> {
            val out = ArrayList<String>()
            for (s in sources) if (!out.contains(s.provider)) out.add(s.provider)
            return out
        }

        /**
         * Two-letter badge for a provider name. Deterministic, no lookup table:
         * an acronym keeps its first two letters (FRED → FR, CNBC Economy → CN),
         * a CamelCase word its first two capitals (MarketWatch → MW), a
         * multi-word name its two initials (Yahoo Finance → YF), and a plain
         * word its first two letters (Reuters → RE).
         */
        @JvmStatic
        fun badgeLetters(name: String?): String {
            val words = name?.trim()?.split(Regex("\\s+"))
                ?.map { w -> w.filter { it.isLetterOrDigit() } }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if (words.isEmpty()) return "?"
            val first = words[0]
            if (first.length >= 2 && first.none { it.isLowerCase() })
                return first.take(2).uppercase(Locale.US)
            val caps = first.filter { it.isUpperCase() }
            if (caps.length >= 2) return caps.take(2)
            if (words.size >= 2) return ("" + first[0] + words[1][0]).uppercase(Locale.US)
            return first.take(2).uppercase(Locale.US)
        }
    }
}

/**
 * MOT-04 — the per-turn source list, carried on AiAnalystBottomSheet.StreamState.
 * Ordered by first fetch and deduped on [TurnSource.key]. Tools run on OkHttp
 * threads and the UI reads a [snapshot], hence the locking.
 *
 * One list per turn on purpose: the stack under the answer reads it now, and
 * Batch 2's inline citation pills should index into the same list rather than
 * build their own.
 */
class TurnSources {
    private val items = LinkedHashMap<String, TurnSource>()

    @Synchronized
    fun addAll(sources: List<TurnSource>) {
        for (s in sources) if (!items.containsKey(s.key)) items[s.key] = s
    }

    @Synchronized
    fun snapshot(): List<TurnSource> = ArrayList(items.values)
}
