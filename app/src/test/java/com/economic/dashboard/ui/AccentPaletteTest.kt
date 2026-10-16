package com.economic.dashboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Settings parity U1 (R7): accent ids and contrast floors. */
class AccentPaletteTest {

    @Test
    fun fromIdFallsBackToNavy() {
        assertEquals(AccentPalette.NAVY, AccentPalette.fromId(null))
        assertEquals(AccentPalette.NAVY, AccentPalette.fromId(""))
        assertEquals(AccentPalette.NAVY, AccentPalette.fromId("magenta"))
        assertEquals(AccentPalette.NAVY, AccentPalette.fromId("gold"))
        assertEquals(AccentPalette.RED, AccentPalette.fromId("red"))
    }

    @Test
    fun idsAreUnique() {
        val ids = AccentPalette.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyAccentClearsContrastInBothModes() {
        for (a in AccentPalette.values()) {
            assertContrast("${a.id} light on-accent", AccentPalette.ON_ACCENT_LIGHT, a.light)
            assertContrast("${a.id} light accent-on-soft", a.light, a.lightSoft)
            assertContrast("${a.id} night on-accent", AccentPalette.ON_ACCENT_NIGHT, a.night)
            assertContrast("${a.id} night accent-on-soft", a.night, a.nightSoft)
        }
    }

    private fun assertContrast(label: String, fg: Long, bg: Long) {
        val ratio = contrast(fg, bg)
        assertTrue("$label is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
}
