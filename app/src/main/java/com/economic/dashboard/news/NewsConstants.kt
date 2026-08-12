package com.economic.dashboard.news

/** One RSS source: where to fetch, how to label it, which settings toggle owns it. */
data class RssFeedDef(val url: String, val name: String, val categoryKey: String)

object NewsConstants {

    // Category keys — map 1:1 to the toggles in the settings sheet
    // (SettingsManager.KEY_NEWS_*).
    @JvmField val CAT_GOV: String = "gov"
    @JvmField val CAT_MEDIA: String = "media"
    @JvmField val CAT_INTL: String = "intl"
    @JvmField val CAT_RESEARCH: String = "research"

    // Article tag vocabulary — assigned in RssParser.tagArticle, read by
    // NewsAdapter (colors) and NewsFragment (filter chips). Constants so a
    // typo is a compile error instead of a silently broken filter.
    const val TAG_FED = "FED"
    const val TAG_INFLATION = "INFLATION"
    const val TAG_JOBS = "JOBS"
    const val TAG_YIELDS = "YIELDS"
    const val TAG_ECONOMY = "ECONOMY"
    const val TAG_RESEARCH = "RESEARCH"
    const val TAG_HOUSING = "HOUSING"
    const val TAG_GENERAL = "GENERAL"

    /**
     * Add new feeds here — no other file changes required.
     */
    @JvmField val RSS_FEEDS: List<RssFeedDef> = listOf(

        // ── U.S. GOVERNMENT — OFFICIAL ─────────────────────────────────────────
        RssFeedDef("https://www.federalreserve.gov/feeds/press_all.xml", "Federal Reserve", CAT_GOV),
        RssFeedDef("https://www.federalreserve.gov/feeds/speeches.xml", "Fed Speech", CAT_GOV),
        RssFeedDef("https://www.federalreserve.gov/feeds/testimony.xml", "Fed Testimony", CAT_GOV),
        RssFeedDef("https://www.bls.gov/feed/bls_latest.rss", "BLS", CAT_GOV),
        RssFeedDef("https://www.bea.gov/rss/release.xml", "BEA", CAT_GOV),
        RssFeedDef("https://home.treasury.gov/system/files/276/treasury-press-releases.xml", "Treasury", CAT_GOV),
        RssFeedDef("https://www.cbo.gov/rss/recurring_reports.xml", "CBO", CAT_GOV),
        RssFeedDef("https://www.census.gov/economic-indicators/indicator.rss", "Census Bureau", CAT_GOV),
        RssFeedDef("https://www.fdic.gov/news/press-releases/rss.xml", "FDIC", CAT_GOV),

        // ── FINANCIAL NEWS ──────────────────────────────────────────────────────
        RssFeedDef("https://feeds.reuters.com/reuters/businessNews", "Reuters", CAT_MEDIA),
        RssFeedDef("https://feeds.reuters.com/news/economy", "Reuters Economy", CAT_MEDIA),
        RssFeedDef("https://feeds.content.dowjones.io/public/rss/mw_topstories", "MarketWatch", CAT_MEDIA),
        RssFeedDef("https://feeds.content.dowjones.io/public/rss/mw_economy", "MarketWatch Economy", CAT_MEDIA),
        RssFeedDef("https://www.kiplinger.com/rss/economy.rss", "Kiplinger", CAT_MEDIA),
        RssFeedDef("https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258", "CNBC Economy", CAT_MEDIA),
        RssFeedDef("https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664", "CNBC Finance", CAT_MEDIA),
        RssFeedDef("https://finance.yahoo.com/rss/topstories", "Yahoo Finance", CAT_MEDIA),
        RssFeedDef("https://www.investopedia.com/feedbuilder/feed/getfeed/?feedName=rss_headline", "Investopedia", CAT_MEDIA),
        RssFeedDef("https://rsshub.app/apnews/topics/business-news", "AP Business", CAT_MEDIA),

        // ── INTERNATIONAL & CENTRAL BANKS ──────────────────────────────────────
        RssFeedDef("https://www.imf.org/en/News/rss?language=eng", "IMF", CAT_INTL),
        RssFeedDef("https://feeds.worldbank.org/worldbank/news/rss", "World Bank", CAT_INTL),
        RssFeedDef("https://www.bis.org/rss/press_releases.rss", "BIS", CAT_INTL),
        RssFeedDef("https://www.oecd.org/economy/rss.xml", "OECD", CAT_INTL),

        // ── THINK TANKS & RESEARCH ──────────────────────────────────────────────
        RssFeedDef("https://www.brookings.edu/topic/economy/feed/", "Brookings", CAT_RESEARCH),
        RssFeedDef("https://www.piie.com/rss.xml", "Peterson Institute", CAT_RESEARCH),
        RssFeedDef("https://www.cato.org/rss/economics", "Cato Institute", CAT_RESEARCH),
        RssFeedDef("https://www.aei.org/feed/?tag=economics", "AEI", CAT_RESEARCH),
        RssFeedDef("https://fredblog.stlouisfed.org/feed/", "FRED Blog", CAT_RESEARCH),
        RssFeedDef("https://macroblog.typepad.com/macroblog/atom.xml", "Atlanta Fed", CAT_RESEARCH),
        RssFeedDef("https://libertystreeteconomics.newyorkfed.org/feed/", "NY Fed", CAT_RESEARCH)
    )
}
