package com.economic.dashboard.api;

import android.util.Log;

import com.economic.dashboard.models.NewsArticle;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Client for NewsAPI.org integration.
 *
 * Fetches full economic news articles from NewsAPI.org.
 * Requires API key from https://newsapi.org
 *
 * Free tier: 100 requests/day, 5,000 articles/month
 * Perfect for economic news monitoring.
 */
public class NewsApiClient {

    private static final String TAG = "NewsApiClient";
    private static final String BASE_URL = "https://newsapi.org/v2";
    private static final String ENDPOINT_EVERYTHING = "/everything";

    // Get your free API key at https://newsapi.org
    // Add to ApiConfig.java: public static final String NEWS_API_KEY = "your_key_here";
    private final String apiKey;

    public interface NewsCallback {
        void onSuccess(List<NewsArticle> articles);
        void onError(String error);
    }

    public NewsApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Search for economic news articles.
     *
     * @param query Search query (e.g., "Federal Reserve", "inflation", "GDP")
     * @param sortBy Sort order: "relevancy", "popularity", "publishedAt"
     * @param callback Results callback
     */
    public void searchNews(String query, String sortBy, NewsCallback callback) {
        new Thread(() -> {
            try {
                String url = BASE_URL + ENDPOINT_EVERYTHING +
                        "?q=" + urlEncode(query) +
                        "&sortBy=" + sortBy +
                        "&language=en" +
                        "&apiKey=" + apiKey;

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("NewsAPI HTTP error: " + response.code());
                    return;
                }

                String jsonResponse = response.body().string();
                List<NewsArticle> articles = parseNewsResponse(jsonResponse);

                if (articles.isEmpty()) {
                    callback.onError("No articles found for: " + query);
                } else {
                    Log.d(TAG, "Fetched " + articles.size() + " articles for: " + query);
                    callback.onSuccess(articles);
                }

            } catch (Exception e) {
                Log.e(TAG, "NewsAPI fetch error", e);
                callback.onError("NewsAPI fetch failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Search for economic news in the last 7 days.
     * Convenience method with sensible defaults.
     */
    public void searchRecentEconomicNews(String query, NewsCallback callback) {
        // Sort by recency for latest news
        searchNews(query, "publishedAt", callback);
    }

    /**
     * Get top economic stories trending today.
     */
    public void getTrendingEconomicNews(NewsCallback callback) {
        searchNews(
                "Federal Reserve OR inflation OR GDP OR unemployment OR interest rates",
                "popularity",
                callback
        );
    }

    /**
     * Get news about a specific economic topic.
     */
    public void searchByTopic(EconomicTopic topic, NewsCallback callback) {
        searchRecentEconomicNews(topic.getSearchQuery(), callback);
    }

    /**
     * Parse JSON response from NewsAPI.org into NewsArticle objects.
     */
    private List<NewsArticle> parseNewsResponse(String jsonResponse) throws Exception {
        List<NewsArticle> articles = new ArrayList<>();
        JSONObject json = new JSONObject(jsonResponse);

        if (!json.has("articles")) {
            Log.e(TAG, "Invalid response format: missing 'articles' field");
            return articles;
        }

        JSONArray articlesArray = json.getJSONArray("articles");
        for (int i = 0; i < articlesArray.length(); i++) {
            try {
                JSONObject articleJson = articlesArray.getJSONObject(i);
                NewsArticle article = parseArticleJson(articleJson);
                if (article != null) {
                    articles.add(article);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing article " + i, e);
            }
        }

        return articles;
    }

    /**
     * Parse a single article JSON object.
     */
    private NewsArticle parseArticleJson(JSONObject json) throws Exception {
        NewsArticle article = new NewsArticle();

        article.title = json.optString("title", "");
        article.description = json.optString("description", "");
        article.content = json.optString("content", "");
        article.url = json.optString("url", "");
        article.imageUrl = json.optString("urlToImage", "");
        article.publishedAt = json.optString("publishedAt", "");
        article.source = json.optJSONObject("source").optString("name", "");
        article.author = json.optString("author", "");

        // Calculate content length for UI display
        article.contentLength = (article.description + " " + article.content).length();

        // Estimate read time (average 200 words per minute)
        int wordCount = article.contentLength / 5; // Rough estimate
        article.estimatedReadMinutes = Math.max(1, wordCount / 200);

        // Detect economic relevance based on keywords
        article.economicRelevanceScore = calculateRelevanceScore(article);

        return article;
    }

    /**
     * Score article relevance to economics (0-100).
     * Higher scores = more relevant to economic analysis.
     */
    private int calculateRelevanceScore(NewsArticle article) {
        String text = (article.title + " " + article.description + " " + article.content).toLowerCase();
        int score = 0;

        // High-impact keywords
        if (text.contains("federal reserve")) score += 25;
        if (text.contains("interest rate")) score += 20;
        if (text.contains("inflation")) score += 20;
        if (text.contains("unemployment")) score += 15;
        if (text.contains("gdp")) score += 20;
        if (text.contains("treasury")) score += 15;
        if (text.contains("debt")) score += 10;
        if (text.contains("employment")) score += 15;
        if (text.contains("wage")) score += 10;
        if (text.contains("housing")) score += 12;
        if (text.contains("market")) score += 5;

        // Negative keywords (reduce score)
        if (text.contains("celebrity") || text.contains("sports") || text.contains("entertainment")) score -= 30;

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Simple URL encoding for query parameters.
     */
    private String urlEncode(String str) {
        try {
            return java.net.URLEncoder.encode(str, "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }

    /**
     * Economic topics for easy searching.
     */
    public enum EconomicTopic {
        FED("Federal Reserve"),
        INFLATION("inflation"),
        EMPLOYMENT("unemployment OR jobs"),
        GDP("GDP OR economic growth"),
        HOUSING("housing OR real estate"),
        INTEREST_RATES("interest rates"),
        DEBT("national debt"),
        WAGES("wages OR earnings"),
        STOCKS("stock market"),
        CRYPTO("cryptocurrency");

        private final String searchQuery;

        EconomicTopic(String searchQuery) {
            this.searchQuery = searchQuery;
        }

        public String getSearchQuery() {
            return searchQuery;
        }
    }
}
