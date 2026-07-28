package com.economic.dashboard.api

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.economic.dashboard.database.NewsArticleDao
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.NewsArticle
import com.economic.dashboard.utils.AppExecutors

/**
 * Repository for news articles with caching and smart prompt generation.
 *
 * Fetches articles from NewsAPI.org and caches them locally in Room database.
 * Provides LiveData for reactive UI updates.
 */
class NewsArticleRepository(context: Context) {

    private val dao: NewsArticleDao = YieldDatabase.getInstance(context).newsArticleDao()
    private val newsApiClient: NewsApiClient = NewsApiClient(ApiConfig.NEWS_API_KEY)

    fun fetchArticlesForTopic(query: String) {
        Log.d(TAG, "Fetching articles for: $query")

        newsApiClient.searchRecentEconomicNews(query, object : NewsApiClient.NewsCallback {
            override fun onSuccess(articles: List<NewsArticle>) {
                Log.d(TAG, "Fetch successful. Caching " + articles.size + " articles")

                val now = System.currentTimeMillis()
                for (article in articles) {
                    article.cachedAtMillis = now
                }

                AppExecutors.diskIO().execute {
                    dao.insertAll(articles)
                    Log.d(TAG, "Articles cached to database")
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Fetch error: $error")
            }
        })
    }

    fun getRecentArticles(limit: Int): LiveData<List<NewsArticle>> = dao.getRecentArticles(limit)

    fun getHighRelevanceArticles(minScore: Int): LiveData<List<NewsArticle>> = dao.getHighRelevanceArticles(minScore)

    fun getArticleById(articleId: Int): LiveData<NewsArticle> = dao.getArticleById(articleId)

    fun markAsRead(articleId: Int) {
        AppExecutors.diskIO().execute { dao.markAsRead(articleId) }
    }

    companion object {
        private const val TAG = "NewsArticleRepository"
    }
}
