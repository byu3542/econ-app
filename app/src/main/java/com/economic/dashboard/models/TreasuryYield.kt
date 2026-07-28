package com.economic.dashboard.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for Treasury Yield data.
 * Represents a cached Treasury yield rate with a timestamp.
 * Uses source + series + date as unique identifier.
 */
@Entity(tableName = "treasury_yields")
data class TreasuryYield(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    /** Data source (e.g., "FRED", "Treasury", "Calculated") */
    var source: String = "",

    /** Category (e.g., "Interest Rates", "Treasury") */
    var category: String = "",

    /** Series name (e.g., "1 Month", "10 Year") */
    var series: String = "",

    /** Date in format "YYYY-MM-DD" */
    var date: String = "",

    /** The yield value (typically a percentage) */
    var value: Double = 0.0,

    /** Unit of measurement (e.g., "%") */
    var unit: String = "",

    /**
     * Timestamp (in milliseconds) when this data was cached.
     * Used to determine if cache is stale.
     */
    var cachedAtMillis: Long = 0
) {
    /** Constructor from an EconomicDataPoint. Converts an API response to a cached entity. */
    constructor(point: EconomicDataPoint, cachedAtMillis: Long) : this(
        source = point.source,
        category = point.category,
        series = point.series,
        date = point.date,
        value = point.value,
        unit = point.unit,
        cachedAtMillis = cachedAtMillis
    )

    /** Convert this cached entity back to an EconomicDataPoint. */
    fun toEconomicDataPoint(): EconomicDataPoint =
        EconomicDataPoint(source, category, series, date, value, unit)

    override fun toString(): String =
        "TreasuryYield{" +
            "id=" + id +
            ", source='" + source + '\'' +
            ", series='" + series + '\'' +
            ", date='" + date + '\'' +
            ", value=" + value +
            ", cachedAtMillis=" + cachedAtMillis +
            '}'
}
