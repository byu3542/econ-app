package com.economic.dashboard.analyst;

import java.util.Calendar;
import java.util.Locale;

/**
 * Static schedule of upcoming U.S. economic data releases, formatted for the
 * AI Analyst system prompt so answers can reference what's coming ("watch
 * Thursday's CPI print"). FOMC and CPI dates are embedded for 2026; the jobs
 * report is computed (first Friday of the month, 8:30 AM ET).
 */
public class ReleaseCalendar {

    /** {month, startDay, endDay} — 2026 FOMC meeting schedule. */
    private static final int[][] FOMC_2026 = {
            {1, 27, 28}, {3, 17, 18}, {4, 28, 29}, {6, 16, 17},
            {7, 28, 29}, {9, 15, 16}, {10, 27, 28}, {12, 8, 9}
    };

    /** {month, day} — 2026 BLS CPI release dates (8:30 AM ET, approximate schedule). */
    private static final int[][] CPI_2026 = {
            {1, 13}, {2, 11}, {3, 11}, {4, 10}, {5, 12}, {6, 10},
            {7, 14}, {8, 12}, {9, 10}, {10, 13}, {11, 12}, {12, 10}
    };

    private static final String[] MONTH_ABBR = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    /** @return "UPCOMING DATA RELEASES" block for the system prompt, or "" past 2026. */
    public static String build() {
        Calendar now = Calendar.getInstance();
        StringBuilder sb = new StringBuilder("\nUPCOMING DATA RELEASES\n");

        String fomc = nextFomc(now);
        if (fomc != null) sb.append("Next FOMC meeting: ").append(fomc).append("\n");

        String cpi = nextCpi(now);
        if (cpi != null) sb.append("Next CPI release: ").append(cpi).append(" (8:30 AM ET)\n");

        sb.append("Next jobs report (BLS Employment Situation): ")
          .append(nextJobsReport(now)).append(" (8:30 AM ET)\n");

        sb.append("GDP estimates (BEA): released quarterly, typically the last week of Jan/Apr/Jul/Oct.\n");
        return sb.toString();
    }

    private static String nextFomc(Calendar now) {
        if (now.get(Calendar.YEAR) != 2026) return null;
        int month = now.get(Calendar.MONTH) + 1, day = now.get(Calendar.DAY_OF_MONTH);
        for (int[] m : FOMC_2026)
            if (m[0] > month || (m[0] == month && m[2] >= day))
                return String.format(Locale.US, "%s %d–%d, 2026", MONTH_ABBR[m[0]-1], m[1], m[2]);
        return null;
    }

    private static String nextCpi(Calendar now) {
        if (now.get(Calendar.YEAR) != 2026) return null;
        int month = now.get(Calendar.MONTH) + 1, day = now.get(Calendar.DAY_OF_MONTH);
        for (int[] m : CPI_2026)
            if (m[0] > month || (m[0] == month && m[1] >= day))
                return String.format(Locale.US, "%s %d, 2026", MONTH_ABBR[m[0]-1], m[1]);
        return null;
    }

    /** First Friday of this month if still ahead, otherwise of next month. */
    private static String nextJobsReport(Calendar now) {
        Calendar c = (Calendar) now.clone();
        for (int attempt = 0; attempt < 2; attempt++) {
            Calendar ff = (Calendar) c.clone();
            ff.set(Calendar.DAY_OF_MONTH, 1);
            while (ff.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
                ff.add(Calendar.DAY_OF_YEAR, 1);
            if (!ff.before(stripTime(now)))
                return String.format(Locale.US, "%s %d, %d",
                        MONTH_ABBR[ff.get(Calendar.MONTH)], ff.get(Calendar.DAY_OF_MONTH), ff.get(Calendar.YEAR));
            c.add(Calendar.MONTH, 1);
        }
        return "first Friday of next month";
    }

    private static Calendar stripTime(Calendar src) {
        Calendar c = (Calendar) src.clone();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
