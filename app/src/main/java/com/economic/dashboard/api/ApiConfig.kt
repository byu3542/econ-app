package com.economic.dashboard.api

import com.economic.dashboard.BuildConfig

object ApiConfig {

    // Read from local.properties via BuildConfig (set BEA_API_KEY= in local.properties)
    @JvmField val BEA_API_KEY: String = BuildConfig.BEA_API_KEY
    // Read from local.properties via BuildConfig (set BLS_API_KEY= in local.properties)
    @JvmField val BLS_API_KEY: String = BuildConfig.BLS_API_KEY
    // Read from local.properties via BuildConfig (set FRED_API_KEY= in local.properties)
    @JvmField val FRED_API_KEY: String = BuildConfig.FRED_API_KEY

    // AI Analyst proxy (see proxy/README.md). The Anthropic key is held by the
    // proxy server and is no longer compiled into the app.
    @JvmField val PROXY_BASE_URL: String = BuildConfig.PROXY_BASE_URL
    @JvmField val PROXY_APP_TOKEN: String = BuildConfig.PROXY_APP_TOKEN

    // Read from local.properties via BuildConfig (set NEWS_API_KEY= in local.properties)
    @JvmField val NEWS_API_KEY: String = BuildConfig.NEWS_API_KEY

    @JvmField val TREASURY_BASE_URL: String =
        "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/"
    @JvmField val BEA_BASE_URL: String = "https://apps.bea.gov/api/"
    @JvmField val BLS_BASE_URL_V2: String = "https://api.bls.gov/publicAPI/v2/"
    @JvmField val FRED_BASE_URL: String = "https://api.stlouisfed.org/fred/"

    @JvmField val BLS_UNEMPLOYMENT_RATE: String = "LNS14000000"
    @JvmField val BLS_EMPLOYMENT_LEVEL: String = "LNS12000000"
    @JvmField val BLS_LABOR_PARTICIPATION: String = "LNS11300000"
    @JvmField val BLS_CPI_U: String = "CUUR0000SA0"
    @JvmField val BLS_CPI_W: String = "CUSR0000SA0"
    @JvmField val BLS_HOURLY_EARNINGS: String = "CES0500000003"
    @JvmField val BLS_WEEKLY_EARNINGS: String = "CES0500000011"

    @JvmField val BEA_DATASET: String = "NIPA"
    @JvmField val BEA_TABLE: String = "T10101"
    @JvmField val BEA_FREQUENCY: String = "Q"
    @JvmField val TREASURY_DAYS_BACK: Int = 30

    @JvmField val TREASURY_MATURITIES: Array<String> = arrayOf(
        "1 Month", "3 Month", "6 Month", "1 Year",
        "2 Year", "5 Year", "10 Year", "30 Year"
    )
    @JvmField val TREASURY_FIELDS: Array<String> = arrayOf(
        "BC_1MONTH", "BC_3MONTH", "BC_6MONTH", "BC_1YEAR",
        "BC_2YEAR", "BC_5YEAR", "BC_10YEAR", "BC_30YEAR"
    )

    @JvmField val FRED_FED_FUNDS: String = "DFF"
    @JvmField val FRED_ISM_PMI: String = "NAPM"
    @JvmField val FRED_10Y_MATURITY: String = "DGS10"
    @JvmField val FRED_2Y_MATURITY: String = "DGS2"
    @JvmField val FRED_3M_MATURITY: String = "DGS3MO"

    // Stock Market Indices
    @JvmField val FRED_SP500: String = "SP500"
    @JvmField val FRED_WILSHIRE5000: String = "WILL5000INDX"
    @JvmField val FRED_NASDAQ: String = "NASDAQCOM"
    @JvmField val FRED_VIX: String = "VIXCLS"

    // PCE Inflation
    @JvmField val FRED_PCE: String = "PCEPI"
    @JvmField val FRED_CORE_PCE: String = "PCEPILFE"

    // Bond Market Spreads
    @JvmField val FRED_BAA_SPREAD: String = "BAA10Y"      // Moody's BAA vs 10Y Treasury
    @JvmField val FRED_HY_SPREAD: String = "BAMLH0A0HYM2" // ICE BofA US HY OAS
    // NOTE: MOVE Index is not available via FRED API. Removed FRED_MOVE to prevent
    // duplicate HY spread fetch masquerading as MOVE data.

    // Housing indicators
    @JvmField val FRED_HOUSING_STARTS: String = "HOUST"
    @JvmField val FRED_EXISTING_HOME_SALES: String = "EXHOSLUSM495S"

    // MBS & Mortgage indicators
    @JvmField val FRED_BANK_MBS: String = "TMBACBW027SBOG"
    @JvmField val FRED_FED_MBS: String = "WSHOMCB"
    @JvmField val FRED_MORTGAGE30: String = "MORTGAGE30US"
    @JvmField val FRED_MBS_LIMIT: String = "260"
    @JvmField val FRED_HOUSING_LIMIT: String = "60"

    // History limits
    @JvmField val BLS_HISTORY_YEARS: String = "5" // years of BLS history to fetch (used as: currentYear - BLS_HISTORY_YEARS)
    @JvmField val FRED_PCE_LIMIT: String = "120"
    @JvmField val STOCK_INDICES_LIMIT: String = "120"
    @JvmField val BOND_SPREADS_LIMIT: String = "120"
}
