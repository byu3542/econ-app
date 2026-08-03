package com.economic.dashboard.models

import com.google.gson.annotations.SerializedName

class BlsResponse {
    @SerializedName("status")
    var status: String? = null

    /**
     * BLS reports soft failures with HTTP 200 + an empty `Results` object and
     * the reason in here (invalid registration key, daily threshold exceeded,
     * unknown series id). Without this field those all surfaced as a bare
     * "no data returned", which is indistinguishable from a genuinely empty
     * series.
     */
    @SerializedName("message")
    var message: List<String>? = null

    @SerializedName("Results")
    var Results: BlsResults? = null

    class BlsResults {
        @SerializedName("series")
        var series: List<BlsSeries>? = null
    }

    class BlsSeries {
        @SerializedName("seriesID")
        var seriesID: String? = null

        @SerializedName("data")
        var data: List<BlsDataPoint>? = null
    }

    class BlsDataPoint {
        @SerializedName("year")
        var year: String? = null

        @SerializedName("period")
        var period: String? = null

        @SerializedName("periodName")
        var periodName: String? = null

        @SerializedName("value")
        var value: String? = null
    }
}
