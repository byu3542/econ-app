package com.economic.dashboard.news;

import android.text.Html;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RssParser {

    private static final String TAG = "RssParser";

    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
    };

    /**
     * Fetch and parse one RSS/Atom feed. Any failure (network, SSL, XML,
     * HTTP error) is caught, logged, and an empty list is returned so that
     * one broken feed never affects the others.
     */
    public List<NewsItem> parse(String feedUrl, String sourceName) {
        try {
            HttpURLConnection.setFollowRedirects(true);
            URL url = new URL(feedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "USEconomicMonitor/1.0 Android RSS Reader");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + responseCode + " skipped: " + sourceName);
                conn.disconnect();
                return new ArrayList<>();
            }

            int impactLevel = getImpactLevel(sourceName);
            List<NewsItem> items;

            try (InputStream is = conn.getInputStream()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                parser.setInput(is, null);
                items = parseFeed(parser, feedUrl, sourceName, impactLevel);
            }

            conn.disconnect();
            Log.d(TAG, "Feed OK: " + feedUrl + " | items: " + items.size());
            return items;

        } catch (Exception e) {
            Log.w(TAG, "Skipped: " + sourceName
                    + " | " + e.getClass().getSimpleName()
                    + " | " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ─── Feed parser ────────────────────────────────────────────────────────────

    private List<NewsItem> parseFeed(XmlPullParser parser, String feedUrl,
                                     String sourceName, int impactLevel) throws Exception {
        // Detect format by scanning for the root element
        boolean isAtom = false;
        int eventType = parser.getEventType();
        outer:
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("feed".equalsIgnoreCase(name)) {
                    isAtom = true;
                    break outer;
                } else if ("rss".equalsIgnoreCase(name)
                        || "channel".equalsIgnoreCase(name)) {
                    break outer;
                }
            }
            eventType = parser.next();
        }

        return parseItems(parser, feedUrl, sourceName, impactLevel, isAtom);
    }

    private List<NewsItem> parseItems(XmlPullParser parser, String feedUrl,
                                      String sourceName, int impactLevel,
                                      boolean isAtom) throws Exception {
        List<NewsItem> items = new ArrayList<>();
        boolean inItem = false;
        NewsItem current = null;
        String currentField = null;
        StringBuilder currentText = new StringBuilder();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG: {
                    String tag = parser.getName();

                    // Detect item start (RSS: <item>, Atom: <entry>)
                    if ("item".equalsIgnoreCase(tag) || "entry".equalsIgnoreCase(tag)) {
                        inItem = true;
                        current = new NewsItem();
                        current.source = sourceName;
                        current.sourceFeedUrl = feedUrl;
                        current.impactLevel = impactLevel;
                        currentField = null;

                    } else if (inItem) {
                        // Atom <link href="..."> is a self-closing element with an attribute
                        if ("link".equalsIgnoreCase(tag)) {
                            String href = parser.getAttributeValue(null, "href");
                            if (href != null && !href.isEmpty()
                                    && current != null && current.url == null) {
                                current.url = href;
                            }
                            // RSS <link> text node handled in END_TAG below
                        }

                        // Track text content of known fields
                        if (isKnownField(tag)) {
                            currentField = tag.toLowerCase(Locale.US);
                            currentText.setLength(0);
                        }
                    }
                    break;
                }
                case XmlPullParser.TEXT: {
                    if (inItem && currentField != null) {
                        currentText.append(parser.getText());
                    }
                    break;
                }
                case XmlPullParser.END_TAG: {
                    String tag = parser.getName();

                    // Flush accumulated field text
                    if (inItem && currentField != null
                            && currentField.equals(tag.toLowerCase(Locale.US))) {
                        String text = currentText.toString().trim();
                        if (!text.isEmpty() && current != null) {
                            applyField(current, currentField, text);
                        }
                        currentField = null;
                    }

                    // Flush item
                    if (("item".equalsIgnoreCase(tag) || "entry".equalsIgnoreCase(tag))
                            && inItem) {
                        inItem = false;
                        if (current != null && current.title != null
                                && !current.title.isEmpty()) {
                            tagArticle(current);
                            items.add(current);
                        }
                        current = null;
                        currentField = null;
                    }
                    break;
                }
            }
            eventType = parser.next();
        }
        return items;
    }

    // ─── Field application ───────────────────────────────────────────────────────

    private boolean isKnownField(String tag) {
        String lower = tag.toLowerCase(Locale.US);
        return lower.equals("title")
                || lower.equals("description")
                || lower.equals("summary")
                || lower.equals("content")    // Atom <content>
                || lower.equals("link")
                || lower.equals("pubdate")
                || lower.equals("published")
                || lower.equals("updated");   // Atom <updated>
    }

    private void applyField(NewsItem item, String field, String text) {
        switch (field) {
            case "title":
                if (item.title == null) item.title = text;
                break;
            case "description":
            case "summary":
            case "content":
                if (item.summary == null) {
                    String stripped = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
                            .toString().trim();
                    stripped = stripped.replaceAll("\\s+", " ").trim();
                    if (stripped.length() > 120) stripped = stripped.substring(0, 120);
                    item.summary = stripped;
                }
                break;
            case "link":
                if (item.url == null) item.url = text;
                break;
            case "pubdate":
            case "published":
            case "updated":
                if (item.pubDate == null) {
                    item.pubDate = text;
                    item.pubDateMillis = parsePubDate(text);
                }
                break;
        }
    }

    // ─── Date parsing ────────────────────────────────────────────────────────────

    private long parsePubDate(String dateStr) {
        for (String format : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
                Date d = sdf.parse(dateStr);
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {
                // try next format
            }
        }
        return System.currentTimeMillis();
    }

    // ─── Tagging ─────────────────────────────────────────────────────────────────

    private void tagArticle(NewsItem item) {
        if (item.title == null) {
            item.tag = "GENERAL";
            return;
        }

        // 1. Research sources always get RESEARCH regardless of headline keywords
        String feedUrl = item.sourceFeedUrl != null
                ? item.sourceFeedUrl.toLowerCase(Locale.US) : "";
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
                || feedUrl.contains("oecd.org")) {
            item.tag = "RESEARCH";
            return;
        }

        // 2. Keyword scan on title
        String lower = item.title.toLowerCase(Locale.US);

        if (containsAny(lower,
                "federal reserve", "fomc", "fed funds", "rate cut",
                "rate hike", "powell", "interest rate", "monetary policy",
                "basis points", "bps", "fed meeting", "discount rate",
                "reserve requirement", "quantitative", "balance sheet",
                "open market", "beige book", "fed chair", "jefferson",
                "waller", "kugler", "barkin", "daly", "bostic")) {
            item.tag = "FED";
        } else if (containsAny(lower,
                "cpi", "inflation", "consumer price", "pce",
                "price index", "deflation", "core inflation",
                "producer price", "ppi", "import price", "export price",
                "price level", "inflationary", "disinflation",
                "cost of living", "purchasing power")) {
            item.tag = "INFLATION";
        } else if (containsAny(lower,
                "unemployment", "jobs", "payroll", "nonfarm",
                "labor market", "jobless claims", "employment",
                "sahm", "employment situation", "labor force",
                "job openings", "jolts", "hiring", "layoffs",
                "initial claims", "continued claims", "workforce",
                "participation rate", "labor department",
                "occupational", "wages", "earnings")) {
            item.tag = "JOBS";
        } else if (containsAny(lower,
                "treasury", "yield", "bond", "10-year", "2-year",
                "yield curve", "spread", "t-bill", "note auction",
                "bond market", "debt auction", "coupon rate",
                "fixed income", "maturity", "3-month", "30-year",
                "securities", "tbill", "tnote", "tbond")) {
            item.tag = "YIELDS";
        } else if (containsAny(lower,
                "housing", "home sale", "home price", "mortgage",
                "real estate", "foreclosure", "housing start",
                "building permit", "homebuilder", "home construction",
                "existing home", "new home", "pending home",
                "nahb", "national association of realtors",
                "freddie mac", "fannie mae", "30-year mortgage",
                "rental market", "rent inflation", "home affordability",
                "case-shiller", "hpi", "zillow")) {
            item.tag = "HOUSING";
        } else if (containsAny(lower,
                "gdp", "recession", "growth", "economic", "economy",
                "fiscal", "deficit", "debt ceiling", "gross domestic",
                "personal income", "advance estimate", "national income",
                "trade deficit", "current account", "trade balance",
                "retail sales", "industrial production", "capacity",
                "leading indicators", "business cycle", "expansion",
                "contraction", "output", "productivity")) {
            item.tag = "ECONOMY";
        } else {
            item.tag = "GENERAL";
        }

        // 3. Source URL/name fallback when no keyword matched
        if ("GENERAL".equals(item.tag)) {
            String s = item.source != null
                    ? item.source.toLowerCase(Locale.US) : "";

            if (feedUrl.contains("federalreserve.gov")
                    || s.contains("federal reserve") || s.contains("fed speech")
                    || s.contains("fed testimony")) {
                item.tag = "FED";
            } else if (feedUrl.contains("bls.gov") || s.contains("bls")) {
                item.tag = "JOBS";
            } else if (feedUrl.contains("bea.gov") || s.contains("bea")) {
                item.tag = "ECONOMY";
            } else if (feedUrl.contains("treasury.gov") || s.contains("treasury")) {
                item.tag = "YIELDS";
            } else if (feedUrl.contains("cbo.gov") || s.contains("cbo")) {
                item.tag = "ECONOMY";
            } else if (feedUrl.contains("census.gov") || s.contains("census")) {
                item.tag = "ECONOMY";
            } else if (feedUrl.contains("fdic.gov") || s.contains("fdic")) {
                item.tag = "FED";
            } else if (feedUrl.contains("nar.realtor") || feedUrl.contains("nahb.org")
                    || feedUrl.contains("freddiemac.com") || feedUrl.contains("fanniemae.com")) {
                item.tag = "HOUSING";
            }
            // Reuters, MarketWatch, CNBC, Yahoo, Investopedia, AP → stay GENERAL
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ─── Impact level ────────────────────────────────────────────────────────────

    private int getImpactLevel(String sourceName) {
        String s = sourceName.toLowerCase(Locale.US);
        if (s.contains("federal reserve") || s.contains("fed speech")
                || s.contains("fed testimony") || s.contains("bls")
                || s.contains("bea") || s.contains("treasury")
                || s.contains("fomc")) {
            return 2; // high — gold border
        }
        if (s.contains("cbo") || s.contains("census")
                || s.contains("fdic") || s.contains("imf")
                || s.contains("fred blog") || s.contains("atlanta fed")
                || s.contains("ny fed") || s.contains("reuters economy")
                || s.contains("marketwatch economy")) {
            return 1; // medium — faint gold border
        }
        return 0; // normal — no border
    }
}
