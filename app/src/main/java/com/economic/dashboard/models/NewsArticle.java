package com.economic.dashboard.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Data model for full economic news articles from NewsAPI.org.
 *
 * Stores complete article content including headline, body, source, and metadata.
 * Caches articles locally for offline access and analysis.
 */
@Entity(tableName = "news_articles")
public class NewsArticle {

    @PrimaryKey(autoGenerate = true)
    public int id;

    /**
     * Article headline
     */
    @NonNull
    public String title = "";

    /**
     * Brief description/summary from NewsAPI
     */
    @NonNull
    public String description = "";

    /**
     * Full article content (truncated by NewsAPI to ~200 chars)
     * For full content, user must visit the article URL
     */
    @NonNull
    public String content = "";

    /**
     * URL to read full article
     */
    @NonNull
    public String url = "";

    /**
     * Article image URL
     */
    @NonNull
    public String imageUrl = "";

    /**
     * ISO 8601 timestamp when article was published
     * Example: "2026-04-17T15:30:00Z"
     */
    @NonNull
    public String publishedAt = "";

    /**
     * News source (e.g., "Reuters", "Associated Press", "Bloomberg")
     */
    @NonNull
    public String source = "";

    /**
     * Article author name (if available)
     */
    @NonNull
    public String author = "";

    /**
     * Estimated economic relevance (0-100).
     * 100 = highly relevant (Fed action, inflation report)
     * 0 = not relevant (sports, entertainment)
     *
     * Used for sorting and filtering.
     */
    public int economicRelevanceScore = 0;

    /**
     * Content length in characters
     * Used to estimate read time
     */
    public int contentLength = 0;

    /**
     * Estimated reading time in minutes
     * Calculated as (contentLength / 5) / 200 words per minute
     */
    public int estimatedReadMinutes = 1;

    /**
     * Timestamp when this article was cached locally (milliseconds)
     * Used for cache management
     */
    public long cachedAtMillis = 0;

    /**
     * Whether user has read this article
     */
    public boolean isRead = false;

    /**
     * Whether user marked as favorite for later analysis
     */
    public boolean isFavorited = false;

    /**
     * User's notes about this article for analysis
     */
    @NonNull
    public String userNotes = "";

    /**
     * AI analysis summary (cached after Claude analysis)
     * Prevents re-analyzing the same article
     */
    @NonNull
    public String aiAnalysisSummary = "";

    /**
     * Timestamp of when AI analysis was performed
     */
    public long aiAnalysisTimestamp = 0;

    @Override
    @NonNull
    public String toString() {
        return "NewsArticle{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", source='" + source + '\'' +
                ", relevance=" + economicRelevanceScore +
                ", publishedAt='" + publishedAt + '\'' +
                '}';
    }

    /**
     * Get display-friendly published time.
     * Example: "2 hours ago", "Yesterday", "Apr 15"
     */
    @NonNull
    public String getPublishedTimeAgo() {
        try {
            long pubTime = javax.xml.datatype.DatatypeFactory.newInstance()
                    .newXMLGregorianCalendar(publishedAt)
                    .toGregorianCalendar()
                    .getTimeInMillis();

            long now = System.currentTimeMillis();
            long diffMillis = now - pubTime;

            long diffMinutes = diffMillis / 1000 / 60;
            long diffHours = diffMinutes / 60;
            long diffDays = diffHours / 24;

            if (diffMinutes < 1) return "Just now";
            if (diffMinutes < 60) return diffMinutes + "m ago";
            if (diffHours < 24) return diffHours + "h ago";
            if (diffDays == 1) return "Yesterday";
            if (diffDays < 7) return diffDays + "d ago";

            // Fallback to date format
            return publishedAt.split("T")[0];
        } catch (Exception e) {
            return publishedAt.split("T")[0];
        }
    }

    /**
     * Get economic impact indicator emoji based on relevance score.
     */
    @NonNull
    public String getRelevanceIndicator() {
        if (economicRelevanceScore >= 80) return "🔴"; // Critical
        if (economicRelevanceScore >= 60) return "🟠"; // High
        if (economicRelevanceScore >= 40) return "🟡"; // Medium
        if (economicRelevanceScore >= 20) return "🟢"; // Low
        return "⚪";                              // Minimal
    }

    /**
     * Check if content is available (not truncated).
     */
    public boolean hasFullContent() {
        return content.length() > 100; // NewsAPI usually truncates to ~200 chars
    }
}
