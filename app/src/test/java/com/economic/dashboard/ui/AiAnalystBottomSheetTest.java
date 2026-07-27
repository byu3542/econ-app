package com.economic.dashboard.ui;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for the Econ Analyst agent path's pure, side-effect-free
 * functions in AiAnalystBottomSheet (TICKET-I / review Q7). These were made
 * package-visible (were private) with no logic change so this test class can
 * call them directly.
 * Run with: ./gradlew test   (or right-click -> Run in Android Studio)
 */
public class AiAnalystBottomSheetTest {

    // ── isLookupAnnouncementOnly / stripDanglingAnnouncement ─────────────────

    @Test
    public void genuineDanglingAnnouncement_isDetected() {
        assertTrue(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "Let me check the CPI data for you."));
        assertTrue(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "One moment, I'll pull that up."));
        assertTrue(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "I'll fetch the historical values now."));
    }

    @Test
    public void plainQuestionAnswer_isNotAnnouncement() {
        assertFalse(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "Unemployment is currently at 4.1%, flat versus last quarter."));
    }

    /**
     * Constructed false positives from the review (F4 / TICKET-D): real,
     * complete analysis that happens to open with a first-person lead-in
     * phrase must NOT be treated as a dangling announcement.
     */
    @Test
    public void realAnalysisOpeningWithLeadIn_isNotFalsePositive() {
        assertFalse(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "I'll check the housing data: starts are up 3% and existing home "
                + "sales rose 1.2%, both pointing to a modest rebound in demand this spring."));
        assertFalse(AiAnalystBottomSheet.isLookupAnnouncementOnly(
                "One moment — actually let me just get straight to it: unemployment "
                + "is 4.1%, flat versus last quarter, and wage growth remains ahead of "
                + "inflation for now."));
    }

    @Test
    public void stripDanglingAnnouncement_dropsTrailingAnnouncementOnly() {
        String withDangler = "GDP grew 2.1% last quarter, driven by consumer spending. "
                + "Let me pull the historical series for more detail.";
        assertEquals("GDP grew 2.1% last quarter, driven by consumer spending.",
                AiAnalystBottomSheet.stripDanglingAnnouncement(withDangler));
    }

    @Test
    public void stripDanglingAnnouncement_keepsTextWithNoDangler() {
        String clean = "GDP grew 2.1% last quarter, driven by consumer spending.";
        assertEquals(clean, AiAnalystBottomSheet.stripDanglingAnnouncement(clean));
    }

    @Test
    public void stripDanglingAnnouncement_neverReturnsEmptyForAnnouncementOnlyText() {
        String onlyAnnouncement = "Let me check the CPI data for you.";
        assertEquals(onlyAnnouncement,
                AiAnalystBottomSheet.stripDanglingAnnouncement(onlyAnnouncement));
    }

    // ── isTransientFailure ────────────────────────────────────────────────────

    @Test
    public void nullMessage_isTreatedAsTransient() {
        assertTrue(AiAnalystBottomSheet.isTransientFailure(new IOException((String) null)));
    }

    @Test
    public void overloadedOrRateLimited_isTransient() {
        assertTrue(AiAnalystBottomSheet.isTransientFailure(
                new IOException("Econ Analyst service error (overloaded_error): server overloaded")));
        assertTrue(AiAnalystBottomSheet.isTransientFailure(
                new IOException("Econ Analyst service error (rate_limit_error): too many requests")));
    }

    @Test
    public void permanentFailures_areNotTransient() {
        assertFalse(AiAnalystBottomSheet.isTransientFailure(
                new IOException("invalid_request: bad schema")));
        assertFalse(AiAnalystBottomSheet.isTransientFailure(
                new IOException("authentication failed")));
        assertFalse(AiAnalystBottomSheet.isTransientFailure(
                new IOException("permission denied")));
        assertFalse(AiAnalystBottomSheet.isTransientFailure(
                new IOException("model not_found")));
    }

    // ── friendlyError ─────────────────────────────────────────────────────────

    @Test
    public void timeout_getsTimeoutMessage() {
        assertEquals("The response timed out — please try again.",
                AiAnalystBottomSheet.friendlyError(new SocketTimeoutException()));
    }

    @Test
    public void serviceError_passesMessageThrough() {
        String msg = "Econ Analyst service error (overloaded_error): server overloaded";
        assertEquals(msg, AiAnalystBottomSheet.friendlyError(new IOException(msg)));
    }

    @Test
    public void genericIOException_getsConnectionDroppedMessage() {
        assertEquals("The connection dropped while receiving the response — please try again.",
                AiAnalystBottomSheet.friendlyError(new IOException("stream closed")));
    }

    @Test
    public void nonIOException_getsGenericReadFailureMessage() {
        assertEquals("Couldn't read the response — please try again.",
                AiAnalystBottomSheet.friendlyError(new RuntimeException("boom")));
    }

    // ── chooseModel (AI Law 6) ────────────────────────────────────────────────

    @Test
    public void plainQuestion_firstRound_usesFastModel() {
        AiAnalystBottomSheet.StreamState st = new AiAnalystBottomSheet.StreamState();
        st.routeStrong = false;
        assertEquals("claude-haiku-4-5-20251001", AiAnalystBottomSheet.chooseModel(st, 0));
    }

    @Test
    public void gestureOrToolLikelyTurn_usesStrongModel() {
        AiAnalystBottomSheet.StreamState st = new AiAnalystBottomSheet.StreamState();
        st.routeStrong = true;
        assertEquals("claude-sonnet-5", AiAnalystBottomSheet.chooseModel(st, 0));
    }

    @Test
    public void anyToolLoopRound_usesStrongModelRegardlessOfRouteStrong() {
        AiAnalystBottomSheet.StreamState st = new AiAnalystBottomSheet.StreamState();
        st.routeStrong = false;
        assertEquals("claude-sonnet-5", AiAnalystBottomSheet.chooseModel(st, 1));
    }

    // ── looksToolLikely ───────────────────────────────────────────────────────

    @Test
    public void trendQuestions_lookToolLikely() {
        assertTrue(AiAnalystBottomSheet.looksToolLikely("How has the 10-year yield trended over the past year?"));
        assertTrue(AiAnalystBottomSheet.looksToolLikely("Compare CPI to last year"));
    }

    @Test
    public void plainCurrentValueQuestion_doesNotLookToolLikely() {
        assertFalse(AiAnalystBottomSheet.looksToolLikely("What is the current unemployment rate?"));
    }

    // ── freshnessTag (AI Law 15) ──────────────────────────────────────────────

    @Test
    public void recentDate_isNotStale() throws Exception {
        long now = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-07-27").getTime();
        assertEquals("", AiAnalystBottomSheet.freshnessTag("2026-07-01", 75, now));
    }

    @Test
    public void oldDate_isStale() throws Exception {
        long now = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-07-27").getTime();
        assertEquals(" [STALE — this reading may be outdated]",
                AiAnalystBottomSheet.freshnessTag("2026-01-01", 75, now));
    }

    @Test
    public void emptyDate_returnsEmptyTag() {
        assertEquals("", AiAnalystBottomSheet.freshnessTag("", 75, System.currentTimeMillis()));
        assertEquals("", AiAnalystBottomSheet.freshnessTag(null, 75, System.currentTimeMillis()));
    }
}
