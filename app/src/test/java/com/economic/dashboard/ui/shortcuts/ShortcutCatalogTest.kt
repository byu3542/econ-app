package com.economic.dashboard.ui.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings parity U6 (KTD6): the catalog and the stored-order read rules. */
class ShortcutCatalogTest {

    @Test
    fun unsetListReturnsTheDefaultsInOrder() {
        val defaults = ShortcutCatalog.sanitize(null)
        assertEquals(ShortcutCatalog.defaults(), defaults)
        assertEquals(ShortcutCatalog.MAX, defaults.size)
        // The plan's defaults lead, in order, minus any not yet available.
        val planned = ShortcutCatalog.DEFAULT_IDS.filter { ShortcutCatalog.find(it) != null }
        assertEquals(planned, defaults.take(planned.size))
    }

    @Test
    fun storedListDropsUnknownAndDuplicatesAndCapsAtSix() {
        val stored = listOf(
            "gdp", "nonsense", "bonds", "gdp", "stocks", "news",
            "housing", "spreads", "jobs", "inflation")
        assertEquals(
            listOf("gdp", "bonds", "stocks", "news", "housing", "spreads"),
            ShortcutCatalog.sanitize(stored))
    }

    @Test
    fun emptyStoredListStaysEmpty() {
        assertTrue(ShortcutCatalog.sanitize(emptyList()).isEmpty())
    }

    @Test
    fun unavailableShortcutsNeverShow() {
        for (s in ShortcutCatalog.ALL.filter { !it.available }) {
            assertNull(ShortcutCatalog.find(s.id))
            assertTrue(s.id !in ShortcutCatalog.sanitize(listOf(s.id)))
            assertTrue(s.id !in ShortcutCatalog.defaults())
        }
    }

    @Test
    fun everyCatalogIdIsUniqueAndHasAnAction() {
        val ids = ShortcutCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in ShortcutCatalog.ALL) {
            assertTrue(s.id.isNotBlank())
            assertTrue(s.label != 0 && s.icon != 0)
            if (s.action is ShortcutAction.OpenTab) {
                assertTrue((s.action as ShortcutAction.OpenTab).tabKey in setOf("markets", "economy", "news"))
            }
        }
    }
}
