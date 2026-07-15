package com.economic.dashboard.cache;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for CacheManager's pure logic.
 * Run with: ./gradlew test   (or right-click → Run in Android Studio)
 */
public class CacheManagerTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY  = 24 * HOUR;

    // ── isStale ───────────────────────────────────────────────────────────────

    @Test
    public void emptyCache_isStale() {
        assertTrue(CacheManager.isStale(0, System.currentTimeMillis()));
    }

    @Test
    public void negativeTimestamp_isStale() {
        assertTrue(CacheManager.isStale(-5, System.currentTimeMillis()));
    }

    @Test
    public void freshCache_isNotStale() {
        long now = 1_750_000_000_000L;
        assertFalse(CacheManager.isStale(now - 1 * HOUR, now));          // 1 hour old
        assertFalse(CacheManager.isStale(now - 6 * DAY, now));           // 6 days old
        assertFalse(CacheManager.isStale(now - 7 * DAY + 1000, now));    // just under 7 days
    }

    @Test
    public void oldCache_isStale() {
        long now = 1_750_000_000_000L;
        assertTrue(CacheManager.isStale(now - 7 * DAY, now));    // exactly 7 days
        assertTrue(CacheManager.isStale(now - 30 * DAY, now));   // a month old
    }

    // ── computeCutoffDate (26-month retention) ───────────────────────────────

    @Test
    public void cutoffDate_is26MonthsBack() {
        assertEquals("2024-04-12",
                CacheManager.computeCutoffDate(LocalDate.of(2026, 6, 12)));
    }

    @Test
    public void cutoffDate_handlesYearBoundary() {
        assertEquals("2023-11-15",
                CacheManager.computeCutoffDate(LocalDate.of(2026, 1, 15)));
    }

    @Test
    public void cutoffDate_handlesShortMonths() {
        // 2026-03-31 minus 26 months → 2024-01-31 (January has 31 days, no clamp)
        assertEquals("2024-01-31",
                CacheManager.computeCutoffDate(LocalDate.of(2026, 3, 31)));
        // 2026-04-30 minus 26 months → 2024-02-29 (clamped to leap-day)
        assertEquals("2024-02-29",
                CacheManager.computeCutoffDate(LocalDate.of(2026, 4, 30)));
    }

    @Test
    public void cutoffDate_sortsLexicographically() {
        // The DAO compares "YYYY-MM-DD" strings with <. Verify a date inside
        // the retention window sorts AFTER the cutoff (i.e. is kept).
        String cutoff = CacheManager.computeCutoffDate(LocalDate.of(2026, 6, 12));
        assertTrue("2024-05-01".compareTo(cutoff) > 0);   // kept
        assertTrue("2024-04-11".compareTo(cutoff) < 0);   // purged
    }

    // ── formatAge ─────────────────────────────────────────────────────────────

    @Test
    public void formatAge_minutes() {
        assertEquals("just now",        CacheManager.formatAge(30_000));
        assertEquals("1 minute ago",    CacheManager.formatAge(90_000));
        assertEquals("35 minutes ago",  CacheManager.formatAge(35 * 60_000));
    }

    @Test
    public void formatAge_hoursAndDays() {
        assertEquals("1 hour ago",   CacheManager.formatAge(1 * HOUR + 5 * 60_000));
        assertEquals("2 hours ago",  CacheManager.formatAge(2 * HOUR));
        assertEquals("1 day ago",    CacheManager.formatAge(1 * DAY + 2 * HOUR));
        assertEquals("3 days ago",   CacheManager.formatAge(3 * DAY));
    }

    @Test
    public void formatAge_negativeTreatedAsZero() {
        assertEquals("just now", CacheManager.formatAge(-1000));
    }
}
