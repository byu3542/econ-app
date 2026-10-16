package com.economic.dashboard.analyst

import android.content.Context

import com.economic.dashboard.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Single source of truth for the analyst's identity (TICKET-P2-8, AI Laws
 * 30/12): one name, one marker, one explanation of where the numbers come
 * from. Every surface that names or marks the analyst — entry menus, the
 * chat sender label, the sheet header, the brief, shared text — builds its
 * label from here rather than repeating a string literal, so the five doors
 * read as one assistant.
 *
 * The marker doubles as the provenance affordance (AI Law 25): wherever it
 * appears on AI-generated content, tapping it opens [showAbout].
 */
object AnalystIdentity {

    /** The assistant's name. Codifies the name the app already used at the
     *  sheet header, sender label, system prompt, and greeting. */
    const val NAME = "economii Analyst"

    /** The AI-content marker, prefixed to labels and generated content. */
    const val MARKER = "✨"

    /** "✨ <action>" — the one way analyst entry points are labelled. */
    @JvmStatic
    fun label(action: String): String = "$MARKER $action"

    /** Attribution line prepended when analyst content leaves the app
     *  (share sheet) — the marker must travel with the content. */
    @JvmStatic
    fun shareAttribution(): String =
        "$MARKER $NAME — AI-generated analysis (U.S. Economic Monitor)\n\n"

    /**
     * The marker's tap target: what the analyst is and where its figures
     * come from. Inherits the app dialog theme (TICKET-54).
     */
    @JvmStatic
    fun showAbout(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.analyst_about_title))
            .setMessage(context.getString(R.string.analyst_about_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
