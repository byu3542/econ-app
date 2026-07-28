package com.economic.dashboard.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the news_articles table.
 * Stores cached news articles with economic relevance scoring.
 */
@Entity(tableName = "news_articles")
data class NewsArticle @JvmOverloads constructor(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    var title: String? = null,
    var description: String? = null,
    var content: String? = null,
    var url: String? = null,
    var imageUrl: String? = null,

    /** ISO-8601 publish date string */
    var publishedAt: String? = null,

    var source: String? = null,
    var author: String? = null,

    /** 0–100 relevance score assigned at fetch time */
    var economicRelevanceScore: Int = 0,

    var contentLength: Int = 0,
    var estimatedReadMinutes: Int = 0,

    /** System.currentTimeMillis() when cached */
    var cachedAtMillis: Long = 0,

    var isRead: Boolean = false,
    var isFavorited: Boolean = false,

    var userNotes: String? = null,
    var aiAnalysisSummary: String? = null,
    var aiAnalysisTimestamp: Long = 0
)
