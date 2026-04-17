package com.economic.dashboard.analyst;

import com.economic.dashboard.models.NewsArticle;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates smart, contextual prompts for the AI Economic Analyst.
 *
 * Instead of users typing prompts manually, this suggests 3-5 relevant questions
 * based on the selected article(s) and current economic context.
 *
 * Examples:
 * - "How would a Fed rate hike affect Treasury yields?" (from Fed headline)
 * - "What does higher inflation mean for my mortgage?" (from inflation headline)
 * - "Compare this unemployment report to the historical trend" (from jobs headline)
 */
public class SmartPromptGenerator {

    /**
     * Generate smart prompts for a single article.
     *
     * @param article The news article to analyze
     * @return List of 3-5 suggested prompts
     */
    public static List<String> generatePromptsForArticle(NewsArticle article) {
        List<String> prompts = new ArrayList<>();
        String title = article.title.toLowerCase();
        String content = (article.description + " " + article.content).toLowerCase();

        // Fed/Interest Rate topics
        if (contains(title, "fed") || contains(title, "federal reserve") || contains(title, "interest rate")) {
            prompts.add("🏦 How might this Fed action affect Treasury yields and bond prices?");
            prompts.add("📊 What's the historical impact of similar Fed decisions on the economy?");
            prompts.add("💰 How could this change affect mortgage rates and real estate market?");
        }

        // Inflation topics
        if (contains(title, "inflation") || contains(title, "cpi") || contains(title, "prices")) {
            prompts.add("📈 Is inflation accelerating or decelerating based on this data?");
            prompts.add("💵 How might persistent inflation affect the Fed's next move?");
            prompts.add("🛒 What sectors are most affected by these price changes?");
        }

        // Employment topics
        if (contains(title, "unemployment") || contains(title, "jobs") || contains(title, "employment") || contains(title, "jobless")) {
            prompts.add("👥 What does this employment trend signal about economic health?");
            prompts.add("💼 How does this compare to pre-pandemic employment levels?");
            prompts.add("📊 What could weaker/stronger job growth mean for Fed policy?");
        }

        // GDP/Growth topics
        if (contains(title, "gdp") || contains(title, "growth") || contains(title, "recession") || contains(title, "contraction")) {
            prompts.add("📊 Is the economy accelerating or slowing based on this data?");
            prompts.add("🔮 What's the recession risk if growth trends continue?");
            prompts.add("📈 How does this growth rate compare to historical averages?");
        }

        // Housing/Real Estate topics
        if (contains(title, "housing") || contains(title, "real estate") || contains(title, "mortgage") || contains(title, "home")) {
            prompts.add("🏠 How are rising/falling home prices affecting affordability?");
            prompts.add("💳 What's the relationship between mortgage rates and housing demand?");
            prompts.add("📍 Which regions are most affected by housing market changes?");
        }

        // Debt/Deficit topics
        if (contains(title, "debt") || contains(title, "deficit") || contains(title, "treasury") || contains(title, "bonds")) {
            prompts.add("💳 How is growing national debt affecting interest rates?");
            prompts.add("📉 What's the sustainability risk of current deficit levels?");
            prompts.add("🌍 How does U.S. debt compare to other developed nations?");
        }

        // Wage/Income topics
        if (contains(title, "wage") || contains(title, "salary") || contains(title, "earnings") || contains(title, "income")) {
            prompts.add("💰 Is wage growth keeping pace with inflation?");
            prompts.add("📊 How do these wage trends compare across industries?");
            prompts.add("👥 What does wage pressure mean for corporate profitability?");
        }

        // Stock Market topics
        if (contains(title, "stock") || contains(title, "market") || contains(title, "dow") || contains(title, "s&p 500") || contains(title, "nasdaq")) {
            prompts.add("📈 What economic factors are driving market volatility?");
            prompts.add("💡 How are earnings expectations affecting valuations?");
            prompts.add("🔗 What's the correlation between market moves and economic indicators?");
        }

        // Generic fallback prompts (always included)
        if (prompts.size() < 3) {
            prompts.add("🔍 What are the key takeaways from this economic news?");
            prompts.add("📊 How does this affect the broader economic outlook?");
            prompts.add("💭 What should policymakers do in response?");
        }

        // Limit to 5 prompts for UI cleanliness
        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    /**
     * Generate prompts comparing multiple articles (trending analysis).
     *
     * @param articles Multiple articles about related topics
     * @return List of comparative prompts
     */
    public static List<String> generateComparativePrompts(List<NewsArticle> articles) {
        List<String> prompts = new ArrayList<>();

        if (articles.isEmpty()) return prompts;

        // Multi-article prompts
        prompts.add("🔗 How are these economic stories connected?");
        prompts.add("📊 What's the common theme across these headlines?");
        prompts.add("🎯 Which story is most significant for long-term economic outlook?");
        prompts.add("⚖️ Are these stories bullish or bearish for the economy?");
        prompts.add("🔮 Based on these trends, what should we expect next month?");

        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    /**
     * Generate prompts about impact on specific asset classes.
     *
     * @param article Article to analyze
     * @param assetClass "stocks", "bonds", "crypto", "commodities"
     * @return Impact-specific prompts
     */
    public static List<String> generateAssetImpactPrompts(NewsArticle article, String assetClass) {
        List<String> prompts = new ArrayList<>();

        switch (assetClass.toLowerCase()) {
            case "stocks":
                prompts.add("📈 How should stock investors respond to this news?");
                prompts.add("🎯 Which stock sectors benefit or suffer from this?");
                prompts.add("💹 What valuation changes should we expect?");
                break;

            case "bonds":
                prompts.add("📊 How will this affect Treasury yields?");
                prompts.add("💰 What's the credit risk implication?");
                prompts.add("🔄 How should bond portfolio allocations change?");
                break;

            case "real_estate":
                prompts.add("🏠 How does this affect home prices?");
                prompts.add("💳 Will mortgage rates adjust in response?");
                prompts.add("📍 Which regions are most impacted?");
                break;

            case "crypto":
                prompts.add("💰 How does this macro news affect crypto sentiment?");
                prompts.add("📉 Are we heading toward risk-on or risk-off markets?");
                prompts.add("🔗 How do Fed actions correlate with Bitcoin price?");
                break;

            default:
                prompts.add("💼 What's the business impact of this news?");
                break;
        }

        return prompts;
    }

    /**
     * Generate prompts for forecasting/outlook analysis.
     *
     * @param article Article about current economic condition
     * @return Forward-looking prompts
     */
    public static List<String> generateOutlookPrompts(NewsArticle article) {
        List<String> prompts = new ArrayList<>();

        prompts.add("🔮 What does this trend suggest for the next quarter?");
        prompts.add("📅 When might we see the impact of this development?");
        prompts.add("⚠️ What are the upside and downside risks?");
        prompts.add("🎯 What leading indicators should we watch?");
        prompts.add("💡 How should policy respond to prevent negative outcomes?");

        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    /**
     * Generate historical context prompts.
     *
     * @param article Article about current event
     * @return Historical comparison prompts
     */
    public static List<String> generateHistoricalPrompts(NewsArticle article) {
        List<String> prompts = new ArrayList<>();

        prompts.add("📚 How does this compare to similar events in history?");
        prompts.add("📈 What was the outcome the last time this happened?");
        prompts.add("🔄 Are we repeating a historical pattern?");
        prompts.add("⏱️ How long did recovery take in previous cycles?");
        prompts.add("💭 What did economists get wrong last time?");

        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    /**
     * Simple helper: check if text contains keyword (case-insensitive).
     */
    private static boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }

    /**
     * Detect which economic category an article belongs to.
     */
    public enum EconomicCategory {
        FEDERAL_RESERVE,
        INFLATION,
        EMPLOYMENT,
        GDP_GROWTH,
        HOUSING,
        DEBT_DEFICIT,
        WAGES,
        STOCK_MARKET,
        OTHER;

        public static EconomicCategory detectFromArticle(NewsArticle article) {
            String text = (article.title + " " + article.description).toLowerCase();

            if (text.contains("fed") || text.contains("federal reserve")) return FEDERAL_RESERVE;
            if (text.contains("inflation") || text.contains("cpi")) return INFLATION;
            if (text.contains("unemployment") || text.contains("jobs")) return EMPLOYMENT;
            if (text.contains("gdp") || text.contains("growth")) return GDP_GROWTH;
            if (text.contains("housing") || text.contains("real estate")) return HOUSING;
            if (text.contains("debt") || text.contains("deficit")) return DEBT_DEFICIT;
            if (text.contains("wage") || text.contains("salary")) return WAGES;
            if (text.contains("stock") || text.contains("market")) return STOCK_MARKET;

            return OTHER;
        }
    }
}
