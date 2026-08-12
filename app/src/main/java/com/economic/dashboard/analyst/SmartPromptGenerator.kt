package com.economic.dashboard.analyst

import java.util.Locale

object SmartPromptGenerator {

    /** Keyword-matched prompts for any raw headline string (used by the chat sheet). */
    @JvmStatic
    fun generatePromptsForHeadline(headline: String?): List<String> {
        val prompts = ArrayList<String>()
        // Locale.US, not getDefault(): the keywords below are English ASCII
        // literals, and on a Turkish-locale device "I" lowercases to "ı", so
        // headlines containing a capital I would fail to match their bucket.
        // Matches the convention used at every other case-conversion site.
        val title = if (headline == null) "" else headline.lowercase(Locale.US)

        if (title.contains("fed") || title.contains("federal reserve") || title.contains("interest rate")) {
            prompts.add("🏦 How might this Fed action affect Treasury yields and bond prices?")
            prompts.add("📊 What's the historical impact of similar Fed decisions on the economy?")
            prompts.add("💰 How could this change affect mortgage rates and real estate market?")
        }
        if (title.contains("inflation") || title.contains("cpi") || title.contains("prices")) {
            prompts.add("📈 Is inflation accelerating or decelerating based on this data?")
            prompts.add("💵 How might persistent inflation affect the Fed's next move?")
            prompts.add("🛒 What sectors are most affected by these price changes?")
        }
        if (title.contains("unemployment") || title.contains("jobs") || title.contains("employment") || title.contains("jobless")) {
            prompts.add("👥 What does this employment trend signal about economic health?")
            prompts.add("💼 How does this compare to pre-pandemic employment levels?")
            prompts.add("📊 What could weaker/stronger job growth mean for Fed policy?")
        }
        if (title.contains("gdp") || title.contains("growth") || title.contains("recession") || title.contains("contraction")) {
            prompts.add("📊 Is the economy accelerating or slowing based on this data?")
            prompts.add("🔮 What's the recession risk if growth trends continue?")
            prompts.add("📈 How does this growth rate compare to historical averages?")
        }
        if (title.contains("housing") || title.contains("real estate") || title.contains("mortgage") || title.contains("home")) {
            prompts.add("🏠 How are rising/falling home prices affecting affordability?")
            prompts.add("💳 What's the relationship between mortgage rates and housing demand?")
            prompts.add("📍 Which regions are most affected by housing market changes?")
        }
        if (title.contains("debt") || title.contains("deficit") || title.contains("treasury") || title.contains("bonds")) {
            prompts.add("💳 How is growing national debt affecting interest rates?")
            prompts.add("📉 What's the sustainability risk of current deficit levels?")
            prompts.add("🌍 How does U.S. debt compare to other developed nations?")
        }
        if (title.contains("wage") || title.contains("salary") || title.contains("earnings") || title.contains("income")) {
            prompts.add("💰 Is wage growth keeping pace with inflation?")
            prompts.add("📊 How do these wage trends compare across industries?")
            prompts.add("👥 What does wage pressure mean for corporate profitability?")
        }
        if (title.contains("stock") || title.contains("market") || title.contains("dow") || title.contains("s&p 500") || title.contains("nasdaq")) {
            prompts.add("📈 What economic factors are driving market volatility?")
            prompts.add("💡 How are earnings expectations affecting valuations?")
            prompts.add("🔗 What's the correlation between market moves and economic indicators?")
        }
        if (prompts.size < 3) {
            prompts.add("🔍 What are the key takeaways from this economic news?")
            prompts.add("📊 How does this affect the broader economic outlook?")
            prompts.add("💭 What should policymakers do in response?")
        }

        return prompts.subList(0, Math.min(5, prompts.size))
    }
}
