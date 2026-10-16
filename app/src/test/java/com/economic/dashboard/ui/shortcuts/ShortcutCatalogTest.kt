package com.economic.dashboard.ui.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun searchIsLiveAndLeadsTheDefaults() {
        // Settings parity U9: Search went live, so it is first again.
        assertEquals("search", ShortcutCatalog.find("search")?.id)
        assertEquals("search", ShortcutCatalog.defaults().first())
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

    // ── Settings parity U7 (R11): the editor's working copy ──

    @Test
    fun addingASeventhIsRejected() {
        val e = ShortcutEditor(listOf("gdp", "bonds", "stocks", "news", "housing", "spreads"))
        assertTrue(e.isFull)
        assertFalse(e.add("jobs"))
        assertEquals(6, e.ids.size)
        assertFalse(e.isDirty)
    }

    @Test
    fun removingThenAddingAppendsAtTheEnd() {
        val e = ShortcutEditor(listOf("gdp", "bonds", "stocks"))
        assertTrue(e.remove("gdp"))
        assertTrue(e.add("gdp"))
        assertEquals(listOf("bonds", "stocks", "gdp"), e.ids)
        assertTrue(e.isDirty)
    }

    @Test
    fun addRejectsDuplicatesAndUnknownIds() {
        val e = ShortcutEditor(listOf("gdp"))
        assertFalse(e.add("gdp"))
        assertFalse(e.add("nonsense"))
        assertEquals(listOf("gdp"), e.ids)
    }

    @Test
    fun moveReordersAndRejectsOutOfRange() {
        val e = ShortcutEditor(listOf("gdp", "bonds", "stocks"))
        assertTrue(e.move(1, 0))
        assertEquals(listOf("bonds", "gdp", "stocks"), e.ids)
        assertFalse(e.move(0, 3))
        assertFalse(e.move(-1, 0))
        assertTrue(e.move(1, 0))
        assertFalse(e.isDirty) // back to where it opened
    }

    @Test
    fun moreListsEveryAvailableShortcutNotInTheList() {
        val e = ShortcutEditor(listOf("gdp"))
        val more = e.more()
        assertFalse("gdp" in more)
        assertEquals(ShortcutCatalog.available().size - 1, more.size)
    }
}
