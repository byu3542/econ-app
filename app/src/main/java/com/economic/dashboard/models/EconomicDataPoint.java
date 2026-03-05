package com.economic.dashboard.models;

public class EconomicDataPoint {
    private String source;
    private String category;
    private String series;
    private String date;
    private double value;
    private String unit;

    public EconomicDataPoint(String source, String category, String series,
                              String date, double value, String unit) {
        this.source = source;
        this.category = category;
        this.series = series;
        this.date = date;
        this.value = value;
        this.unit = unit;
    }

    public String getSource()   { return source; }
    public String getCategory() { return category; }
    public String getSeries()   { return series; }
    public String getDate()     { return date; }
    public double getValue()    { return value; }
    public String getUnit()     { return unit; }
}
