package com.economic.dashboard.models

import com.google.gson.annotations.SerializedName

class BlsResponse {
    @SerializedName("status")
    var status: String? = null

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
