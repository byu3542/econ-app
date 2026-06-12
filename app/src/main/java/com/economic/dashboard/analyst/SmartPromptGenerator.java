package com.economic.dashboard.analyst;

import com.economic.dashboard.models.NewsArticle;

import java.util.ArrayList;
import java.util.List;

public class SmartPromptGenerator {

    public static List<String> generatePromptsForArticle(NewsArticle article) {
        List<String> prompts = new ArrayList<>();
        String title = article.title.toLowerCase();

        if (contains(title, "fed") || contains(title, "federal reserve") || contains(title, "interest rate")) {
            prompts.add("🏦 How might this Fed action affect Treasury yields and bond prices?");
            prompts.add("📊 What's the historical impact of similar Fed decisions on the economy?");
            prompts.add("💰 How could this change affect mortgage rates and real estate market?");
        }
        if (contains(title, "inflation") || contains(title, "cpi") || contains(title, "prices")) {
            prompts.add("📈 Is inflation accelerating or decelerating based on this data?");
            prompts.add("💵 How might persistent inflation affect the Fed's next move?");
            prompts.add("🛒 What sectors are most affected by these price changes?");
        }
        if (contains(title, "unemployment") || contains(title, "jobs") || contains(title, "employment") || contains(title, "jobless")) {
            prompts.add("👥 What does this employment trend signal about economic health?");
            prompts.add("💼 How does this compare to pre-pandemic employment levels?");
            prompts.add("📊 What could weaker/stronger job growth mean for Fed policy?");
        }
        if (contains(title, "gdp") || contains(title, "growth") || contains(title, "recession") || contains(title, "contraction")) {
            prompts.add("📊 Is the economy accelerating or slowing based on this data?");
            prompts.add("🔮 What's the recession risk if growth trends continue?");
            prompts.add("📈 How does this growth rate compare to historical averages?");
        }
        if (contains(title, "housing") || contains(title, "real estate") || contains(title, "mortgage") || contains(title, "home")) {
            prompts.add("🏠 How are rising/falling home prices affecting affordability?");
            prompts.add("💳 What's the relationship between mortgage rates and housing demand?");
            prompts.add("📍 Which regions are most affected by housing market changes?");
        }
        if (contains(title, "debt") || contains(title, "deficit") || contains(title, "treasury") || contains(title, "bonds")) {
            prompts.add("💳 How is growing national debt affecting interest rates?");
            prompts.add("📉 What's the sustainability risk of current deficit levels?");
            prompts.add("🌍 How does U.S. debt compare to other developed nations?");
        }
        if (contains(title, "wage") || contains(title, "salary") || contains(title, "earnings") || contains(title, "income")) {
            prompts.add("💰 Is wage growth keeping pace with inflation?");
            prompts.add("📊 How do these wage trends compare across industries?");
            prompts.add("👥 What does wage pressure mean for corporate profitability?");
        }
        if (contains(title, "stock") || contains(title, "market") || contains(title, "dow") || contains(title, "s&p 500") || contains(title, "nasdaq")) {
            prompts.add("📈 What economic factors are driving market volatility?");
            prompts.add("💡 How are earnings expectations affecting valuations?");
            prompts.add("🔗 What's the correlation between market moves and economic indicators?");
        }
        if (prompts.size() < 3) {
            prompts.add("🔍 What are the key takeaways from this economic news?");
            prompts.add("📊 How does this affect the broader economic outlook?");
            prompts.add("💭 What should policymakers do in response?");
        }

        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    public static List<String> generateComparativePrompts(List<NewsArticle> articles) {
        List<String> prompts = new ArrayList<>();
        if (articles.isEmpty()) return prompts;
        prompts.add("🔗 How are these economic stories connected?");
        prompts.add("📊 What's the common theme across these headlines?");
        prompts.add("🎯 Which story is most significant for long-term economic outlook?");
        prompts.add("⚖️ Are these stories bullish or bearish for the economy?");
        prompts.add("🔮 Based on these trends, what should we expect next month?");
        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    public static List<String> generateOutlookPrompts(NewsArticle article) {
        List<String> prompts = new ArrayList<>();
        prompts.add("🔮 What does this trend suggest for the next quarter?");
        prompts.add("📅 When might we see the impact of this development?");
        prompts.add("⚠️ What are the upside and downside risks?");
        prompts.add("🎯 What leading indicators should we watch?");
        prompts.add("💡 How should policy respond to prevent negative outcomes?");
        return prompts.subList(0, Math.min(5, prompts.size()));
    }

    private static boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }

    public enum EconomicCategory {
        FEDERAL_RESERVE, INFLATION, EMPLOYMENT, GDP_GROWTH, HOUSING, DEBT_DEFICIT, WAGES, STOCK_MARKET, OTHER;

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
