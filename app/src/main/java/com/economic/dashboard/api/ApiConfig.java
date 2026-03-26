package com.economic.dashboard.api;

import com.economic.dashboard.BuildConfig;

public class ApiConfig {

    // ============================================================
    // API KEYS
    // ============================================================
    public static final String BEA_API_KEY = "13425E5B-2ECF-4378-9786-E48A81B1AEAE";
    public static final String BLS_API_KEY = "5198938c150e4e93a417f3769a5cc077";
    public static final String FRED_API_KEY = "02e1293e2b997b87df09df66c0e8fb86";

    // Read from BuildConfig (populated from local.properties)
    public static final String ANTHROPIC_API_KEY = BuildConfig.ANTHROPIC_API_KEY;

    // ============================================================
    // BASE URLs
    // ============================================================
    public static final String TREASURY_BASE_URL =
        "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/";
    public static final String BEA_BASE_URL = "https://apps.bea.gov/api/";
    public static final String BLS_BASE_URL_V2 = "https://api.bls.gov/publicAPI/v2/";
    public static final String FRED_BASE_URL = "https://api.stlouisfed.org/fred/";

    // ============================================================
    // BLS Series IDs
    // ============================================================
    public static final String BLS_UNEMPLOYMENT_RATE   = "LNS14000000";
    public static final String BLS_EMPLOYMENT_LEVEL    = "LNS12000000";
    public static final String BLS_LABOR_PARTICIPATION = "LNS11300000";
    public static final String BLS_CPI_U = "CUUR0000SA0";
    public static final String BLS_CPI_W = "CUSR0000SA0";
    public static final String BLS_HOURLY_EARNINGS  = "CES0500000003";
    public static final String BLS_WEEKLY_EARNINGS  = "CES0500000011";

    // ============================================================
    // Configs
    // ============================================================
    public static final String BEA_DATASET   = "NIPA";
    public static final String BEA_TABLE     = "T10101";
    public static final String BEA_FREQUENCY = "Q";
    public static final int TREASURY_DAYS_BACK = 30;

    public static final String[] TREASURY_MATURITIES = {
        "1 Month", "3 Month", "6 Month", "1 Year",
        "2 Year", "5 Year", "10 Year", "30 Year"
    };
    public static final String[] TREASURY_FIELDS = {
        "BC_1MONTH", "BC_3MONTH", "BC_6MONTH", "BC_1YEAR",
        "BC_2YEAR", "BC_5YEAR", "BC_10YEAR", "BC_30YEAR"
    };

    public static final String FRED_FED_FUNDS    = "DFF";
    public static final String FRED_ISM_PMI      = "NAPM";
    public static final String FRED_10Y_MATURITY = "DGS10";
    public static final String FRED_2Y_MATURITY  = "DGS2";
    public static final String FRED_3M_MATURITY  = "DGS3MO";

    // PCE Inflation (Fed’s preferred measure) — FRED
    public static final String FRED_PCE      = "PCEPI";        // PCE Price Index (all items)
    public static final String FRED_CORE_PCE = "PCEPILFE";     // Core PCE (ex food & energy)

    // Housing indicators — FRED
    public static final String FRED_HOUSING_STARTS       = "HOUST";          // Housing Starts (thousands)
    public static final String FRED_EXISTING_HOME_SALES  = "EXHOSLUSM495S";  // Existing Home Sales (thousands)

    // MBS & Mortgage indicators — FRED
    public static final String FRED_BANK_MBS   = "TMBACBW027SBOG"; // Commercial Bank MBS Holdings (billions)
    public static final String FRED_FED_MBS    = "WSHOMCB";        // Fed MBS Holdings (billions)
    public static final String FRED_MORTGAGE30 = "MORTGAGE30US";   // 30-Year Fixed Mortgage Rate (%)
    public static final String FRED_MBS_LIMIT  = "260";            // ~5 years weekly data

    // History limits
    public static final String BLS_HISTORY_MONTHS = "10"; // years back for BLS percentile history
    public static final String FRED_PCE_LIMIT     = "120"; // 10 years monthly
    public static final String FRED_HOUSING_LIMIT = "36";  // 3 years monthly
}
