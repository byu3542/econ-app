package com.economic.dashboard.news;

public class NewsConstants {

    /**
     * Each entry is { feedUrl, displaySourceName }.
     * Add new feeds here — no other file changes required.
     */
    public static final String[][] RSS_FEEDS = {

        // ── U.S. GOVERNMENT — OFFICIAL ─────────────────────────────────────────
        { "https://www.federalreserve.gov/feeds/press_all.xml",  "Federal Reserve" },
        { "https://www.federalreserve.gov/feeds/speeches.xml",   "Fed Speech" },
        { "https://www.federalreserve.gov/feeds/testimony.xml",  "Fed Testimony" },
        { "https://www.bls.gov/feed/bls_latest.rss",             "BLS" },
        { "https://www.bea.gov/rss/release.xml",                 "BEA" },
        { "https://home.treasury.gov/system/files/276/treasury-press-releases.xml", "Treasury" },
        { "https://www.cbo.gov/rss/recurring_reports.xml",       "CBO" },
        { "https://www.census.gov/economic-indicators/indicator.rss", "Census Bureau" },
        { "https://www.fdic.gov/news/press-releases/rss.xml",    "FDIC" },

        // ── FINANCIAL NEWS ──────────────────────────────────────────────────────
        { "https://feeds.reuters.com/reuters/businessNews",      "Reuters" },
        { "https://feeds.reuters.com/news/economy",              "Reuters Economy" },
        { "https://feeds.content.dowjones.io/public/rss/mw_topstories", "MarketWatch" },
        { "https://feeds.content.dowjones.io/public/rss/mw_economy",    "MarketWatch Economy" },
        { "https://www.kiplinger.com/rss/economy.rss",           "Kiplinger" },
        { "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258", "CNBC Economy" },
        { "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664", "CNBC Finance" },
        { "https://finance.yahoo.com/rss/topstories",            "Yahoo Finance" },
        { "https://www.investopedia.com/feedbuilder/feed/getfeed/?feedName=rss_headline", "Investopedia" },
        { "https://rsshub.app/apnews/topics/business-news",      "AP Business" },

        // ── INTERNATIONAL & CENTRAL BANKS ──────────────────────────────────────
        { "https://www.imf.org/en/News/rss?language=eng",        "IMF" },
        { "https://feeds.worldbank.org/worldbank/news/rss",      "World Bank" },
        { "https://www.bis.org/rss/press_releases.rss",          "BIS" },
        { "https://www.oecd.org/economy/rss.xml",                "OECD" },

        // ── THINK TANKS & RESEARCH ──────────────────────────────────────────────
        { "https://www.brookings.edu/topic/economy/feed/",       "Brookings" },
        { "https://www.piie.com/rss.xml",                        "Peterson Institute" },
        { "https://www.cato.org/rss/economics",                  "Cato Institute" },
        { "https://www.aei.org/feed/?tag=economics",             "AEI" },
        { "https://fredblog.stlouisfed.org/feed/",               "FRED Blog" },
        { "https://macroblog.typepad.com/macroblog/atom.xml",    "Atlanta Fed" },
        { "https://libertystreeteconomics.newyorkfed.org/feed/", "NY Fed" },
    };
}
