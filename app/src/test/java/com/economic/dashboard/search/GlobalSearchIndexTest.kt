package com.economic.dashboard.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings parity U8 (R13, KTD8): ranking, normalization and grouping. */
class GlobalSearchIndexTest {

    private val sources = SearchSources(
        indicators = listOf(
            SearchDoc("corepce", "Core PCE index", text = listOf("Core PCE index", "core inflation vs cpi")),
            SearchDoc("cpi", "CPI-U YoY"),
            SearchDoc("vix", "VIX index")
        ),
        news = listOf(
            SearchDoc("https://a", "Café owners brace for tariffs"),
            SearchDoc("https://b", "Markets rally")
        ),
        chatMessages = listOf(
            SearchDoc("c1", "What does CPI mean?", text = listOf("What does CPI mean?")),
            SearchDoc("c1", "What does CPI mean?", text = listOf("CPI is the consumer price index")),
            SearchDoc("c2", "Rates", text = listOf("Fed funds"))
        ),
        settings = listOf(SearchDoc("appearance", "Appearance"))
    )

    @Test
    fun prefixRanksFirstAndEmptyGroupsAreDropped() {
        val groups = GlobalSearchIndex.search(sources, "cpi")
        assertEquals(listOf(SearchScope.INDICATORS, SearchScope.CHATS), groups.map { it.scope })
        assertEquals(listOf("cpi", "corepce"), groups[0].results.map { it.key })
    }

    @Test
    fun matchingIgnoresAccentsAndCase() {
        assertEquals("https://a", GlobalSearchIndex.search(sources, "cafe").single().results.single().key)
        assertEquals("https://b", GlobalSearchIndex.search(sources, "MARKETS").single().results.single().key)
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(GlobalSearchIndex.search(sources, "").isEmpty())
        assertTrue(GlobalSearchIndex.search(sources, "   ").isEmpty())
    }

    @Test
    fun twoMessagesInOneConversationYieldOneHit() {
        val chats = GlobalSearchIndex.search(sources, "cpi", SearchScope.CHATS).single()
        assertEquals(listOf("c1"), chats.results.map { it.key })
    }

    @Test
    fun indicatorsScopeReturnsOnlyIndicators() {
        val groups = GlobalSearchIndex.search(sources, "i", SearchScope.INDICATORS)
        assertEquals(listOf(SearchScope.INDICATORS), groups.map { it.scope })
    }

    @Test
    fun prefixBeatsWordStartAndTiesSortAlphabetically() {
        val docs = SearchSources(indicators = listOf(
            SearchDoc("b", "Inflation"),
            SearchDoc("a", "Core inflation"),
            SearchDoc("c", "Deflation")
        ))
        assertEquals(listOf("b", "a"), GlobalSearchIndex.search(docs, "infl").single().results.map { it.key })
        // All three only contain "flat", so they fall back to title order.
        assertEquals(listOf("a", "c", "b"), GlobalSearchIndex.search(docs, "flat").single().results.map { it.key })
    }
}
