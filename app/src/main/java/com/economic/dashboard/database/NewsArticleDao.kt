package com.economic.dashboard.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.economic.dashboard.models.NewsArticle

@Dao
interface NewsArticleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(articles: List<NewsArticle>)

    @Query("UPDATE news_articles SET isRead = 1 WHERE id = :articleId")
    fun markAsRead(articleId: Int)

    @Query("UPDATE news_articles SET isFavorited = 1 WHERE id = :articleId")
    fun markAsFavorited(articleId: Int)

    @Query("DELETE FROM news_articles WHERE cachedAtMillis < :timestamp")
    fun deleteOlderThan(timestamp: Long)

    @Query("SELECT * FROM news_articles ORDER BY publishedAt DESC LIMIT :limit")
    fun getRecentArticles(limit: Int): LiveData<List<NewsArticle>>

    @Query("SELECT * FROM news_articles WHERE economicRelevanceScore >= :minScore ORDER BY publishedAt DESC")
    fun getHighRelevanceArticles(minScore: Int): LiveData<List<NewsArticle>>

    @Query("SELECT * FROM news_articles WHERE id = :articleId")
    fun getArticleById(articleId: Int): LiveData<NewsArticle>

    @Query("SELECT * FROM news_articles ORDER BY publishedAt DESC LIMIT :limit")
    fun getRecentArticlesSync(limit: Int): List<NewsArticle>
}
