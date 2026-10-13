package com.economic.dashboard.ui.assistant

import kotlin.random.Random

/**
 * Pantrii port U7 (R5, KTD9): the words the assistant loader shows while a
 * reply is pending. Pure Kotlin, so it runs in a JVM test.
 *
 * [next] deals the themed phrases from a shuffled bag: every phrase appears
 * once before any repeats, and the same phrase never shows twice in a row,
 * including across a reshuffle. [forTool] gives the phrase that overrides the
 * rotation while a tool round runs (AE1).
 *
 * @param phrases themed phrases; duplicates are dropped. Must not be empty.
 * @param toolPhrases tool name → phrase. A phrase may hold [SUBJECT], which
 *   [forTool] fills with the series being pulled or drops when none is known.
 */
class LoadingPhrases(
    phrases: List<String>,
    private val toolPhrases: Map<String, String> = emptyMap(),
    private val random: Random = Random.Default,
) {
    private val all = phrases.distinct()
    private val bag = ArrayDeque<String>()
    private var last: String? = null

    init {
        require(all.isNotEmpty()) { "LoadingPhrases needs at least one phrase" }
    }

    /** The next themed phrase. A one-phrase list can only repeat itself. */
    fun next(): String {
        if (bag.isEmpty()) refill()
        val phrase = bag.removeFirst()
        last = phrase
        return phrase
    }

    private fun refill() {
        val order = all.shuffled(random).toMutableList()
        // A fresh bag must not open with the phrase the last one closed on.
        if (order.size > 1 && order[0] == last) {
            val j = 1 + random.nextInt(order.size - 1)
            order[0] = order[j].also { order[j] = order[0] }
        }
        bag.addAll(order)
    }

    /** The phrase for tool [name], with [subject] (e.g. "CPI") filled in;
     *  null for a tool with no phrase. */
    fun forTool(name: String, subject: String? = null): String? {
        val phrase = toolPhrases[name] ?: return null
        return if (subject.isNullOrBlank()) phrase.replace("$SUBJECT ", "").replace(SUBJECT, "")
        else phrase.replace(SUBJECT, subject.trim())
    }

    companion object {
        /** Placeholder for the series a tool round is pulling. */
        const val SUBJECT = "{series}"

        /** KTD9: a themed phrase stays this long before the next crossfades in. */
        const val ROTATE_MS = 1800L

        /** A phrase that just appeared is not replaced sooner than this, so a
         *  quick tool round never flashes its phrase. */
        const val MIN_PHRASE_MS = 900L

        /** The loader stays at least this long, so a fast reply shows one
         *  readable phrase instead of a flicker. */
        const val MIN_VISIBLE_MS = 1000L
    }
}
