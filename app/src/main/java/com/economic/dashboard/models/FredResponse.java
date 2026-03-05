package com.economic.dashboard.models;

import java.util.List;

public class FredResponse {
    public List<FredObservation> observations;

    public static class FredObservation {
        public String date;
        public String value;
    }
}
