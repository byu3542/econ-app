package com.economic.dashboard.ui

import com.economic.dashboard.analyst.ModelConfig
import com.economic.dashboard.models.ChatChart
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.TurnSources
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure turn logic for the Econ Analyst: model routing, lookup-announcement
 * detection, error wording and snapshot freshness, plus the per-turn
 * [StreamState]. No views, no Android lifecycle.
 *
 * The chat UI moved to [com.economic.dashboard.ui.assistant.AssistantFragment]
 * (Pantrii port U3). These helpers keep this class name only because
 * AiAnalystBottomSheetTest calls them by it and app/src/test is hook-locked;
 * rename this holder when that test is carried over to its new owner.
 */
class AiAnalystBottomSheet private constructor() {

    /**
     * Carries streamed text across tool-use round-trips into one answer.
     *
     * Public (was package-private) and non-`inner` so AiAnalystBottomSheetTest
     * can still write `new AiAnalystBottomSheet.StreamState()`. Kotlin has no
     * package-private, and `internal` name-mangles at the bytecode level, which
     * would break that same-package Java test (migration lesson 5).
     *
     * `@JvmField` on every property is load-bearing: the test assigns
     * `st.routeStrong = false` as a RAW FIELD. A plain Kotlin `var` emits
     * getter/setter and no field at all, and the test stops compiling.
     */
    class StreamState {
        /** The answer so far. U7 (KTD2): accumulated off screen, bound once. */
        @JvmField val acc = StringBuilder()
        /** AI Law 6: true when every round of this turn should use the stronger
         *  model (gesture-initiated, or the question looks tool-likely). */
        @JvmField var routeStrong = false
        /** Total retries scheduled for this turn so far, across every
         *  sendTurn() invocation and tool-loop round. Capped at
         *  MAX_RETRIES_PER_TURN. */
        @JvmField var retryAttempts = 0
        /** TICKET-P2-2: charts rendered so far this turn, in call order.
         *  Carried onto the answer in finalizeTurn(), which binds them with
         *  its text (U7). */
        @JvmField val charts = ArrayList<ChatChart>()
        /** Rounds this turn continued purely to acknowledge render_chart calls.
         *  Those rounds are exempt from MAX_TOOL_DEPTH (a rendering directive
         *  is not a data lookup — R4/KTD2), so without their own cap the model
         *  could loop forever on free chart rounds. Capped at MAX_CHART_ROUNDS. */
        @JvmField var chartRounds = 0
        /** TICKET-P2-4: true when this turn came from a one-tap gesture, so
         *  finalizeTurn() offers follow-up chips derived from the answer. */
        @JvmField var gestureTurn = false
        /** MOT-04: what this turn's data tools actually returned, deduped.
         *  Handed to the answer bubble in finalizeTurn(); never persisted. */
        @JvmField val sources = TurnSources()
        /** AIC-05: model id of the latest round, for the label under the answer. */
        @JvmField var lastModel: String? = null
        /** AIC-05: answers this turn regenerates, oldest first. */
        @JvmField var priorVariants: List<ChatMessage.AnswerVariant> = emptyList()
    }

