package com.economic.dashboard.api;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.economic.dashboard.database.NewsArticleDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.NewsArticle;
import com.economic.dashboard.utils.AppExecutors;

import java.util.List;

/**
 * Repository for news articles with caching and smart prompt generation.
 *
 * Fetches articles from NewsAPI.org and caches them locally in Room database.
 * Provides LiveData for reactive UI updates.
 */
public class NewsArticleRepository {
    private static final String TAG = "NewsArticleRepository";
    private final NewsArticleDao dao;
    private final NewsApiClient newsApiClient;

    public NewsArticleRepository(Context context) {
        YieldDatabase database = YieldDatabase.getInstance(context);
        this.dao = database.newsArticleDao();
        this.newsApiClient = new NewsApiClient(ApiConfig.NEWS_API_KEY);
    }

    public void fetchArticlesForTopic(String query) {
        Log.d(TAG, "Fetching articles for: " + query);

        newsApiClient.searchRecentEconomicNews(query, new NewsApiClient.NewsCallback() {
            @Override
            public void onSuccess(List<NewsArticle> articles) {
                Log.d(TAG, "Fetch successful. Caching " + articles.size() + " articles");

                long now = System.currentTimeMillis();
                for (NewsArticle article : articles) {
                    article.cachedAtMillis = now;
                }

                AppExecutors.getInstance().diskIO().execute(() -> {
                    dao.insertAll(articles);
                    Log.d(TAG, "Articles cached to database");
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Fetch error: " + error);
            }
        });
    }

    public LiveData<List<NewsArticle>> getRecentArticles(int limit) {
        return dao.getRecentArticles(limit);
    }

    public LiveData<List<NewsArticle>> getHighRelevanceArticles(int minScore) {
        return dao.getHighRelevanceArticles(minScore);
    }

    public LiveData<NewsArticle> getArticleById(int articleId) {
        return dao.getArticleById(articleId);
    }

    public void markAsRead(int articleId) {
        AppExecutors.getInstance().diskIO().execute(() -> dao.markAsRead(articleId));
    }
}
