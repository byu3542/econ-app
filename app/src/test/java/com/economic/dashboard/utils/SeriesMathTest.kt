package com.economic.dashboard.utils

import com.economic.dashboard.models.EconomicDataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for [SeriesMath.mortgageMinusTenYearSpread] — the only
 * derived transform with a non-trivial date join (weekly mortgage prints paired
 * with the most recent daily 10Y close on or before each print, via a
 * two-pointer walk over date-sorted YYYY-MM-DD strings). The alert-side
 * consumer is locked separately in AlertValuesTest (mortgageSpreadMirrorsPopupFormula).
 */
class SeriesMathTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun mort(date: String, v: Double) =
        EconomicDataPoint("Test", "Housing", "30-Yr Mortgage Rate", date, v, "%")

    private fun tsy(date: String, v: Double) =
        EconomicDataPoint("Test", "Treasury", "10 Year", date, v, "%")

    // ── the date join ───────────────────────────────────────────────────────

    @Test
    fun pairsEachMortgagePrintWithMostRecentTreasuryOnOrBefore() {
        // Weekly Thursday mortgage prints vs. daily treasury closes that never
        // land on the same date — except the last, which locks the `<=`
        // (on-or-before is INCLUSIVE of a same-date close).
        val mortgage = listOf(
            mort("2026-07-02", 6.80),   // closes on/before: 06-30, 07-01 → 07-01 (4.40)
            mort("2026-07-09", 6.72),   // adds 07-06, 07-08 → 07-08 (4.55)
            mort("2026-07-16", 6.65))   // adds 07-15, 07-16 → 07-16 itself (4.50)
        val treasury = listOf(
            tsy("2026-06-30", 4.35),
            tsy("2026-07-01", 4.40),
            tsy("2026-07-06", 4.48),
            tsy("2026-07-08", 4.55),
            tsy("2026-07-15", 4.45),
            tsy("2026-07-16", 4.50),
            tsy("2026-07-17", 9.99))    // after the last print — never used

        val out = SeriesMath.mortgageMinusTenYearSpread(mortgage, treasury)

        assertEquals(3, out.size)
        // One output point per mortgage print, carrying the mortgage date.
        assertEquals("2026-07-02", out[0].date)
        assertEquals("2026-07-09", out[1].date)
        assertEquals("2026-07-16", out[2].date)
        // Hand-computed spreads: print minus its on-or-before close.
        assertEquals(6.80 - 4.40, out[0].value, 1e-9)
        assertEquals(6.72 - 4.55, out[1].value, 1e-9)
        assertEquals(6.65 - 4.50, out[2].value, 1e-9)
    }

    @Test
    fun spreadPointsCarryCalculatedHousingMetadata() {
        val out = SeriesMath.mortgageMinusTenYearSpread(
            listOf(mort("2026-07-02", 6.80)), listOf(tsy("2026-07-01", 4.40)))
        assertEquals(1, out.size)
        assertEquals("Calculated", out[0].source)
        assertEquals("Housing", out[0].category)
        assertEquals("Mortgage-10Y Spread", out[0].series)
        assertEquals("%", out[0].unit)
    }

    @Test
    fun mortgagePrintBeforeEveryTreasuryCloseIsDropped() {
        // First print predates all closes — no on-or-before candidate, so it is
        // dropped rather than paired forward or defaulted to zero.
        val mortgage = listOf(mort("2026-06-25", 6.90), mort("2026-07-02", 6.80))
        val treasury = listOf(tsy("2026-07-01", 4.40))

        val out = SeriesMath.mortgageMinusTenYearSpread(mortgage, treasury)

        assertEquals(1, out.size)
        assertEquals("2026-07-02", out[0].date)
        assertEquals(6.80 - 4.40, out[0].value, 1e-9)
    }

    @Test
    fun staleTreasuryCloseIsReusedAcrossLaterPrints() {
        // No new close between the two prints — the 07-01 close pairs with both.
        val mortgage = listOf(mort("2026-07-02", 6.80), mort("2026-07-09", 6.72))
        val treasury = listOf(tsy("2026-07-01", 4.40))

        val out = SeriesMath.mortgageMinusTenYearSpread(mortgage, treasury)

        assertEquals(2, out.size)
        assertEquals(6.80 - 4.40, out[0].value, 1e-9)
        assertEquals(6.72 - 4.40, out[1].value, 1e-9)
    }

    @Test
    fun duplicateTreasuryDatesPickTheLastOccurrence() {
        // The walk consumes every close with date <= the print date and keeps
        // the last one consumed, so of two rows sharing a date the
        // later-indexed row wins — deterministically.
        val mortgage = listOf(mort("2026-07-02", 6.80))
        val treasury = listOf(tsy("2026-07-01", 4.50), tsy("2026-07-01", 4.30))

        val out = SeriesMath.mortgageMinusTenYearSpread(mortgage, treasury)

        assertEquals(1, out.size)
        assertEquals(6.80 - 4.30, out[0].value, 1e-9)
    }

    // ── empty inputs ────────────────────────────────────────────────────────

    @Test
    fun emptyEitherInputYieldsEmptyResult() {
        val mortgage = listOf(mort("2026-07-02", 6.80))
        val treasury = listOf(tsy("2026-07-01", 4.40))
        assertTrue(SeriesMath.mortgageMinusTenYearSpread(emptyList(), treasury).isEmpty())
        assertTrue(SeriesMath.mortgageMinusTenYearSpread(mortgage, emptyList()).isEmpty())
        assertTrue(SeriesMath.mortgageMinusTenYearSpread(emptyList(), emptyList()).isEmpty())
    }
}