    companion object {


        private const val MODEL_FAST = ModelConfig.MODEL_FAST
        private const val MODEL_STRONG = ModelConfig.MODEL_STRONG

        /** Phrases that make a first round very likely to call get_series. */
        private val TOOL_LIKELY_HINTS = arrayOf(
            "history", "historical", "trend", "over the last", "over the past",
            "past year", "past month", "past few", "last 12", "last 24",
            "last year", "last month", "since ", "compare", "compared",
            "versus", " vs ", "chart", "graph", "turning point",
            "when did", "how has", "how have"
        )

        /** Lead-in phrase followed (within 80 non-terminal chars) by a tool-verb. */
        private val LOOKUP_ANNOUNCEMENT: Pattern = Pattern.compile(
            "(?is)\\b(let me|i'?ll|i will|i'?m going to|going to|one (moment|sec)|" +
                "give me a (moment|second)|hold on)\\b[^.!?]{0,80}" +
                "\\b(fetch|pull|look\\s*up|looking|check|grab|retriev\\w*|get|dig|query|load)\\w*")

        /** Max characters allowed after the matched verb for the round to still
         *  count as "nothing but an announcement" (TICKET-D / review F4). */
        private const val ANNOUNCEMENT_TAIL_SLACK = 60
        /**
         * AI Law 6 (match the model to the job): the single place that decides
         * which model a round uses. Haiku stays the default for plain typed,
         * prompt-answered questions; gesture turns, tool-likely questions, and
         * every round of an active tool loop (depth > 0) get the stronger model.
         *
         * Public + @JvmStatic (was package-private static) so the same-package
         * Java test can call it unqualified — `internal` would name-mangle and
         * omitting @JvmStatic would force `AiAnalystBottomSheet.Companion.…`.
         */
        @JvmStatic
        fun chooseModel(st: StreamState, depth: Int): String {
            return if (st.routeStrong || depth > 0) MODEL_STRONG else MODEL_FAST
        }

        /** AI Law 6: heuristic for questions that will almost certainly need a tool. */
        @JvmStatic
        fun looksToolLikely(q: String?): Boolean {
            if (q == null) return false
            val s = " " + q.lowercase(Locale.US) + " "
            for (hint in TOOL_LIKELY_HINTS)
                if (s.contains(hint)) return true
            return false
        }

        /**
         * AI Law 1: true when a round's text is nothing but a "let me look that up"
         * announcement — short, and promising a fetch/check/lookup, with little or
         * nothing following it. A reply that opens with a first-person verb but then
         * states a real, complete answer in the same breath (e.g. "I'll check the
         * housing data: starts are up 3%...") does NOT match, even though it
         * contains the same lead-in phrase — the announcement has to BE the reply,
         * not just open it.
         */
        @JvmStatic
        fun isLookupAnnouncementOnly(t: String?): Boolean {
            if (t == null) return false
            val s = t.trim()
            if (s.isEmpty() || s.length > 240) return false
            val m = LOOKUP_ANNOUNCEMENT.matcher(s)
            if (!m.find()) return false
            return s.length - m.end() <= ANNOUNCEMENT_TAIL_SLACK
        }

        /**
         * AI Law 1 fallback: if the reply ends with a dangling lookup-announcement
         * sentence but has real content before it, drop the dangling sentence.
         * Never leaves the reply empty.
         */
        @JvmStatic
        fun stripDanglingAnnouncement(text: String?): String {
            if (text == null) return ""
            val t = text.trim()
            var cut = -1
            for (i in t.length - 2 downTo 0) {
                val c = t[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n') { cut = i + 1; break }
            }
            if (cut <= 0) return text
            val tail = t.substring(cut).trim()
            if (!tail.isEmpty() && isLookupAnnouncementOnly(tail))
                return t.substring(0, cut).trim()
            return text
        }

        /** AI Law 5: only retry failures a retry can fix — never bad requests/auth. */
        @JvmStatic
        fun isTransientFailure(e: Exception): Boolean {
            val m = e.message ?: return true
            return !(m.contains("invalid_request") || m.contains("authentication") ||
                m.contains("permission") || m.contains("not_found"))
        }

        /** Maps low-level failures to a message that says what actually happened. */
        @JvmStatic
        fun friendlyError(e: Exception): String {
            if (e is java.net.SocketTimeoutException)
                return "The response timed out — please try again."
            val m = e.message
            if (m != null && m.startsWith("Econ Analyst service error")) return m
            if (e is IOException)
                return "The connection dropped while receiving the response — please try again."
            return "Couldn't read the response — please try again."
        }

        /**
         * AIC-07: the text shown in an error bubble. Raw server wording
         * ("API error 429", "overloaded_error") stays in Log.w only; the thrown
         * IOException messages are untouched because isTransientFailure parses them.
         */
        @JvmStatic
        fun plainError(code: Int?, e: Exception?, offline: Boolean): String {
            val m = e?.message ?: ""
            if (offline || e is java.net.UnknownHostException || e is java.net.ConnectException
                || e is java.net.NoRouteToHostException)
                return "You're offline. Check your connection and try again."
            if (code == 429 || m.contains("rate_limit_error"))
                return "The analyst is busy. Try again in a minute."
            if ((code != null && code >= 500) || m.contains("overloaded_error")
                || m.contains("api_error"))
                return "The analyst service is down. Please try again shortly."
            if (e is java.net.SocketTimeoutException) return friendlyError(e)
            if (code == null && e is IOException && !m.startsWith("Econ Analyst service error"))
                return friendlyError(e)
            return "Something went wrong. Please try again."
        }



        /**
         * Clock-injectable overload (matches CacheManager.isStale's pattern) so
         * AiAnalystBottomSheetTest can test staleness without a wall-clock race
         * (TICKET-I / review Q7).
         *
         * dateStr MUST stay nullable: the test asserts freshnessTag(null, …) == "".
         * A non-null parameter still compiles from Java but would throw at runtime
         * on Kotlin's generated null check (migration lesson 7).
         *
         * Kept as a separate function from the 2-arg form rather than collapsing
         * to a default argument — @JvmStatic + @JvmOverloads on a companion
         * function is awkward, and it would make the 2-arg form public.
         */
        @JvmStatic
        fun freshnessTag(dateStr: String?, maxAgeDays: Int, now: Long): String {
            if (dateStr == null || dateStr.isEmpty()) return ""
            val formats = arrayOf("yyyy-MM-dd", "yyyy-MM", "MMM yyyy", "MMMM yyyy", "MM/dd/yyyy")
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US)
                    sdf.isLenient = false
                    val d = sdf.parse(dateStr) ?: continue
                    val ageDays = (now - d.time) / 86_400_000L
                    return if (ageDays > maxAgeDays) " [STALE — this reading may be outdated]" else ""
                } catch (ignored: Exception) { }
            }
            return ""
        }
    }
}
