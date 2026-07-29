package com.economic.dashboard.news

class NewsItem {
    var title: String? = null
    var summary: String? = null       // <description> tag, HTML stripped
    var source: String? = null        // human-readable source name
    var url: String? = null           // <link> tag
    var pubDate: String? = null       // raw pubDate string
    var pubDateMillis: Long = 0       // parsed to millis for sorting
    var tag: String? = null           // "FED" | "INFLATION" | "JOBS" | "YIELDS" | "ECONOMY" | "GENERAL"
    var impactLevel: Int = 0          // 2 = high, 1 = medium, 0 = normal
    var sourceFeedUrl: String? = null // URL of the RSS feed that produced this item
    var imageUrl: String? = null      // from <enclosure>/<media:content>, null if the feed has none
}
