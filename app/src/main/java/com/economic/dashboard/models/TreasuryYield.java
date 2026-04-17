package com.economic.dashboard.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room Entity for Treasury Yield data.
 * Represents a cached Treasury yield rate with a timestamp.
 * Uses source + series + date as unique identifier.
 */
@Entity(tableName = "treasury_yields")
public class TreasuryYield {

    @PrimaryKey(autoGenerate = true)
    public int id;

    /**
     * Data source (e.g., "FRED", "Treasury", "Calculated")
     */
    @NonNull
    public String source = "";

    /**
     * Category (e.g., "Interest Rates", "Treasury")
     */
    @NonNull
    public String category = "";

    /**
     * Series name (e.g., "1 Month", "10 Year")
     */
    @NonNull
    public String series = "";

    /**
     * Date in format "YYYY-MM-DD"
     */
    @NonNull
    public String date = "";

    /**
     * The yield value (typically a percentage)
     */
    public double value = 0.0;

    /**
     * Unit of measurement (e.g., "%")
     */
    @NonNull
    public String unit = "";

    /**
     * Timestamp (in milliseconds) when this data was cached.
     * Used to determine if cache is stale.
     */
    public long cachedAtMillis = 0;

    // Default constructor for Room
    public TreasuryYield() {
    }

    /**
     * Constructor from an EconomicDataPoint.
     * Useful for converting API responses to cached entities.
     */
    public TreasuryYield(@NonNull EconomicDataPoint point, long cachedAtMillis) {
        this.source = point.getSource();
        this.category = point.getCategory();
        this.series = point.getSeries();
        this.date = point.getDate();
        this.value = point.getValue();
        this.unit = point.getUnit();
        this.cachedAtMillis = cachedAtMillis;
    }

    /**
     * Convert this cached entity back to an EconomicDataPoint.
     */
    @NonNull
    public EconomicDataPoint toEconomicDataPoint() {
        return new EconomicDataPoint(source, category, series, date, value, unit);
    }

    @Override
    @NonNull
    public String toString() {
        return "TreasuryYield{" +
                "id=" + id +
                ", source='" + source + '\'' +
                ", series='" + series + '\'' +
                ", date='" + date + '\'' +
                ", value=" + value +
                ", cachedAtMillis=" + cachedAtMillis +
                '}';
    }
}
