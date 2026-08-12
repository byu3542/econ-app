package com.economic.dashboard.news

import android.text.Html
import android.util.Log
import android.util.Xml

import org.xmlpull.v1.XmlPullParser

import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class RssParser {

    /**
     * Fetch and parse one RSS/Atom feed. Any failure (network, SSL, XML,
     * HTTP error) is caught, logged, and an empty list is returned so that
     * one broken feed never affects the others.
     */
    fun parse(feedUrl: String, sourceName: String): List<NewsItem> {
        try {
            HttpURLConnection.setFollowRedirects(true)
            val url = URL(feedUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "USEconomicMonitor/1.0 Android RSS Reader")
            conn.connectTimeout = 8000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $responseCode skipped: $sourceName")
                conn.disconnect()
                return ArrayList()
            }

            val impactLevel = getImpactLevel(sourceName)
            val items: List<NewsItem>

            conn.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                items = parseFeed(parser, feedUrl, sourceName, impactLevel)
            }

            conn.disconnect()
            Log.d(TAG, "Feed OK: $feedUrl | items: ${items.size}")
            return items

        } catch (e: Exception) {
            Log.w(TAG, "Skipped: $sourceName | ${e.javaClass.simpleName} | ${e.message}")
            return ArrayList()
        }
    }

    // ─── Feed parser ────────────────────────────────────────────────────────────

    private fun parseFeed(
        parser: XmlPullParser, feedUrl: String,
        sourceName: String, impactLevel: Int
    ): List<NewsItem> {
        // Detect format by scanning for the root element
        var isAtom = false
        var eventType = parser.eventType
        outer@ while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                if (name.equals("feed", ignoreCase = true)) {
                    isAtom = true
                    break@outer
                } else if (name.equals("rss", ignoreCase = true)
                    || name.equals("channel", ignoreCase = true)
                ) {
                    break@outer
                }
            }
            eventType = parser.next()
        }

        return parseItems(parser, feedUrl, sourceName, impactLevel, isAtom)
    }

    private fun parseItems(
        parser: XmlPullParser, feedUrl: String,
        sourceName: String, impactLevel: Int,
        isAtom: Boolean
    ): List<NewsItem> {
        val items = ArrayList<NewsItem>()
        var inItem = false
        var current: NewsItem? = null
        var currentField: String? = null
        val currentText = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name

                    // Detect item start (RSS: <item>, Atom: <entry>)
                    if (tag.equals("item", ignoreCase = true) || tag.equals("entry", ignoreCase = true)) {
                        inItem = true
                        val newItem = NewsItem()
                        newItem.source = sourceName
                        newItem.sourceFeedUrl = feedUrl
                        newItem.impactLevel = impactLevel
                        current = newItem
                        currentField = null

                    } else if (inItem) {
                        val item = current
                        // Thumbnail: <enclosure url= type=image/*> or <media:content|thumbnail url=>
                        if (item != null && item.imageUrl == null
                            && (tag.equals("enclosure", ignoreCase = true)
                                || tag.equals("media:content", ignoreCase = true)
                                || tag.equals("media:thumbnail", ignoreCase = true))
                        ) {
                            val imgUrl = parser.getAttributeValue(null, "url")
                            val type = parser.getAttributeValue(null, "type")
                            if (!imgUrl.isNullOrEmpty() && (type == null || type.startsWith("image"))) {
                                item.imageUrl = imgUrl
                            }
                        }

                        // Atom <link href="..."> is a self-closing element with an attribute
                        if (tag.equals("link", ignoreCase = true)) {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty() && item != null && item.url == null) {
                                item.url = href
                            }
                            // RSS <link> text node handled in END_TAG below
                        }

                        // Track text content of known fields
                        if (isKnownField(tag)) {
                            currentField = tag.lowercase(Locale.US)
                            currentText.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem && currentField != null) {
                        currentText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name

                    // Flush accumulated field text
                    val field = currentField
                    if (inItem && field != null && field == tag.lowercase(Locale.US)) {
                        val text = currentText.toString().trim()
                        val item = current
                        if (text.isNotEmpty() && item != null) {
                            applyField(item, field, text)
                        }
                        currentField = null
                    }

                    // Flush item
                    if ((tag.equals("item", ignoreCase = true) || tag.equals("entry", ignoreCase = true))
                        && inItem
                    ) {
                        inItem = false
                        val item = current
                        if (item != null && !item.title.isNullOrEmpty()) {
                            tagArticle(item)
                            items.add(item)
                        }
                        current = null
                        currentField = null
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    // ─── Field application ───────────────────────────────────────────────────────

    private fun isKnownField(tag: String): Boolean {
        val lower = tag.lowercase(Locale.US)
        return lower == "title"
            || lower == "description"
            || lower == "summary"
            || lower == "content"    // Atom <content>
            || lower == "link"
            || lower == "pubdate"
            || lower == "published"
            || lower == "updated"    // Atom <updated>
    }

    private fun applyField(item: NewsItem, field: String, text: String) {
        when (field) {
            "title" -> if (item.title == null) item.title = text
            "description", "summary", "content" -> {
                if (item.summary == null) {
                    var stripped = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim()
                    stripped = stripped.replace(Regex("\\s+"), " ").trim()
                    if (stripped.length > 120) stripped = stripped.substring(0, 120)
                    item.summary = stripped
                }
            }
            "link" -> if (item.url == null) item.url = text
            "pubdate", "published", "updated" -> {
                if (item.pubDate == null) {
                    item.pubDate = text
                    item.pubDateMillis = parsePubDate(text)
                }
            }
        }
    }

    // ─── Date parsing ────────────────────────────────────────────────────────────

    // Compiled once per parser instance (one instance per feed, confined to a
    // single background thread) instead of one new SimpleDateFormat per format
    // attempt per item. SimpleDateFormat isn't thread-safe — keep these
    // instance-level, never companion-level.
    private val dateFormatters = DATE_FORMATS.map { SimpleDateFormat(it, Locale.US) }

    private fun parsePubDate(dateStr: String): Long {
        for (sdf in dateFormatters) {
            try {
                val d = sdf.parse(dateStr)
                if (d != null) return d.time
            } catch (ignored: ParseException) {
                // try next format
            }
        }
        return System.currentTimeMillis()
    }

    // ─── Tagging ─────────────────────────────────────────────────────────────────

    private fun tagArticle(item: NewsItem) {
        val title = item.title
        if (title == null) {
            item.tag = "GENERAL"
            return
        }

        // 1. Research sources always get RESEARCH regardless of headline keywords
        val feedUrl = item.sourceFeedUrl?.lowercase(Locale.US) ?: ""
        if (feedUrl.contains("brookings.edu")
            || feedUrl.contains("piie.com")
            || feedUrl.contains("cato.org")
            || feedUrl.contains("aei.org")
            || feedUrl.contains("stlouisfed.org")
            || feedUrl.contains("typepad.com")       // Atlanta Fed macroblog
            || feedUrl.contains("newyorkfed.org")
            || feedUrl.contains("imf.org")
            || feedUrl.contains("worldbank.org")
            || feedUrl.contains("bis.org")
            || feedUrl.contains("oecd.org")
        ) {
            item.tag = "RESEARCH"
            return
        }

        // 2. Keyword scan on title
        val lower = title.lowercase(Locale.US)

        if (containsAny(lower,
                "federal reserve", "fomc", "fed funds", "rate cut",
                "rate hike", "powell", "interest rate", "monetary policy",
                "basis points", "bps", "fed meeting", "discount rate",
                "reserve requirement", "quantitative", "balance sheet",
                "open market", "beige book", "fed chair", "jefferson",
                "waller", "kugler", "barkin", "daly", "bostic")) {
            item.tag = "FED"
        } else if (containsAny(lower,
                "cpi", "inflation", "consumer price", "pce",
                "price index", "deflation", "core inflation",
                "producer price", "ppi", "import price", "export price",
                "price level", "inflationary", "disinflation",
                "cost of living", "purchasing power")) {
            item.tag = "INFLATION"
        } else if (containsAny(lower,
                "unemployment", "jobs", "payroll", "nonfarm",
                "labor market", "jobless claims", "employment",
                "sahm", "employment situation", "labor force",
                "job openings", "jolts", "hiring", "layoffs",
                "initial claims", "continued claims", "workforce",
                "participation rate", "labor department",
                "occupational", "wages", "earnings")) {
            item.tag = "JOBS"
        } else if (containsAny(lower,
                "treasury", "yield", "bond", "10-year", "2-year",
                "yield curve", "spread", "t-bill", "note auction",
                "bond market", "debt auction", "coupon rate",
                "fixed income", "maturity", "3-month", "30-year",
                "securities", "tbill", "tnote", "tbond")) {
            item.tag = "YIELDS"
        } else if (containsAny(lower,
                "housing", "home sale", "home price", "mortgage",
                "real estate", "foreclosure", "housing start",
                "building permit", "homebuilder", "home construction",
                "existing home", "new home", "pending home",
                "nahb", "national association of realtors",
                "freddie mac", "fannie mae", "30-year mortgage",
                "rental market", "rent inflation", "home affordability",
                "case-shiller", "hpi", "zillow")) {
            item.tag = "HOUSING"
        } else if (containsAny(lower,
                "gdp", "recession", "growth", "economic", "economy",
                "fiscal", "deficit", "debt ceiling", "gross domestic",
                "personal income", "advance estimate", "national income",
                "trade deficit", "current account", "trade balance",
                "retail sales", "industrial production", "capacity",
                "leading indicators", "business cycle", "expansion",
                "contraction", "output", "productivity")) {
            item.tag = "ECONOMY"
        } else {
            item.tag = "GENERAL"
        }

        // 3. Source URL/name fallback when no keyword matched
        if (item.tag == "GENERAL") {
            val s = item.source?.lowercase(Locale.US) ?: ""

            if (feedUrl.contains("federalreserve.gov")
                || s.contains("federal reserve") || s.contains("fed speech")
                || s.contains("fed testimony")
            ) {
                item.tag = "FED"
            } else if (feedUrl.contains("bls.gov") || s.contains("bls")) {
                item.tag = "JOBS"
            } else if (feedUrl.contains("bea.gov") || s.contains("bea")) {
                item.tag = "ECONOMY"
            } else if (feedUrl.contains("treasury.gov") || s.contains("treasury")) {
                item.tag = "YIELDS"
            } else if (feedUrl.contains("cbo.gov") || s.contains("cbo")) {
                item.tag = "ECONOMY"
            } else if (feedUrl.contains("census.gov") || s.contains("census")) {
                item.tag = "ECONOMY"
            } else if (feedUrl.contains("fdic.gov") || s.contains("fdic")) {
                item.tag = "FED"
            } else if (feedUrl.contains("nar.realtor") || feedUrl.contains("nahb.org")
                || feedUrl.contains("freddiemac.com") || feedUrl.contains("fanniemae.com")
            ) {
                item.tag = "HOUSING"
            }
            // Reuters, MarketWatch, CNBC, Yahoo, Investopedia, AP → stay GENERAL
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        return false
    }

    // ─── Impact level ────────────────────────────────────────────────────────────

    private fun getImpactLevel(sourceName: String): Int {
        val s = sourceName.lowercase(Locale.US)
        if (s.contains("federal reserve") || s.contains("fed speech")
            || s.contains("fed testimony") || s.contains("bls")
            || s.contains("bea") || s.contains("treasury")
            || s.contains("fomc")
        ) {
            return 2 // high — gold border
        }
        if (s.contains("cbo") || s.contains("census")
            || s.contains("fdic") || s.contains("imf")
            || s.contains("fred blog") || s.contains("atlanta fed")
            || s.contains("ny fed") || s.contains("reuters economy")
            || s.contains("marketwatch economy")
        ) {
            return 1 // medium — faint gold border
        }
        return 0 // normal — no border
    }

    companion object {
        private const val TAG = "RssParser"

        private val DATE_FORMATS = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
    }
}
