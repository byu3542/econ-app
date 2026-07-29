package com.economic.dashboard.analyst

/**
 * Single source of truth for the Claude model ids the Econ Analyst routes
 * between (AI Law 6). Was a separate hardcoded literal in DailyBriefWorker,
 * independent of AiAnalystBottomSheet's constants (TICKET-F / review F9) — the
 * next model rotation now has one place to change instead of two.
 */
object ModelConfig {

    /** Fast/cheap model for plain typed, prompt-answered questions. */
    const val MODEL_FAST = "claude-haiku-4-5-20251001"

    /** Stronger model for gesture turns and tool-loop rounds — holds
     *  multi-round tool discipline and length rules far better than Haiku. */
    const val MODEL_STRONG = "claude-sonnet-5"
}
