package com.economic.dashboard.analyst

/**
 * Single source of truth for the Claude model ids the economii Analyst routes
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

    /**
     * AIC-05: the short name shown under an answer, derived from the id so a
     * model rotation needs no second edit: "claude-haiku-4-5-20251001" →
     * "Haiku 4.5", "claude-sonnet-5" → "Sonnet 5". Date stamps are dropped.
     */
    @JvmStatic
    fun label(modelId: String?): String {
        if (modelId.isNullOrEmpty()) return ""
        val parts = modelId.removePrefix("claude-").split('-')
        val name = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: return modelId
        val version = parts.drop(1).filter { it.length <= 2 && it.all(Char::isDigit) }
        return if (version.isEmpty()) name else name + " " + version.joinToString(".")
    }
}
