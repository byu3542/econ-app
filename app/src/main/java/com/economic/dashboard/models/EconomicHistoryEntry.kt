package com.economic.dashboard.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the economic_history table.
 *
 * Stores up to 2 years of historical data for key series:
 *   - Treasury yields (10Y, 2Y, 3M) — weekly from FRED
 *   - 30-Year Mortgage Rate          — weekly from FRED
 *   - Unemployment Rate              — monthly from BLS
 *   - GDP Growth (QoQ annualized)    — quarterly from BEA
 *
 * The (seriesId, date) unique index prevents duplicate rows and lets
 * @Insert(REPLACE) act as an upsert on refresh.
 */
@Entity(
    tableName = "economic_history",
    indices = [Index(value = ["seriesId", "date"], unique = true)]
)
data class EconomicHistoryEntry @JvmOverloads constructor(
    /** API series identifier: e.g. "DGS10", "LNS14000000", "GDP_BEA_T10101" */
    var seriesId: String,
    /** Human-readable name: e.g. "10-Year Treasury", "Unemployment Rate" */
    var seriesName: String,
    /** Grouping label used in the AI context block: "Treasury", "Employment", "Housing", "GDP" */
    var category: String,
    /** Date string "YYYY-MM-DD" */
    var date: String,
    /** The data value (rate, percent, index, etc.) */
    var value: Double,
    /** Unit label: "%", "Index", "$/hr", etc. */
    var unit: String,
    /** System.currentTimeMillis() when this row was last fetched and stored */
    var cachedAtMillis: Long,
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
)
