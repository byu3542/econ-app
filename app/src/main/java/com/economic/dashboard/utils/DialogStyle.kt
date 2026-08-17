package com.economic.dashboard.utils

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.economic.dashboard.R

/**
 * Tints a confirmation dialog's positive button as destructive.
 *
 * TICKET-54 puts the shared dialog chrome in the theme
 * (`ThemeOverlay.Econ.MaterialAlertDialog`), but destructiveness is per-dialog
 * — a theme can't know that "Clear cache" is destructive and "Save alert" is
 * not. The button styles are resolved at inflation, so the only seam left after
 * [AlertDialog.show] is the colour itself.
 *
 * Call immediately after `show()`; the button does not exist before that.
 */
fun AlertDialog.markDestructive() {
    getButton(AlertDialog.BUTTON_POSITIVE)
        ?.setTextColor(ContextCompat.getColor(context, R.color.error_text))
}
