package com.economic.dashboard.ui

import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.addTextChangedListener

import com.economic.dashboard.R
import com.economic.dashboard.alerts.AlertRule
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.IllegalFormatException
import java.util.Locale

/** Shows metric-detail layouts as modal bottom sheets (replaces center AlertDialogs). */
object MetricBottomSheet {

    /**
     * What the add-alert dialog needs to describe the metric it is arming
     * (P9 TICKET-47/48). Supplied by [MetricPopupController] from the popup's
     * own spec and history, so the caption can never name a value, unit or
     * quantity other than the one the popup plots — which is also the one
     * `AlertValues` evaluates. Null for callers that have no popup spec.
     */
    class AlertContext(
        /** The popup's `MetricPopupSpec.valueFmt`, e.g. "%.2f%%". */
        @JvmField val valueFmt: String?,
        /** Last point of the popup's own history lambda. */
        @JvmField val latestValue: Double?,
        /** Set only where the graded quantity differs from the headline print. */
        @JvmField val quantityNote: String?,
        /** "daily" / "weekly" / "monthly" / "quarterly", inferred from the series. */
        @JvmField val cadence: String?
    )

    /**
     * Inflates layoutRes inside the sheet chrome (rounded navy top + drag handle),
     * wires the optional btnClose, and shows the sheet.
     *
     * TICKET-24: when `seriesKey`/`seriesLabel` are given, appends an
     * "＋ Add alert" affordance that opens the threshold-rule dialog for that
     * metric. Both are nullable — pass null/null for a plain detail sheet.
     *
     * @return the inflated content view so callers can bind data to it.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        ctx: Context, layoutRes: Int, seriesKey: String?, seriesLabel: String?,
        alertContext: AlertContext? = null
    ): View {
        val wrap = LayoutInflater.from(ctx).inflate(R.layout.sheet_metric_wrapper, null)

        // Guarantee the rounded top corners regardless of what child content
        // draws over them (the extra height pushes the bottom of the round-rect
        // below the view's visible bounds, so only the top corners round off).
        val density0 = ctx.resources.displayMetrics.density
        val cornerRadiusPxOutline = 24 * density0
        wrap.clipToOutline = true
        wrap.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width,
                    view.height + cornerRadiusPxOutline.toInt(), cornerRadiusPxOutline)
            }
        }

        val holder: FrameLayout = wrap.findViewById(R.id.sheetContent)
        val sheetScroll: View? = wrap.findViewById(R.id.sheetScroll)
        val content = LayoutInflater.from(ctx).inflate(layoutRes, holder, false)
        holder.addView(content)
        val dialog = BottomSheetDialog(ctx, R.style.Theme_EconomicDashboard_BottomSheet24)
        dialog.setContentView(wrap)
        val btn: View? = content.findViewById(R.id.btnClose)
        btn?.setOnClickListener { dialog.dismiss() }

        // These sheets close via their own DISMISS button, or the system
        // back button/gesture (which takes the user back to whatever screen
        // was presenting the sheet) — but not by swiping down or tapping the
        // scrim. setCancelable(true) must come before setCanceledOnTouchOutside(false):
        // the latter only overrides touch-outside behavior, so this order is
        // what keeps back-to-cancel enabled while touch-outside stays disabled.
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        // Clear the gesture-nav inset so the last row and "+ Add alert" pill
        // aren't flush with (or under) the home indicator. This is the raw
        // systemBars() bottom, not the app's bottom-nav-wrapper height — the
        // sheet sits above the bottom nav, not beside it.
        if (sheetScroll != null) {
            sheetScroll.setPadding(sheetScroll.paddingLeft, sheetScroll.paddingTop,
                sheetScroll.paddingRight,
                sheetScroll.paddingBottom + EdgeToEdgeInsets.getSystemBottomInsetPx())
        }

        // The sheet is sized to its content rather than a fixed ratio, so it
        // always occupies exactly the space its content needs — never dead
        // space below a short list, never a half-height that clips a taller
        // one and forces an extra drag to see the rest.
        val behavior = dialog.behavior
        behavior.isFitToContents = true
        behavior.skipCollapsed = true
        // STATE_HIDDEN must stay reachable: BottomSheetDialog's back-button/
        // predictive-back handling doesn't dismiss directly, it drives the
        // sheet to STATE_HIDDEN first (that's what animates it away) and only
        // then dismisses. With isHideable = false that transition is rejected
        // and back gets stuck mid-gesture. isDraggable = false below already
        // blocks manual finger-drag regardless of this flag, so this doesn't
        // reopen swipe-to-dismiss — it only lets the back gesture complete.
        behavior.isHideable = true

        // Fully disable the drag gesture itself — the sheet doesn't even nudge
        // or spring back on a swipe attempt. DISMISS is the only way to close it.
        behavior.isDraggable = false

        // Expanded state stops short of the status bar (+8dp) instead of going
        // flush to y=0 — a sheet flush to the top reads as a full-screen
        // dialog, not a sheet with the presenting screen still peeking above it.
        val density = ctx.resources.displayMetrics.density
        behavior.setExpandedOffset(
            EdgeToEdgeInsets.getSystemTopInsetPx() + Math.round(8 * density))

        // The presenting screen recedes as the sheet opens further, so it
        // reads as still "there" behind the sheet rather than just gone.
        val presenting: View? = if (ctx is android.app.Activity)
            ctx.findViewById(android.R.id.content) else null
        if (presenting != null) {
            val maxCornerRadiusPx = 12 * density
            val cornerRadiusPx = floatArrayOf(0f)
            presenting.clipToOutline = true
            presenting.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx[0])
                }
            }
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(sheetView: View, slideOffset: Float) {
                    val offset = Math.max(0f, slideOffset)
                    val scale = 1f - (offset * 0.06f)
                    presenting.scaleX = scale
                    presenting.scaleY = scale
                    cornerRadiusPx[0] = offset * maxCornerRadiusPx
                    presenting.invalidateOutline()
                }
                override fun onStateChanged(sheetView: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        presenting.scaleX = 1f
                        presenting.scaleY = 1f
                        cornerRadiusPx[0] = 0f
                        presenting.invalidateOutline()
                    }
                }
            })
            dialog.setOnDismissListener {
                presenting.scaleX = 1f
                presenting.scaleY = 1f
                cornerRadiusPx[0] = 0f
                presenting.invalidateOutline()
            }
        }

        // TICKET-24: metric-aware sheets get an "Add alert" row appended to the
        // content column (sheet content roots are vertical LinearLayouts).
        if (seriesKey != null && seriesLabel != null && content is LinearLayout) {
            val row = LayoutInflater.from(ctx)
                .inflate(R.layout.view_add_alert_row, content, false)
            val tap: View? = row.findViewById(R.id.tvAddAlert)
            (tap ?: row).setOnClickListener {
                showAddAlertDialog(ctx, seriesKey, seriesLabel, alertContext, content)
            }
            content.addView(row)
        }

        dialog.show()

        // Setting state before the sheet has been laid out is unreliable (the
        // behavior can re-resolve to STATE_COLLAPSED on first measure), so the
        // initial state is set after the first layout pass, via post().
        content.post {
            if (dialog.isShowing) behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return content
    }

    /**
     * TICKET-24: dialog to create (or remove) threshold alert rules for a metric.
     *
     * P9 TICKET-46..53 reworked it. Three behaviours are load-bearing and easy
     * to undo by accident:
     *  - The positive button's listener is bound **after** [AlertDialog.show],
     *    not through the builder. A builder-bound positive button always
     *    dismisses, so the old code's early `return` on a bad threshold closed
     *    the dialog and saved nothing — the user was told "Threshold (e.g. 4.5)"
     *    by a toast and left believing an alert existed.
     *  - The metric name is the dialog's **title**, so TalkBack announces it on
     *    open instead of it reading as a stray bold line.
     *  - The caption is built from [AlertContext], which comes from the popup's
     *    own spec/history — the same quantity `AlertValues` evaluates.
     */
    private fun showAddAlertDialog(
        ctx: Context, seriesKey: String, seriesLabel: String,
        alertContext: AlertContext?, sheetContent: View
    ) {
        val form = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_alert, null)
        val rg: RadioGroup = form.findViewById(R.id.rgAlertOp)
        val til: TextInputLayout = form.findViewById(R.id.tilAlertThreshold)
        val etThr: TextInputEditText = form.findViewById(R.id.etAlertThreshold)
        val tvContext: TextView = form.findViewById(R.id.tvAlertContext)
        val tvExistingHeader: TextView = form.findViewById(R.id.tvExistingAlerts)
        val ruleList: LinearLayout = form.findViewById(R.id.alertRuleList)

