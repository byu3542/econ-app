package com.economic.dashboard.news

object NewsConstants {

    // Category keys — map 1:1 to the toggles in the settings sheet
    // (SettingsManager.KEY_NEWS_*).
    @JvmField val CAT_GOV: String = "gov"
    @JvmField val CAT_MEDIA: String = "media"
    @JvmField val CAT_INTL: String = "intl"
    @JvmField val CAT_RESEARCH: String = "research"

    /**
     * Each entry is { feedUrl, displaySourceName, categoryKey }.
     * Add new feeds here — no other file changes required.
     */
    @JvmField val RSS_FEEDS: Array<Array<String>> = arrayOf(

        // ── U.S. GOVERNMENT — OFFICIAL ─────────────────────────────────────────
        arrayOf("https://www.federalreserve.gov/feeds/press_all.xml", "Federal Reserve", CAT_GOV),
        arrayOf("https://www.federalreserve.gov/feeds/speeches.xml", "Fed Speech", CAT_GOV),
        arrayOf("https://www.federalreserve.gov/feeds/testimony.xml", "Fed Testimony", CAT_GOV),
        arrayOf("https://www.bls.gov/feed/bls_latest.rss", "BLS", CAT_GOV),
        arrayOf("https://www.bea.gov/rss/release.xml", "BEA", CAT_GOV),
        arrayOf("https://home.treasury.gov/system/files/276/treasury-press-releases.xml", "Treasury", CAT_GOV),
        arrayOf("https://www.cbo.gov/rss/recurring_reports.xml", "CBO", CAT_GOV),
        arrayOf("https://www.census.gov/economic-indicators/indicator.rss", "Census Bureau", CAT_GOV),
        arrayOf("https://www.fdic.gov/news/press-releases/rss.xml", "FDIC", CAT_GOV),

        // ── FINANCIAL NEWS ──────────────────────────────────────────────────────
        arrayOf("https://feeds.reuters.com/reuters/businessNews", "Reuters", CAT_MEDIA),
        arrayOf("https://feeds.reuters.com/news/economy", "Reuters Economy", CAT_MEDIA),
        arrayOf("https://feeds.content.dowjones.io/public/rss/mw_topstories", "MarketWatch", CAT_MEDIA),
        arrayOf("https://feeds.content.dowjones.io/public/rss/mw_economy", "MarketWatch Economy", CAT_MEDIA),
        arrayOf("https://www.kiplinger.com/rss/economy.rss", "Kiplinger", CAT_MEDIA),
        arrayOf("https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258", "CNBC Economy", CAT_MEDIA),
        arrayOf("https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664", "CNBC Finance", CAT_MEDIA),
        arrayOf("https://finance.yahoo.com/rss/topstories", "Yahoo Finance", CAT_MEDIA),
        arrayOf("https://www.investopedia.com/feedbuilder/feed/getfeed/?feedName=rss_headline", "Investopedia", CAT_MEDIA),
        arrayOf("https://rsshub.app/apnews/topics/business-news", "AP Business", CAT_MEDIA),

        // ── INTERNATIONAL & CENTRAL BANKS ──────────────────────────────────────
        arrayOf("https://www.imf.org/en/News/rss?language=eng", "IMF", CAT_INTL),
        arrayOf("https://feeds.worldbank.org/worldbank/news/rss", "World Bank", CAT_INTL),
        arrayOf("https://www.bis.org/rss/press_releases.rss", "BIS", CAT_INTL),
        arrayOf("https://www.oecd.org/economy/rss.xml", "OECD", CAT_INTL),

        // ── THINK TANKS & RESEARCH ──────────────────────────────────────────────
        arrayOf("https://www.brookings.edu/topic/economy/feed/", "Brookings", CAT_RESEARCH),
        arrayOf("https://www.piie.com/rss.xml", "Peterson Institute", CAT_RESEARCH),
        arrayOf("https://www.cato.org/rss/economics", "Cato Institute", CAT_RESEARCH),
        arrayOf("https://www.aei.org/feed/?tag=economics", "AEI", CAT_RESEARCH),
        arrayOf("https://fredblog.stlouisfed.org/feed/", "FRED Blog", CAT_RESEARCH),
        arrayOf("https://macroblog.typepad.com/macroblog/atom.xml", "Atlanta Fed", CAT_RESEARCH),
        arrayOf("https://libertystreeteconomics.newyorkfed.org/feed/", "NY Fed", CAT_RESEARCH)
    )
}
