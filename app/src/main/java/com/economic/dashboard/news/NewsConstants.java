package com.economic.dashboard.news;

public class NewsConstants {

    // Category keys — map 1:1 to the toggles in the settings sheet
    // (SettingsManager.KEY_NEWS_*).
    public static final String CAT_GOV      = "gov";
    public static final String CAT_MEDIA    = "media";
    public static final String CAT_INTL     = "intl";
    public static final String CAT_RESEARCH = "research";

    /**
     * Each entry is { feedUrl, displaySourceName, categoryKey }.
     * Add new feeds here — no other file changes required.
     */
    public static final String[][] RSS_FEEDS = {

        // ── U.S. GOVERNMENT — OFFICIAL ─────────────────────────────────────────
        { "https://www.federalreserve.gov/feeds/press_all.xml",  "Federal Reserve", CAT_GOV },
        { "https://www.federalreserve.gov/feeds/speeches.xml",   "Fed Speech",      CAT_GOV },
        { "https://www.federalreserve.gov/feeds/testimony.xml",  "Fed Testimony",   CAT_GOV },
        { "https://www.bls.gov/feed/bls_latest.rss",             "BLS",             CAT_GOV },
        { "https://www.bea.gov/rss/release.xml",                 "BEA",             CAT_GOV },
        { "https://home.treasury.gov/system/files/276/treasury-press-releases.xml", "Treasury", CAT_GOV },
        { "https://www.cbo.gov/rss/recurring_reports.xml",       "CBO",             CAT_GOV },
        { "https://www.census.gov/economic-indicators/indicator.rss", "Census Bureau", CAT_GOV },
        { "https://www.fdic.gov/news/press-releases/rss.xml",    "FDIC",            CAT_GOV },

        // ── FINANCIAL NEWS ──────────────────────────────────────────────────────
        { "https://feeds.reuters.com/reuters/businessNews",      "Reuters",         CAT_MEDIA },
        { "https://feeds.reuters.com/news/economy",              "Reuters Economy", CAT_MEDIA },
        { "https://feeds.content.dowjones.io/public/rss/mw_topstories", "MarketWatch", CAT_MEDIA },
        { "https://feeds.content.dowjones.io/public/rss/mw_economy",    "MarketWatch Economy", CAT_MEDIA },
        { "https://www.kiplinger.com/rss/economy.rss",           "Kiplinger",       CAT_MEDIA },
        { "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258", "CNBC Economy", CAT_MEDIA },
        { "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664", "CNBC Finance", CAT_MEDIA },
        { "https://finance.yahoo.com/rss/topstories",            "Yahoo Finance",   CAT_MEDIA },
        { "https://www.investopedia.com/feedbuilder/feed/getfeed/?feedName=rss_headline", "Investopedia", CAT_MEDIA },
        { "https://rsshub.app/apnews/topics/business-news",      "AP Business",     CAT_MEDIA },

        // ── INTERNATIONAL & CENTRAL BANKS ──────────────────────────────────────
        { "https://www.imf.org/en/News/rss?language=eng",        "IMF",             CAT_INTL },
        { "https://feeds.worldbank.org/worldbank/news/rss",      "World Bank",      CAT_INTL },
        { "https://www.bis.org/rss/press_releases.rss",          "BIS",             CAT_INTL },
        { "https://www.oecd.org/economy/rss.xml",                "OECD",            CAT_INTL },

        // ── THINK TANKS & RESEARCH ──────────────────────────────────────────────
        { "https://www.brookings.edu/topic/economy/feed/",       "Brookings",       CAT_RESEARCH },
        { "https://www.piie.com/rss.xml",                        "Peterson Institute", CAT_RESEARCH },
        { "https://www.cato.org/rss/economics",                  "Cato Institute",  CAT_RESEARCH },
        { "https://www.aei.org/feed/?tag=economics",             "AEI",             CAT_RESEARCH },
        { "https://fredblog.stlouisfed.org/feed/",               "FRED Blog",       CAT_RESEARCH },
        { "https://macroblog.typepad.com/macroblog/atom.xml",    "Atlanta Fed",     CAT_RESEARCH },
        { "https://libertystreeteconomics.newyorkfed.org/feed/", "NY Fed",          CAT_RESEARCH },
    };
}
