package com.economic.dashboard.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

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
    indices = {@Index(value = {"seriesId", "date"}, unique = true)}
)
public class EconomicHistoryEntry {

    @PrimaryKey(autoGenerate = true)
    public int id;

    /** API series identifier: e.g. "DGS10", "LNS14000000", "GDP_BEA_T10101" */
    @NonNull
    public String seriesId = "";

    /** Human-readable name: e.g. "10-Year Treasury", "Unemployment Rate" */
    @NonNull
    public String seriesName = "";

    /** Grouping label used in the AI context block: "Treasury", "Employment", "Housing", "GDP" */
    @NonNull
    public String category = "";

    /** Date string "YYYY-MM-DD" */
    @NonNull
    public String date = "";

    /** The data value (rate, percent, index, etc.) */
    public double value = 0.0;

    /** Unit label: "%", "Index", "$/hr", etc. */
    @NonNull
    public String unit = "";

    /** System.currentTimeMillis() when this row was last fetched and stored */
    public long cachedAtMillis = 0;

    // Required by Room
    public EconomicHistoryEntry() {}

    public EconomicHistoryEntry(
            @NonNull String seriesId,
            @NonNull String seriesName,
            @NonNull String category,
            @NonNull String date,
            double value,
            @NonNull String unit,
            long cachedAtMillis) {
        this.seriesId      = seriesId;
        this.seriesName    = seriesName;
        this.category      = category;
        this.date          = date;
        this.value         = value;
        this.unit          = unit;
        this.cachedAtMillis = cachedAtMillis;
    }
}
