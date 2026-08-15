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
data class ChatChart(
    @JvmField val seriesId: String,
    @JvmField val months: Int
)
