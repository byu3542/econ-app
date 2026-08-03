package com.economic.dashboard.api

import android.util.Log
import android.util.Xml
import com.economic.dashboard.models.BeaResponse
import com.economic.dashboard.models.BlsResponse
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.models.FredResponse
import com.economic.dashboard.utils.AppExecutors
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.Calendar
import java.util.Locale

class EconomicRepository {

    interface DataCallback<T> {
        fun onSuccess(data: T)
        fun onError(error: String)
    }

    // ============================================================
    // FRED - Generic Fetcher
    // ============================================================
    fun fetchFredData(seriesId: String, seriesName: String, callback: DataCallback<List<EconomicDataPoint>>) {
        fetchFredData(seriesId, seriesName, "5", null, "FRED", callback)
    }

    fun fetchFredData(seriesId: String, seriesName: String, limit: String, callback: DataCallback<List<EconomicDataPoint>>) {
        fetchFredData(seriesId, seriesName, limit, null, "FRED", callback)
    }

    fun fetchFredData(seriesId: String, seriesName: String, limit: String, frequency: String?, callback: DataCallback<List<EconomicDataPoint>>) {
        fetchFredData(seriesId, seriesName, limit, frequency, "FRED", callback)
    }

    /** Full-control overload — callers should pass the correct category for display and filtering. */
    fun fetchFredData(
        seriesId: String, seriesName: String, limit: String, frequency: String?,
        category: String, callback: DataCallback<List<EconomicDataPoint>>
    ) {
        AppExecutors.networkIO().execute {
            try {
                val params = HashMap<String, String>()
                params["series_id"] = seriesId
                params["api_key"] = ApiConfig.FRED_API_KEY
                params["file_type"] = "json"
                params["sort_order"] = "desc"
                params["limit"] = limit
                if (frequency != null) {
                    params["frequency"] = frequency
                }

                val call = RetrofitClient.getFredService().getFredData(params)
                val response = call.execute()
                val body = response.body()

                if (!response.isSuccessful || body == null) {
                    var errorDetail = ""
                    val errorBody = response.errorBody()
                    if (errorBody != null) {
                        try { errorDetail = " - " + errorBody.string() } catch (ignored: Exception) {}
                    }
                    callback.onError("FRED HTTP error: " + response.code() + errorDetail)
                    return@execute
                }

                val results = ArrayList<EconomicDataPoint>()
                val observations = body.observations
                if (observations != null) {
                    for (obs in observations) {
                        val value = obs.value
                        val date = obs.date
                        if (date != null && value != null && isValidValue(value)) {
                            try {
                                results.add(EconomicDataPoint("FRED", category, seriesName, date, value.toDouble(), "%"))
                            } catch (ignored: NumberFormatException) {}
                        }
                    }
                }

                if (results.isEmpty()) {
                    callback.onError("No valid data found for $seriesName")
                } else {
                    callback.onSuccess(results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "FRED fetch error", e)
                callback.onError("FRED fetch failed: " + e.message)
            }
        }
    }

    // ============================================================
    // PCE INFLATION — FRED
    // ============================================================
    fun fetchPCEData(callback: DataCallback<List<EconomicDataPoint>>) {
        AppExecutors.networkIO().execute {
            try {
                // Fetch PCE (all items) — 120 months for percentile context
                val pceAll = fetchFredSync(ApiConfig.FRED_PCE, "PCE Price Index", ApiConfig.FRED_PCE_LIMIT, "m", "Inflation")
                val pceCore = fetchFredSync(ApiConfig.FRED_CORE_PCE, "Core PCE Price Index", ApiConfig.FRED_PCE_LIMIT, "m", "Inflation")

                val combined = ArrayList<EconomicDataPoint>()
                combined.addAll(pceAll)
                combined.addAll(pceCore)

                if (combined.isEmpty()) {
                    callback.onError("No PCE data returned")
                } else {
                    combined.sortWith(compareBy { it.date })
                    callback.onSuccess(combined)
                }
            } catch (e: Exception) {
                Log.e(TAG, "PCE fetch error", e)
                callback.onError("PCE fetch failed: " + e.message)
            }
        }
    }

    // ============================================================
    // HOUSING DATA — FRED
    // ============================================================
    fun fetchHousingData(callback: DataCallback<List<EconomicDataPoint>>) {
        AppExecutors.networkIO().execute {
            try {
                val starts = fetchFredSync(
                    ApiConfig.FRED_HOUSING_STARTS, "Housing Starts",
                    ApiConfig.FRED_HOUSING_LIMIT, "m", "Housing"
                )
                val sales = fetchFredSync(
                    ApiConfig.FRED_EXISTING_HOME_SALES, "Existing Home Sales",
                    ApiConfig.FRED_HOUSING_LIMIT, "m", "Housing"
                )

                val combined = ArrayList<EconomicDataPoint>()
                combined.addAll(starts)
                combined.addAll(sales)

                if (combined.isEmpty()) {
                    callback.onError("No housing data returned")
                } else {
                    combined.sortWith(compareBy { it.date })
                    callback.onSuccess(combined)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Housing fetch error", e)
                callback.onError("Housing fetch failed: " + e.message)
            }
        }
    }

    /** Synchronous FRED helper for use inside compound fetch methods. Caller must supply the correct category. */
    @Throws(Exception::class)
    private fun fetchFredSync(
        seriesId: String, seriesName: String,
        limit: String, frequency: String?, category: String
    ): List<EconomicDataPoint> {
        val params = HashMap<String, String>()
        params["series_id"] = seriesId
        params["api_key"] = ApiConfig.FRED_API_KEY
        params["file_type"] = "json"
        params["sort_order"] = "desc"
        params["limit"] = limit
        if (frequency != null) params["frequency"] = frequency

        val call = RetrofitClient.getFredService().getFredData(params)
        val response = call.execute()
        val body = response.body()

        val results = ArrayList<EconomicDataPoint>()
        if (response.isSuccessful && body != null) {
            val observations = body.observations
            if (observations != null) {
                for (obs in observations) {
                    val value = obs.value
                    val date = obs.date
                    if (date != null && value != null && isValidValue(value)) {
                        try {
                            results.add(EconomicDataPoint("FRED", category, seriesName, date, value.toDouble(), "Index"))
                        } catch (ignored: NumberFormatException) {}
                    }
                }
            }
        }
        return results
    }

    // ============================================================
    // MBS & MORTGAGE RATES — FRED
    // ============================================================
    fun fetchMbsMortgageData(callback: DataCallback<List<EconomicDataPoint>>) {
        AppExecutors.networkIO().execute {
            try {
                val bankMbs = fetchFredSync(ApiConfig.FRED_BANK_MBS, "Bank MBS Holdings", ApiConfig.FRED_MBS_LIMIT, "w", "Bonds")
                val fedMbs = fetchFredSync(ApiConfig.FRED_FED_MBS, "Fed MBS Holdings", ApiConfig.FRED_MBS_LIMIT, "w", "Bonds")
                val mortgage = fetchFredSync(ApiConfig.FRED_MORTGAGE30, "30-Yr Mortgage Rate", ApiConfig.FRED_MBS_LIMIT, "w", "Housing")

                val combined = ArrayList<EconomicDataPoint>()
                combined.addAll(bankMbs)
                combined.addAll(fedMbs)
                combined.addAll(mortgage)

                if (combined.isEmpty()) {
                    callback.onError("No MBS/mortgage data returned")
                } else {
                    combined.sortWith(compareBy { it.date })
                    callback.onSuccess(combined)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MBS/mortgage fetch error", e)
                callback.onError("MBS/mortgage fetch failed: " + e.message)
            }
        }
    }

    // ============================================================
    // GDP — BEA NIPA TABLE T10101
    // ============================================================
    fun fetchGDPData(callback: DataCallback<List<EconomicDataPoint>>) {
        AppExecutors.networkIO().execute {
            try {
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val yearsBuilder = StringBuilder()
                for (y in currentYear - 10..currentYear) {
                    if (yearsBuilder.isNotEmpty()) yearsBuilder.append(",")
                    yearsBuilder.append(y)
                }

                val params = HashMap<String, String>()
                params["UserID"] = ApiConfig.BEA_API_KEY
                params["method"] = "GetData"
                params["DatasetName"] = ApiConfig.BEA_DATASET
                params["TableName"] = ApiConfig.BEA_TABLE
                params["Frequency"] = ApiConfig.BEA_FREQUENCY
                params["Year"] = yearsBuilder.toString()
                params["ResultFormat"] = "JSON"

                val call = RetrofitClient.getBeaService().getBeaData(params)
                val response = call.execute()
                val beaResp = response.body()

                if (!response.isSuccessful || beaResp == null) {
                    callback.onError("BEA HTTP error: " + response.code())
                    return@execute
                }

                val results = ArrayList<EconomicDataPoint>()
                val dataPoints = beaResp.BEAAPI?.Results?.Data

                if (dataPoints != null) {
                    for (dp in dataPoints) {
                        val dataValue = dp.DataValue
                        val timePeriod = dp.TimePeriod
                        // getDescription() can be null only if BEA omits both LineDescription and
                        // SeriesCode; EconomicDataPoint.series is non-null, so skip that entry.
                        val description = dp.getDescription()
                        if (dataValue != null && isValidValue(dataValue) && timePeriod != null && description != null) {
                            try {
                                val value = dataValue.replace(",", "").toDouble()
                                val date = beaTimePeriodToDate(timePeriod)
                                results.add(EconomicDataPoint("BEA", "GDP", description, date, value, "%"))
                            } catch (ignored: NumberFormatException) {}
                        }
                    }
                }

                if (results.isEmpty()) {
                    callback.onError("No GDP data returned")
                } else {
                    results.sortWith(compareBy { it.date })
                    callback.onSuccess(results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "BEA fetch error", e)
                callback.onError("GDP fetch failed: " + e.message)
            }
        }
    }

    /** Converts BEA period strings like "2023Q1" → "2023-01-01". */
    private fun beaTimePeriodToDate(timePeriod: String): String {
        if (timePeriod.contains("Q")) {
            val parts = timePeriod.split("Q")
            if (parts.size == 2) {
                val year = parts[0]
                val quarter = parts[1].toInt()
                val month = when (quarter) {
                    1 -> "01"
                    2 -> "04"
                    3 -> "07"
                    else -> "10"
                }
                return "$year-$month-01"
            }
        }
        return timePeriod
    }

    // ============================================================
    // EMPLOYMENT — BLS (Unemployment, Participation, Level)
    // ============================================================
    fun fetchEmploymentData(callback: DataCallback<List<EconomicDataPoint>>) {
        fetchBlsData(
            listOf(ApiConfig.BLS_UNEMPLOYMENT_RATE, ApiConfig.BLS_EMPLOYMENT_LEVEL, ApiConfig.BLS_LABOR_PARTICIPATION),
            "Employment",
            arrayOf("Unemployment Rate", "Employment Level", "Labor Force Participation Rate"),
            arrayOf("%", "Thousands", "%"),
            callback
        )
    }

    // ============================================================
    // CPI — BLS (CPI-U and CPI-W)
    // ============================================================
    fun fetchCPIData(callback: DataCallback<List<EconomicDataPoint>>) {
        fetchBlsData(
            listOf(ApiConfig.BLS_CPI_U, ApiConfig.BLS_CPI_W),
            "Inflation",
            arrayOf("CPI-U All Items", "CPI-W All Items"),
            arrayOf("Index", "Index"),
            callback
        )
    }

    // ============================================================
    // WAGES — BLS (Hourly and Weekly Earnings)
    // ============================================================
    fun fetchWageData(callback: DataCallback<List<EconomicDataPoint>>) {
        fetchBlsData(
            listOf(ApiConfig.BLS_HOURLY_EARNINGS, ApiConfig.BLS_WEEKLY_EARNINGS),
            "Employment",
            arrayOf("Average Hourly Earnings - Private", "Average Weekly Earnings - Private"),
            arrayOf("$/hr", "$/wk"),
            callback
        )
    }

    /**
     * Shared BLS fetch helper. Sends a POST request for the given series IDs,
     * maps each to a human-readable name, and returns chronologically sorted results.
     */
    private fun fetchBlsData(
        seriesIds: List<String>, category: String,
        seriesNames: Array<String>, units: Array<String>,
        callback: DataCallback<List<EconomicDataPoint>>
    ) {
        AppExecutors.networkIO().execute {
            try {
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val startYear = currentYear - ApiConfig.BLS_HISTORY_YEARS.toInt()

                val body = HashMap<String, Any>()
                body["seriesid"] = seriesIds
                body["startyear"] = startYear.toString()
                body["endyear"] = currentYear.toString()
                body["registrationkey"] = ApiConfig.BLS_API_KEY

                val call = RetrofitClient.getBlsService().getBlsData(body)
                val response = call.execute()
                val blsResp = response.body()

                if (!response.isSuccessful || blsResp == null) {
                    var errorDetail = ""
                    val errorBody = response.errorBody()
                    if (errorBody != null) {
                        try { errorDetail = " - " + errorBody.string() } catch (ignored: Exception) {}
                    }
                    callback.onError("BLS HTTP error: " + response.code() + errorDetail)
                    return@execute
                }

                val results = ArrayList<EconomicDataPoint>()
                val seriesList = blsResp.Results?.series

                if (seriesList != null) {
                    for (series in seriesList) {
                        // Map series ID → human name + unit. Falls back to the raw
                        // (possibly blank) series ID if BLS ever omits it — EconomicDataPoint.series
                        // is non-null, unlike the original Java's nullable seriesID field.
                        var seriesName = series.seriesID ?: ""
                        var unit = ""
                        for (i in seriesIds.indices) {
                            if (seriesIds[i] == series.seriesID) {
                                seriesName = seriesNames[i]
                                unit = units[i]
                                break
                            }
                        }

                        val dataList = series.data
                        if (dataList != null) {
                            for (dp in dataList) {
                                val period = dp.period
                                val value = dp.value
                                try {
                                    if (period != null && period.startsWith("M") && value != null && isValidValue(value)) {
                                        val month = period.substring(1).toInt()
                                        val date = dp.year + "-" +
                                            String.format(Locale.US, "%02d", month) +
                                            "-01"
                                        results.add(EconomicDataPoint("BLS", category, seriesName, date, value.toDouble(), unit))
                                    }
                                } catch (ignored: NumberFormatException) {}
                            }
                        }
                    }
                }

                if (results.isEmpty()) {
                    // Include what BLS actually said — a bad key or a blown daily
                    // quota comes back as HTTP 200 with an empty Results block.
                    val detail = blsResp.message?.joinToString("; ") ?: ""
                    val msg = "No BLS data returned for $seriesIds " +
                        "(status=${blsResp.status}${if (detail.isEmpty()) "" else "; $detail"})"
                    Log.w(TAG, msg)
                    callback.onError(msg)
                } else {
                    results.sortWith(compareBy { it.date })
                    Log.i(TAG, "BLS $seriesIds → status=${blsResp.status}, parsed ${results.size} points")
                    callback.onSuccess(results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLS fetch error", e)
                callback.onError("BLS fetch failed: " + e.message)
            }
        }
    }

    // ============================================================
    // TREASURY YIELD CURVE
    // ============================================================
    fun fetchTreasuryRates(callback: DataCallback<List<EconomicDataPoint>>) {
        AppExecutors.networkIO().execute {
            try {
                callback.onSuccess(fetchTreasuryRatesSync())
            } catch (e: Exception) {
                Log.e(TAG, "Treasury fetch error", e)
                callback.onError("Treasury fetch failed: " + e.message)
            }
        }
    }

    /**
     * Blocking variant of [fetchTreasuryRates] — runs the request and the XML
     * parse on the CALLING thread and throws on failure.
     *
     * Exists for [com.economic.dashboard.workers.YieldRefreshWorker], which
     * must not return from `doWork()` until the fetch is durable: the callback
     * form dispatches onto [AppExecutors.networkIO] and returns immediately,
     * which let WorkManager tear the process down mid-request.
     */
    @Throws(Exception::class)
    fun fetchTreasuryRatesSync(): List<EconomicDataPoint> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val url = ApiConfig.TREASURY_BASE_URL +
            "xml?data=daily_treasury_yield_curve&field_tdr_date_value=" + currentYear

        val client = RetrofitClient.buildClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body

        if (!response.isSuccessful || responseBody == null) {
            throw IOException("Treasury HTTP error: " + response.code)
        }

        return parseTreasuryXml(responseBody.string())
    }

    @Throws(Exception::class)
    private fun parseTreasuryXml(xmlContent: String): List<EconomicDataPoint> {
        var results = ArrayList<EconomicDataPoint>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xmlContent))

        var currentDate: String? = null
        val currentRates = HashMap<String, String>()
        var inProperties = false
        var currentElement: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    currentElement = name
                    if (name == "properties") {
                        inProperties = true
                        currentDate = null
                        currentRates.clear()
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inProperties && currentElement != null) {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            if (currentElement == "NEW_DATE") {
                                currentDate = formatTreasuryDate(text)
                            }
                            val fields = ApiConfig.TREASURY_FIELDS
                            for (field in fields) {
                                if (currentElement == field) {
                                    currentRates[field] = text
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "properties" && inProperties) {
                        val dateAtClose = currentDate
                        if (dateAtClose != null) {
                            for (i in ApiConfig.TREASURY_FIELDS.indices) {
                                val v = currentRates[ApiConfig.TREASURY_FIELDS[i]]
                                if (v != null && isValidValue(v)) {
                                    try {
                                        results.add(
                                            EconomicDataPoint(
                                                "Treasury", "Yield Curve",
                                                ApiConfig.TREASURY_MATURITIES[i],
                                                dateAtClose, v.toDouble(), "%"
                                            )
                                        )
                                    } catch (ignored: NumberFormatException) {}
                                }
                            }
                        }
                        inProperties = false
                    }
                    currentElement = null
                }
            }
            eventType = parser.next()
        }

        results.sortWith(compareByDescending { it.date })
        if (results.size > ApiConfig.TREASURY_DAYS_BACK * 8) {
            results = ArrayList(results.subList(0, ApiConfig.TREASURY_DAYS_BACK * 8))
        }
        return results
    }

    private fun formatTreasuryDate(rawDate: String?): String {
        if (rawDate == null) return ""
        if (rawDate.contains("T")) return rawDate.substring(0, 10)
        return if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate
    }

    private fun isValidValue(value: String?): Boolean =
        value != null && value.isNotEmpty() && value != "." && !value.equals("ND", ignoreCase = true)

    companion object {
        private const val TAG = "EconomicRepository"
    }
}
