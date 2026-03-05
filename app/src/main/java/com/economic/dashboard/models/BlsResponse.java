package com.economic.dashboard.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class BlsResponse {
    @SerializedName("status")
    public String status;

    @SerializedName("Results")
    public BlsResults Results;

    public static class BlsResults {
        @SerializedName("series")
        public List<BlsSeries> series;
    }

    public static class BlsSeries {
        @SerializedName("seriesID")
        public String seriesID;

        @SerializedName("data")
        public List<BlsDataPoint> data;
    }

    public static class BlsDataPoint {
        @SerializedName("year")
        public String year;

        @SerializedName("period")
        public String period;

        @SerializedName("periodName")
        public String periodName;

        @SerializedName("value")
        public String value;
    }
}
