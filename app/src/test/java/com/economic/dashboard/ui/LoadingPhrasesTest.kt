package com.economic.dashboard.ui

import com.economic.dashboard.ui.assistant.LoadingPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Pantrii port U7 (R5, KTD9): the loader's phrase rotation and tool-phrase
 * mapping. Seeded randoms make every shuffle reproducible.
 */
class LoadingPhrasesTest {

    private val themed = listOf(
        "Reading the yield curve…", "Consulting the dot plot…", "Seasonally adjusting…",
        "Counting nonfarm payrolls…", "Balancing the ledger…", "Squinting at the spreads…",
    )

    private val tools = mapOf(
        "get_series" to "Pulling the {series} series…",
        "render_chart" to "Drawing the chart…",
    )

    @Test
    fun next_dealsEveryPhraseOnceBeforeAnyRepeats() {
        for (seed in 0 until 50) {
            val phrases = LoadingPhrases(themed, random = Random(seed))
            // Three full bags: each bag of N is a permutation of the list.
            for (bag in 0 until 3) {
                val dealt = List(themed.size) { phrases.next() }
                assertEquals("seed $seed bag $bag", themed.toSet(), dealt.toSet())
            }
        }
    }

    @Test
    fun next_neverRepeatsBackToBack_evenAcrossAReshuffle() {
        for (seed in 0 until 200) {
            val phrases = LoadingPhrases(themed, random = Random(seed))
            var previous = phrases.next()
            repeat(themed.size * 10) {
                val current = phrases.next()
                assertNotEquals("seed $seed call $it", previous, current)
                previous = current
            }
        }
    }

    @Test
    fun next_twoPhrases_alternate() {
        val phrases = LoadingPhrases(listOf("A", "B"), random = Random(7))
        var previous = phrases.next()
        repeat(20) {
            val current = phrases.next()
            assertNotEquals(previous, current)
            previous = current
        }
    }

    @Test
    fun forTool_mapsKnownTools_fillsOrDropsTheSeries() {
        val phrases = LoadingPhrases(themed, tools)
        assertEquals("Pulling the CPI series…", phrases.forTool("get_series", "CPI"))
        assertEquals("Pulling the series…", phrases.forTool("get_series", null))
        assertEquals("Drawing the chart…", phrases.forTool("render_chart"))
    }

    @Test
    fun forTool_unknownTool_isNull() {
        val phrases = LoadingPhrases(themed, tools)
        assertNull(phrases.forTool("get_weather", "CPI"))
        assertNull(LoadingPhrases(themed).forTool("get_series", "CPI"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPhraseList_isRejected() {
        LoadingPhrases(emptyList())
    }
}
