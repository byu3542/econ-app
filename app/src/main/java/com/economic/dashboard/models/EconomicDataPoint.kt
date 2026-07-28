package com.economic.dashboard.models

data class EconomicDataPoint(
    val source: String,
    val category: String,
    val series: String,
    val date: String,
    val value: Double,
    val unit: String
)
