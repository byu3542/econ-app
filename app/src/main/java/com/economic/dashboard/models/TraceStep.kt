package com.economic.dashboard.models

/**
 * MOT-05: one tool call in the analyst's thinking trace.
 *
 * [id] is the Anthropic `tool_use` id, so a step is matched to its result by
 * id, never by label (two `get_series` calls share a verb). Mutated on the UI
 * thread only. Transient — like the rest of the trace, never persisted.
 */
class TraceStep(
    val id: String,
    var label: String,
    var state: State = State.PENDING,
    /** Wall time of the tool call; negative until it has run. */
    var durationMs: Long = -1L
) {
    enum class State { PENDING, RUNNING, DONE, FAILED }
}
