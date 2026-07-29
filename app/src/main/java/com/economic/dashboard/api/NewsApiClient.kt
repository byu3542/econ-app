package com.economic.dashboard.api

import android.util.Log

import com.economic.dashboard.models.NewsArticle
import com.economic.dashboard.utils.AppExecutors

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class NewsApiClient(private val apiKey: String) {

    interface NewsCallback {
        fun onSuccess(articles: List<NewsArticle>)
        fun onError(error: String)
    }

    fun searchNews(query: String, sortBy: String, callback: NewsCallback) {
        AppExecutors.networkIO().execute {
            try {
                val url = BASE_URL + ENDPOINT_EVERYTHING +
                    "?q=" + urlEncode(query) +
                    "&sortBy=" + sortBy +
                    "&language=en" +
                    "&apiKey=" + apiKey

                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    callback.onError("NewsAPI HTTP error: " + response.code)
                    return@execute
                }

                val jsonResponse = response.body!!.string()
                val articles = parseNewsResponse(jsonResponse)

                if (articles.isEmpty()) {
                    callback.onError("No articles found for: $query")
                } else {
                    callback.onSuccess(articles)
                }

            } catch (e: Exception) {
                Log.e(TAG, "NewsAPI fetch error", e)
                callback.onError("NewsAPI fetch failed: " + e.message)
            }
        }
    }

    fun searchRecentEconomicNews(query: String, callback: NewsCallback) {
        searchNews(query, "publishedAt", callback)
    }

    fun getTrendingEconomicNews(callback: NewsCallback) {
        searchNews(
            "Federal Reserve OR inflation OR GDP OR unemployment OR interest rates",
            "popularity",
            callback
        )
    }

    fun searchByTopic(topic: EconomicTopic, callback: NewsCallback) {
        searchRecentEconomicNews(topic.searchQuery, callback)
    }

    private fun parseNewsResponse(jsonResponse: String): List<NewsArticle> {
        val articles = ArrayList<NewsArticle>()
        val json = JSONObject(jsonResponse)

        if (!json.has("articles")) return articles

        val articlesArray = json.getJSONArray("articles")
        for (i in 0 until articlesArray.length()) {
            try {
                val article = parseArticleJson(articlesArray.getJSONObject(i))
                articles.add(article)
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing article $i", e)
            }
        }

        return articles
    }

    private fun parseArticleJson(json: JSONObject): NewsArticle {
        val article = NewsArticle()

        article.title = json.optString("title", "")
        article.description = json.optString("description", "")
        article.content = json.optString("content", "")
        article.url = json.optString("url", "")
        article.imageUrl = json.optString("urlToImage", "")
        article.publishedAt = json.optString("publishedAt", "")
        // Pre-existing behavior: NPEs here if a "source" object is absent from the
        // response, same as the original Java (optJSONObject("source").optString(...)
        // with no null check). Not fixed per migration rule 4 — see CHANGES file.
        article.source = json.optJSONObject("source")!!.optString("name", "")
        article.author = json.optString("author", "")

        article.contentLength = (article.description + " " + article.content).length
        val wordCount = article.contentLength / 5
        article.estimatedReadMinutes = maxOf(1, wordCount / 200)
        article.economicRelevanceScore = calculateRelevanceScore(article)

        return article
    }

    private fun calculateRelevanceScore(article: NewsArticle): Int {
        val text = (article.title + " " + article.description + " " + article.content).lowercase()
        var score = 0

        if (text.contains("federal reserve")) score += 25
        if (text.contains("interest rate")) score += 20
        if (text.contains("inflation")) score += 20
        if (text.contains("unemployment")) score += 15
        if (text.contains("gdp")) score += 20
        if (text.contains("treasury")) score += 15
        if (text.contains("debt")) score += 10
        if (text.contains("employment")) score += 15
        if (text.contains("wage")) score += 10
        if (text.contains("housing")) score += 12
        if (text.contains("market")) score += 5
        if (text.contains("celebrity") || text.contains("sports") || text.contains("entertainment")) score -= 30

        return maxOf(0, minOf(100, score))
    }

    private fun urlEncode(str: String): String {
        return try {
            java.net.URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            str
        }
    }

    enum class EconomicTopic(val searchQuery: String) {
        FED("Federal Reserve"),
        INFLATION("inflation"),
        EMPLOYMENT("unemployment OR jobs"),
        GDP("GDP OR economic growth"),
        HOUSING("housing OR real estate"),
        INTEREST_RATES("interest rates"),
        DEBT("national debt"),
        WAGES("wages OR earnings"),
        STOCKS("stock market"),
        CRYPTO("cryptocurrency")
    }

    companion object {
        private const val TAG = "NewsApiClient"
        private const val BASE_URL = "https://newsapi.org/v2"
        private const val ENDPOINT_EVERYTHING = "/everything"
    }
}
