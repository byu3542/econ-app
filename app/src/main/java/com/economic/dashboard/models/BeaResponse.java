package com.economic.dashboard.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class BeaResponse {
    @SerializedName("BEAAPI")
    public BeaApi BEAAPI;

    public static class BeaApi {
        @SerializedName("Results")
        public BeaResults Results;
    }

    public static class BeaResults {
        @SerializedName("Data")
        public List<BeaDataPoint> Data;
    }

    public static class BeaDataPoint {
        @SerializedName("LineDescription")
        public String LineDescription;

        @SerializedName("SeriesCode")
        public String SeriesCode;

        @SerializedName("TimePeriod")
        public String TimePeriod;

        @SerializedName("DataValue")
        public String DataValue;

        @SerializedName("UNIT_MULT")
        public String UNIT_MULT;

        public String getDescription() {
            return (LineDescription != null && !LineDescription.isEmpty())
                ? LineDescription : SeriesCode;
        }
    }
}
