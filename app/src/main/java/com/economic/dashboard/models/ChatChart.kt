package com.economic.dashboard.models

/**
 * One chart the analyst asked the app to draw, via the `render_chart` tool
 * (TICKET-P2-2).
 *
 * The model emits data — a series id and a window — and the client owns the
 * pixels. It replaces the old `[CHART:SERIES_ID:24M]` tag, where the model
 * emitted a rendering token into its prose and ChatAdapter regex-parsed it back
 * out. Nothing here carries values: the renderer reads the same points
 * get_series serves (AI Law 13), so a chart can never disagree with the numbers
 * in the reply.
 *
 * [seriesId] is always a canonical EconomicSeriesRegistry id — the tool
 * executor resolves case-insensitively before constructing this, because a
 * wrong-cased id completes with no API error and would otherwise vanish
 * silently.
 */
data class ChatChart @JvmOverloads constructor(
    @JvmField val seriesId: String,
    @JvmField val months: Int,
    /** 2026-10-12: [VIEW_CHART] (render_chart) or [VIEW_TABLE] (render_table). */
    @JvmField val view: String = VIEW_CHART
) {
    val isTable: Boolean get() = view == VIEW_TABLE

    companion object {
        const val VIEW_CHART = "chart"
        const val VIEW_TABLE = "table"
    }
}
