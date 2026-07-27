package com.economic.dashboard.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * JVM unit tests for ChatAdapter's pure text-cleanup functions (TICKET-I /
 * review Q7). These were made package-visible (were private) with no logic
 * change so this test class can call them directly.
 * Run with: ./gradlew test   (or right-click -> Run in Android Studio)
 */
public class ChatAdapterTest {

    // ── stripChartTags ────────────────────────────────────────────────────────

    @Test
    public void removesChartTagAndTrailingWhitespace() {
        assertEquals("Housing starts are climbing.",
                ChatAdapter.stripChartTags("Housing starts are climbing. [CHART:HOUSING_STARTS:12M]"));
    }

    @Test
    public void collapsesBlankLineLeftByRemovedTag() {
        assertEquals("Para one.\n\nPara two.",
                ChatAdapter.stripChartTags("Para one.\n[CHART:CPI:24M]\n\nPara two."));
    }

    @Test
    public void leavesTextWithoutTagsUnchanged() {
        assertEquals("Plain text with no tags", ChatAdapter.stripChartTags("Plain text with no tags"));
    }

    @Test
    public void nullText_returnsEmptyString() {
        assertEquals("", ChatAdapter.stripChartTags(null));
    }

    // ── stripStrayMarkdown (AI Law 10) ────────────────────────────────────────

    @Test
    public void markdownHeader_becomesBoldLine() {
        assertEquals("**Fed Funds Rate**\nSome text",
                ChatAdapter.stripStrayMarkdown("## Fed Funds Rate\nSome text"));
    }

    @Test
    public void dashBulletList_becomesDotBulletsOnSeparateLines() {
        assertEquals("• First point\n\n• Second point",
                ChatAdapter.stripStrayMarkdown("- First point\n- Second point"));
    }

    @Test
    public void plainText_isUnchanged() {
        String plain = "Plain sentence with no markdown.";
        assertEquals(plain, ChatAdapter.stripStrayMarkdown(plain));
    }

    @Test
    public void nullText_isReturnedAsIs() {
        assertNull(ChatAdapter.stripStrayMarkdown(null));
    }

    @Test
    public void emptyText_isReturnedAsIs() {
        assertEquals("", ChatAdapter.stripStrayMarkdown(""));
    }
}
