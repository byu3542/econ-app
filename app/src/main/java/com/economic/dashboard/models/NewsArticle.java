package com.economic.dashboard.models;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity for the news_articles table.
 * Stores cached news articles with economic relevance scoring.
 */
@Entity(tableName = "news_articles")
public class NewsArticle {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public String title;
    public String description;
    public String content;
    public String url;
    public String imageUrl;

    /** ISO-8601 publish date string */
    public String publishedAt;

    public String source;
    public String author;

    /** 0–100 relevance score assigned at fetch time */
    public int economicRelevanceScore;

    public int contentLength;
    public int estimatedReadMinutes;

    /** System.currentTimeMillis() when cached */
    public long cachedAtMillis;

    public boolean isRead;
    public boolean isFavorited;

    public String userNotes;
    public String aiAnalysisSummary;
    public long aiAnalysisTimestamp;

    // Requ