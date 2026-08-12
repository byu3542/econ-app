package com.economic.dashboard.api

import java.util.Calendar
import java.util.Locale

// Shared helpers for the two api repositories (EconomicRepository fills the
// dashboard's live LiveData; HistoricalDataRepository fills the Room cache the
// AI Analyst reads). Each of these bodies used to exist as a private copy in
// both files — one shared symbol so the twins can't drift.

/** FRED/BLS/BEA/Treasury "is this observation usable" filter: rejects null, empty, "." and "ND". */
internal fun isValidValue(value: String?): Boolean =
    value != null && value.isNotEmpty() && value != "." && !value.equals("ND", ignoreCase = true)

/**
 * BLS "M01".."M12" period + year to "YYYY-MM-01". [year] stays nullable on
 * purpose: BlsResponse.year is String?, and both call sites rely on Kotlin's
 * String?.plus (a null year yields "null-MM-01", unchanged from the inline
 * code this replaces). Callers keep their own M-prefix/M13 checks and their
 * NumberFormatException handling — the M13 filter is deliberately
 * call-site-specific (EconomicRepository lets M13 through, the cache filler
 * does not).
 */
internal fun blsPeriodToDate(year: String?, period: String): String =
    year + "-" + String.format(Locale.US, "%02d", period.substring(1).toInt()) + "-01"

/** BLS POST body: the requested ids over the ApiConfig.BLS_HISTORY_YEARS window ending now. */
internal fun buildBlsRequestBody(seriesIds: List<String>): HashMap<String, Any> {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val startYear = currentYear - ApiConfig.BLS_HISTORY_YEARS.toInt()
    val body = HashMap<String, Any>()
    body["seriesid"] = seriesIds
    body["startyear"] = startYear.toString()
    body["endyear"] = currentYear.toString()
    body["registrationkey"] = ApiConfig.BLS_API_KEY
    return body
}

/** Comma-separated year list for BEA "Year" params, [startYear]..[endYear] inclusive. */
internal fun buildYearsCsv(startYear: Int, endYear: Int): String {
    val sb = StringBuilder()
    for (y in startYear..endYear) {
        if (sb.isNotEmpty()) sb.append(",")
        sb.append(y)
    }
    return sb.toString()
}
