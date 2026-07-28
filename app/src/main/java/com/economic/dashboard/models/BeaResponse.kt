package com.economic.dashboard.models

import com.google.gson.annotations.SerializedName

class BeaResponse {
    @SerializedName("BEAAPI")
    var BEAAPI: BeaApi? = null

    class BeaApi {
        @SerializedName("Results")
        var Results: BeaResults? = null
    }

    class BeaResults {
        @SerializedName("Data")
        var Data: List<BeaDataPoint>? = null
    }

    class BeaDataPoint {
        @SerializedName("LineDescription")
        var LineDescription: String? = null

        @SerializedName("SeriesCode")
        var SeriesCode: String? = null

        @SerializedName("TimePeriod")
        var TimePeriod: String? = null

        @SerializedName("DataValue")
        var DataValue: String? = null

        @SerializedName("UNIT_MULT")
        var UNIT_MULT: String? = null

        fun getDescription(): String? =
            if (!LineDescription.isNullOrEmpty()) LineDescription else SeriesCode
    }
}
