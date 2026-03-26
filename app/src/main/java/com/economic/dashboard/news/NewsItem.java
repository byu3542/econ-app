package com.economic.dashboard.news;

public class NewsItem {
    public String title;
    public String summary;       // <description> tag, HTML stripped
    public String source;        // human-readable source name
    public String url;           // <link> tag
    public String pubDate;       // raw pubDate string
    public long   pubDateMillis; // parsed to millis for sorting
    public String tag;           // "FED" | "INFLATION" | "JOBS" | "YIELDS" | "ECONOMY" | "GENERAL"
    public int    impactLevel;   // 2 = high, 1 = medium, 0 = normal
    public String sourceFeedUrl; // URL of the RSS feed that produced this item
}
