package com.economic.dashboard.analyst

import java.util.Calendar
import java.util.Locale

/**
 * Static schedule of upcoming U.S. economic data releases, formatted for the
 * AI Analyst system prompt so answers can reference what's coming ("watch
 * Thursday's CPI print"). FOMC and CPI dates are embedded for 2026; the jobs
 * report is computed (first Friday of the month, 8:30 AM ET).
 */
object ReleaseCalendar {

    /** {month, startDay, endDay} — 2026 FOMC meeting schedule. */
    private val FOMC_2026 = arrayOf(
        intArrayOf(1, 27, 28), intArrayOf(3, 17, 18), intArrayOf(4, 28, 29), intArrayOf(6, 16, 17),
        intArrayOf(7, 28, 29), intArrayOf(9, 15, 16), intArrayOf(10, 27, 28), intArrayOf(12, 8, 9)
    )

    /** {month, day} — 2026 BLS CPI release dates (8:30 AM ET, approximate schedule). */
    private val CPI_2026 = arrayOf(
        intArrayOf(1, 13), intArrayOf(2, 11), intArrayOf(3, 11), intArrayOf(4, 10),
        intArrayOf(5, 12), intArrayOf(6, 10), intArrayOf(7, 14), intArrayOf(8, 12),
        intArrayOf(9, 10), intArrayOf(10, 13), intArrayOf(11, 12), intArrayOf(12, 10)
    )

    private val MONTH_ABBR = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** @return "UPCOMING DATA RELEASES" block for the system prompt, or "" past 2026. */
    @JvmStatic
    fun build(): String {
        val now = Calendar.getInstance()
        val sb = StringBuilder("\nUPCOMING DATA RELEASES\n")

        val fomc = nextFomc(now)
        if (fomc != null) sb.append("Next FOMC meeting: ").append(fomc).append("\n")

        val cpi = nextCpi(now)
        if (cpi != null) sb.append("Next CPI release: ").append(cpi).append(" (8:30 AM ET)\n")

        sb.append("Next jobs report (BLS Employment Situation): ")
            .append(nextJobsReport(now)).append(" (8:30 AM ET)\n")

        sb.append("GDP estimates (BEA): released quarterly, typically the last week of Jan/Apr/Jul/Oct.\n")
        return sb.toString()
    }

    private fun nextFomc(now: Calendar): String? {
        if (now.get(Calendar.YEAR) != 2026) return null
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        for (m in FOMC_2026)
            if (m[0] > month || (m[0] == month && m[2] >= day))
                return String.format(Locale.US, "%s %d–%d, 2026", MONTH_ABBR[m[0] - 1], m[1], m[2])
        return null
    }

    private fun nextCpi(now: Calendar): String? {
        if (now.get(Calendar.YEAR) != 2026) return null
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        for (m in CPI_2026)
            if (m[0] > month || (m[0] == month && m[1] >= day))
                return String.format(Locale.US, "%s %d, 2026", MONTH_ABBR[m[0] - 1], m[1])
        return null
    }

    /** First Friday of this month if still ahead, otherwise of next month. */
    private fun nextJobsReport(now: Calendar): String {
        val c = now.clone() as Calendar
        for (attempt in 0 until 2) {
            val ff = c.clone() as Calendar
            ff.set(Calendar.DAY_OF_MONTH, 1)
            while (ff.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
                ff.add(Calendar.DAY_OF_YEAR, 1)
            if (!ff.before(stripTime(now)))
                return String.format(Locale.US, "%s %d, %d",
                    MONTH_ABBR[ff.get(Calendar.MONTH)], ff.get(Calendar.DAY_OF_MONTH), ff.get(Calendar.YEAR))
            c.add(Calendar.MONTH, 1)
        }
        return "first Friday of next month"
    }

    private fun stripTime(src: Calendar): Calendar {
        val c = src.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c
    }
}
