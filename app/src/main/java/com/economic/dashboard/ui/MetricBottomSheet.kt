package com.economic.dashboard.ui

import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

import com.economic.dashboard.R
import com.economic.dashboard.alerts.AlertRule
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Shows metric-detail layouts as modal bottom sheets (replaces center AlertDialogs). */
object MetricBottomSheet {

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
    fun show(ctx: Context, layoutRes: Int, seriesKey: String?, seriesLabel: String?): View {
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
                showAddAlertDialog(ctx, seriesKey, seriesLabel)
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

    /** TICKET-24: dialog to create (or clear) threshold alert rules for a metric. */
    private fun showAddAlertDialog(ctx: Context, seriesKey: String, seriesLabel: String) {
        val form = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_alert, null)
        val tvName: TextView? = form.findViewById(R.id.tvAlertMetricName)
        val rg: RadioGroup? = form.findViewById(R.id.rgAlertOp)
        val etThr: EditText? = form.findViewById(R.id.etAlertThreshold)
        val tvEx: TextView? = form.findViewById(R.id.tvExistingAlerts)
        val btnRm: TextView? = form.findViewById(R.id.btnRemoveAlerts)

        if (tvName != null) tvName.text = seriesLabel

        // Show existing rules for this metric + a remove affordance.
        val existing = SettingsManager.getAlertRules(ctx)
        val sb = StringBuilder()
        for (r in existing) {
            if (seriesKey == r.seriesKey) {
                if (sb.length > 0) sb.append('\n')
                sb.append("• ").append(r.describe())
            }
        }
        if (sb.length > 0) {
            if (tvEx != null) { tvEx.text = sb.toString(); tvEx.visibility = View.VISIBLE }
            if (btnRm != null) btnRm.visibility = View.VISIBLE
        }

        val alertDialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var op = AlertRule.OP_ABOVE
                if (rg != null) {
                    val checked = rg.checkedRadioButtonId
                    if (checked == R.id.rbBelow) op = AlertRule.OP_BELOW
                    if (checked == R.id.rbChange) op = AlertRule.OP_CHANGE
                }
                var thr = 0.0
                if (AlertRule.OP_CHANGE != op) {
                    try {
                        thr = java.lang.Double.parseDouble(
                            if (etThr != null) etThr.text.toString().trim() else "")
                    } catch (e: NumberFormatException) {
                        Toast.makeText(ctx,
                            R.string.alert_threshold_hint,
                            Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                SettingsManager.addAlertRule(ctx,
                    AlertRule(seriesKey, seriesLabel, op, thr))
                Toast.makeText(ctx, R.string.alert_saved,
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        if (btnRm != null) {
            btnRm.setOnClickListener {
                SettingsManager.removeAlertRulesFor(ctx, seriesKey)
                Toast.makeText(ctx, R.string.alerts_removed,
                    Toast.LENGTH_SHORT).show()
                alertDialog.dismiss()
            }
        }

        alertDialog.show()
    }
}