        val valueFmt = alertContext?.valueFmt
        til.suffixText = unitSuffix(valueFmt)

        val alertDialog = AlertDialog.Builder(ctx)
            .setTitle(seriesLabel)
            .setView(form)
            // Both listeners are null here on purpose: the positive one is bound
            // after show() so it can keep the dialog open (see the KDoc above).
            .setPositiveButton(R.string.alert_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Errors surface inline on the field, except when the field is disabled
        // (the "any change" operator), where the only visible channel left is a
        // snackbar inside the dialog's own window.
        fun showError(message: String) {
            if (til.isEnabled) {
                til.error = message
                etThr.requestFocus()
            } else {
                Snackbar.make(form, message, Snackbar.LENGTH_LONG).show()
            }
        }

        fun renderRules() {
            ruleList.removeAllViews()
            val mine = SettingsManager.getAlertRules(ctx).filter { seriesKey == it.seriesKey }
            val vis = if (mine.isEmpty()) View.GONE else View.VISIBLE
            tvExistingHeader.visibility = vis
            ruleList.visibility = vis
            val inflater = LayoutInflater.from(ctx)
            for (rule in mine) {
                val row = inflater.inflate(R.layout.view_alert_rule_row, ruleList, false)
                val text = rule.describe(valueFmt) ?: ""
                row.findViewById<TextView>(R.id.tvRuleText).text = text
                val del: TextView = row.findViewById(R.id.btnDeleteRule)
                del.contentDescription = ctx.getString(R.string.alert_delete_cd, text)
                markAsButton(del)
                del.setOnClickListener {
                    if (SettingsManager.removeAlertRule(ctx, rule)) {
                        renderRules()
                        Snackbar.make(form, R.string.alert_removed_one, Snackbar.LENGTH_LONG)
                            // The same instance goes back in, so its lastState /
                            // lastValue latch is restored with it — a re-armed
                            // rule would swallow or invent the next crossing.
                            .setAction(R.string.alert_undo) {
                                SettingsManager.addAlertRule(ctx, rule)
                                renderRules()
                            }
                            .show()
                    }
                }
                ruleList.addView(row)
            }
        }

        fun applyOperatorState() {
            val isChange = rg.checkedRadioButtonId == R.id.rbChange
            // Both, deliberately: TextInputLayout is a ViewGroup and View.setEnabled
            // does not cascade, so disabling only the wrapper would leave a
            // greyed-looking box the user can still type into.
            til.isEnabled = !isChange
            etThr.isEnabled = !isChange
            if (isChange) til.error = null
            val caption = if (isChange) changeCaption(ctx, alertContext)
                          else thresholdCaption(ctx, alertContext)
            tvContext.text = caption
            tvContext.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
        }

        rg.setOnCheckedChangeListener { _, _ -> applyOperatorState() }
        applyOperatorState()
        etThr.addTextChangedListener { til.error = null }
        renderRules()

        alertDialog.show()

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val op = when (rg.checkedRadioButtonId) {
                R.id.rbBelow -> AlertRule.OP_BELOW
                R.id.rbChange -> AlertRule.OP_CHANGE
                else -> AlertRule.OP_ABOVE
            }
            var thr = 0.0
            if (AlertRule.OP_CHANGE != op) {
                val raw = etThr.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    showError(ctx.getString(R.string.alert_err_empty))
                    return@setOnClickListener
                }
                val parsed = raw.toDoubleOrNull()
                if (parsed == null) {
                    showError(ctx.getString(R.string.alert_err_nan))
                    return@setOnClickListener
                }
                thr = parsed
            }
            val rule = AlertRule(seriesKey, seriesLabel, op, thr)
            if (!SettingsManager.addAlertRule(ctx, rule)) {
                showError(ctx.getString(R.string.alert_err_duplicate))
                renderRules()
                return@setOnClickListener
            }
            alertDialog.dismiss()
            // Hosted on the sheet, not the (now dismissed) dialog, and worded
            // from the rule actually stored rather than a generic "Alert saved".
            Snackbar.make(sheetContent,
                ctx.getString(R.string.alert_saved_fmt, rule.describe(valueFmt) ?: seriesLabel),
                Snackbar.LENGTH_LONG).show()
        }
    }

    /** "Currently 4.10% · year-over-year change, monthly" — as much as is known. */
    private fun thresholdCaption(ctx: Context, c: AlertContext?): String {
        if (c == null) return ""
        val fmt = c.valueFmt
        val latest = c.latestValue
        val value = if (fmt != null && latest != null) {
            try {
                String.format(Locale.US, fmt, latest)
            } catch (e: IllegalFormatException) {
                null
            }
        } else null
        val note = listOfNotNull(c.quantityNote, c.cadence).joinToString(", ")
        return when {
            value != null && note.isNotEmpty() ->
                ctx.getString(R.string.alert_context_value_note, value, note)
            value != null -> ctx.getString(R.string.alert_context_value, value)
            // No value this pass (the series didn't resolve): say what is watched
            // rather than printing a placeholder the evaluator can't back up.
            note.isNotEmpty() -> note.replaceFirstChar { it.uppercase(Locale.US) }
            else -> ""
        }
    }

    /** What "any change" actually means for this series' release schedule. */
    private fun changeCaption(ctx: Context, c: AlertContext?): String {
        val cadence = c?.cadence
        return if (cadence != null) ctx.getString(R.string.alert_cadence_known, cadence)
               else ctx.getString(R.string.alert_cadence_unknown)
    }

    /** The unit the popup itself displays, taken from its value format. */
    private fun unitSuffix(valueFmt: String?): String? = when {
        valueFmt == null -> null
        valueFmt.contains("%%") -> "%"
        valueFmt.contains("pt") -> "pt"
        else -> null
    }

    /** Announces a tappable TextView as a button (it isn't one, structurally). */
    private fun markAsButton(v: View) {
        ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View, info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }
}
