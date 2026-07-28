package com.economic.dashboard.models

class FredResponse {
    var observations: List<FredObservation>? = null

    class FredObservation {
        var date: String? = null
        var value: String? = null
    }
}
