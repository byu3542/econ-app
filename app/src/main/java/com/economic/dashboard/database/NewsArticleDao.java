package com.economic.dashboard.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.economic.dashboard.models.NewsArticle;

import java.util.List;

@Dao
public interface NewsArticleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<NewsArticle> articles);

    @Query("UPDATE news_articles SET isRead = 1 WHERE id = :articleId")
    void markAsRead(int articleId);

    @Query("UPDATE news_articles SET isFavorited = 1 WHERE id = :articleId")
    void markAsFavorited(int articleId);

    @Query("DELETE FROM news_articles WHERE cachedAtMillis < :timestamp")
    void deleteOlderThan(long timestamp);

    @Query("SELECT * FROM news_articles ORDER BY publishedAt DESC LIMIT :limit")
    LiveData<List<NewsArticle>> getRecentArticles(int limit);

    @Query("SELECT * FROM news_articles WHERE economicRelevanceScore >= :minScore ORDER BY publishedAt DESC")
    LiveData<List<NewsArticle>> getHighRelevanceArticles(int minScore);

    @Query("SELECT * FROM news_articles WHERE id = :articleId")
    LiveData<NewsArticle> getArticleById(int articleId);

    @Query("SELECT * FROM news_articles ORDER BY publishedAt DESC LIMIT :limit")
    List<NewsArticle> getRecentArticlesSync(int limit);
}
