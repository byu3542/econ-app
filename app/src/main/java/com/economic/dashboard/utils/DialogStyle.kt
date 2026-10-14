package com.economic.dashboard.utils

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.economic.dashboard.R

/**
 * Turns a confirmation dialog's positive button into the destructive one.
 *
 * TICKET-54 puts the shared dialog chrome in the theme
 * (`ThemeOverlay.Econ.MaterialAlertDialog`), but destructiveness is per-dialog
 * — a theme can't know that "Clear cache" is destructive and "Save alert" is
 * not. The button styles are resolved at inflation, so the only seam left after
 * [AlertDialog.show] is the colour itself.
 *
 * Pantrii port U11: the theme's positive button is an accent-filled pill, so
 * red text alone would sit on gold. A destructive action drops the fill and
 * reads as red text instead — the accent never marks a destructive choice.
 *
 * Call immediately after `show()`; the button does not exist before that.
 */
fun AlertDialog.markDestructive() {
    val button = getButton(AlertDialog.BUTTON_POSITIVE) ?: return
    button.setTextColor(ContextCompat.getColor(context, R.color.error_text))
    button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
}
