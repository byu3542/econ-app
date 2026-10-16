package com.economic.dashboard.search

import java.text.Normalizer
import java.util.Locale

/**
 * Settings parity U8 (R13, KTD8): ranked, grouped search over plain snapshots.
 *
 * No Android dependency and no I/O: callers hand in [SearchSources] built from
 * what is already loaded, so a search never touches the network.
 *
 * Matching ignores case and accents. A prefix match beats a word-start match,
 * which beats a contains match; ties sort alphabetically by title.
 */
object GlobalSearchIndex {

    /** Scope order is the group order on screen. */
    val SCOPES = listOf(
        SearchScope.INDICATORS, SearchScope.NEWS, SearchScope.CHATS, SearchScope.SETTINGS
    )

    private const val RANK_PREFIX = 0
    private const val RANK_WORD_START = 1
    private const val RANK_CONTAINS = 2

    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Groups with at least one hit, in [SCOPES] order. [scope] null means All.
     * A blank query returns no groups.
     */
    @JvmStatic
    fun search(
        sources: SearchSources,
        query: String,
        scope: SearchScope? = null
    ): List<SearchGroup> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return emptyList()
        return SCOPES.filter { scope == null || it == scope }.mapNotNull { s ->
            val docs = when (s) {
                SearchScope.INDICATORS -> sources.indicators
                SearchScope.NEWS -> sources.news
                SearchScope.CHATS -> sources.chatMessages
                SearchScope.SETTINGS -> sources.settings
            }
            val results = rank(s, docs, q)
            if (results.isEmpty()) null else SearchGroup(s, results)
        }
    }

    private fun rank(scope: SearchScope, docs: List<SearchDoc>, q: String): List<SearchResult> {
        // Best rank per key, so one conversation (or a duplicate URL) is one hit.
        val best = LinkedHashMap<String, Pair<Int, SearchDoc>>()
        for (doc in docs) {
            val r = doc.text.mapNotNull { matchRank(normalize(it), q) }.minOrNull() ?: continue
            val prev = best[doc.key]
            if (prev == null || r < prev.first) best[doc.key] = r to doc
        }
        return best.values
            .sortedWith(compareBy<Pair<Int, SearchDoc>> { it.first }
                .thenBy { normalize(it.second.title) })
            .map { (_, d) -> SearchResult(scope, d.key, d.title, d.subtitle) }
    }

    /** Null when [text] does not contain [q]; both already normalized. */
    internal fun matchRank(text: String, q: String): Int? {
        var i = text.indexOf(q)
        if (i < 0) return null
        if (i == 0) return RANK_PREFIX
        while (i >= 0) {
            if (!text[i - 1].isLetterOrDigit()) return RANK_WORD_START
            i = text.indexOf(q, i + 1)
        }
        return RANK_CONTAINS
    }

    /** Lower case with diacritics stripped: "Café" becomes "cafe". */
    @JvmStatic
    fun normalize(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}
