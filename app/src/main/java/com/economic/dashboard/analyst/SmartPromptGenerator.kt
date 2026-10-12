package com.economic.dashboard.analyst

import com.economic.dashboard.models.TurnSource
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

    /**
     * Follow-up questions derived from an analyst ANSWER, not a headline
     * (TICKET-P2-4, truncated pyramid). The 60–90-word gesture tier keeps only
     * what was asked; the detail it leaves out gets somewhere to go — chips
     * keyed to the topics the answer actually mentioned. Plain text, no emoji:
     * these render in the quick-ask chip row, whose chips are unadorned.
     */
    @JvmStatic
    @JvmOverloads
    fun generateFollowUpsForAnswer(
        answer: String?, sources: List<TurnSource> = emptyList()
    ): List<String> {
        val followUps = ArrayList<String>()
        if (answer == null || answer.trim().isEmpty()) return followUps
        // Locale.US for the same Turkish-I reason as generatePromptsForHeadline.
        val text = answer.lowercase(Locale.US)

        // AIC-11: the series this answer actually fetched come first, so the
        // follow-ups are about this answer rather than its topic in general.
        for (s in sources) {
            if (s.kind != TurnSource.Kind.SERIES || followUps.size >= 2) continue
            followUps.add("How has the ${s.title} moved over the past year?")
        }

        if (text.contains("inflation") || text.contains("cpi") || text.contains("pce") || text.contains("prices"))
            followUps.add("How does inflation compare historically?")
        if (text.contains("unemployment") || text.contains("jobs") || text.contains("labor") || text.contains("employment"))
            followUps.add("What's the trend in the labor market?")
        if (text.contains("yield") || text.contains("treasury") || text.contains("spread") || text.contains("curve"))
            followUps.add("What is the yield curve signaling?")
        if (text.contains("fed") || text.contains("federal reserve") || text.contains("rate cut") || text.contains("rate hike"))
            followUps.add("What's the Fed's likely next move?")
        if (text.contains("recession") || text.contains("slowdown") || text.contains("contraction"))
            followUps.add("What's the current recession risk?")
        if (text.contains("mortgage") || text.contains("housing") || text.contains("home"))
            followUps.add("What does this mean for mortgage rates?")
        if (text.contains("wage") || text.contains("earnings") || text.contains("income"))
            followUps.add("Are real wages keeping up with inflation?")
        if (text.contains("market") || text.contains("s&p") || text.contains("stock") || text.contains("nasdaq") || text.contains("vix"))
            followUps.add("How are markets pricing this in?")

        // Depth extenders so a match-poor answer still offers the second level.
        if (followUps.size < 2) followUps.add("What's driving this?")
        if (followUps.size < 2) followUps.add("What should I watch next?")

        return followUps.subList(0, Math.min(3, followUps.size))
    }
}
