package com.economic.dashboard.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.economic.dashboard.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Design branch 2a: "Choose an indicator" sheet shown when the user taps the
 * Overview hero card or any of the 6 KPI slots. Lists every
 * [DashboardIndicatorCatalog] entry grouped by category with the currently
 * assigned one checked; tapping a row picks it and dismisses.
 *
 * Direction 1d adds an optional [groupFilter]: when set, only catalog groups
 * in that list are shown, so a slot can only be swapped for a metric tagged
 * to its own Edit Cards category (a slot's card label never goes stale).
 * Hero editing passes no filter — it can take any indicator, as before.
 */
class IndicatorPickerBottomSheet : BottomSheetDialogFragment() {

    private var currentId: String? = null
    private var groupFilter: List<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Everything this sheet needs lives in arguments, so the restored
        // instance filters and delivers exactly like the original — the old
        // retained-field wiring left a recreated picker unfiltered and inert.
        currentId = arguments?.getString(KEY_CURRENT_ID)
        groupFilter = arguments?.getStringArrayList(KEY_GROUP_FILTER)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_indicator_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnPickerClose)?.setOnClickListener { dismissAllowingStateLoss() }

        val container: LinearLayout = view.findViewById(R.id.llPickerContainer) ?: return
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        for (group in DashboardIndicatorCatalog.GROUP_ORDER) {
            val filter = groupFilter
            if (filter != null && !filter.contains(group)) continue
            val header = TextView(ctx)
            header.text = group
            header.setTextAppearance(android.R.style.TextAppearance_Small)
            header.setTextColor(resolveColor(ctx, R.color.gold_muted))
            header.textSize = 11f
            header.isAllCaps = true
            header.letterSpacing = 0.1f
            header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
            val hp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hp.topMargin = dp(ctx, 14)
            hp.bottomMargin = dp(ctx, 6)
            hp.leftMargin = dp(ctx, 8)
            header.layoutParams = hp
            container.addView(header)

            for (ind in DashboardIndicatorCatalog.ALL) {
                if (ind.group != group) continue
                val row = inflater.inflate(R.layout.item_indicator_picker_row, container, false)
                val tvLabel: TextView? = row.findViewById(R.id.tvIndicatorRowLabel)
                val tvCheck: TextView? = row.findViewById(R.id.tvIndicatorRowCheck)
                tvLabel?.text = ind.label
                val selected = ind.id == currentId
                tvCheck?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                row.setBackgroundColor(
                    if (selected) resolveColor(ctx, R.color.bg_selected_row) else android.graphics.Color.TRANSPARENT
                )
                row.setOnClickListener {
                    val result = Bundle()
                    result.putString(KEY_SELECTED_ID, ind.id)
                    // Echo the target back so the caller knows which slot this
                    // selection is for even after both sheets were recreated.
                    result.putString(KEY_TARGET_KIND, arguments?.getString(KEY_TARGET_KIND))
                    result.putString(KEY_TARGET_CAT, arguments?.getString(KEY_TARGET_CAT))
                    result.putInt(KEY_TARGET_SLOT, arguments?.getInt(KEY_TARGET_SLOT, -1) ?: -1)
                    parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
                    dismissAllowingStateLoss()
                }
                container.addView(row)
            }
        }
    }

    private fun resolveColor(ctx: Context, resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(ctx, resId)

    private fun dp(ctx: Context, v: Int): Int =
        Math.round(v * ctx.resources.displayMetrics.density)

    companion object {
        const val TAG = "IndicatorPickerBottomSheet"

        /** Fragment-result contract with the caller (EditCardsBottomSheet). */
        const val REQUEST_KEY = "indicator_picker_result"
        const val KEY_SELECTED_ID = "selected_id"
        const val KEY_TARGET_KIND = "target_kind"
        const val KEY_TARGET_CAT = "target_cat"
        const val KEY_TARGET_SLOT = "target_slot"

        private const val KEY_CURRENT_ID = "current_id"
        private const val KEY_GROUP_FILTER = "group_filter"

        @JvmStatic
        fun show(
            fm: androidx.fragment.app.FragmentManager, currentId: String?,
            groupFilter: List<String>? = null,
            targetKind: String, targetCatKey: String?, targetPhysicalIndex: Int
        ) {
            val sheet = IndicatorPickerBottomSheet()
            val args = Bundle()
            args.putString(KEY_CURRENT_ID, currentId)
            if (groupFilter != null) args.putStringArrayList(KEY_GROUP_FILTER, ArrayList(groupFilter))
            args.putString(KEY_TARGET_KIND, targetKind)
            args.putString(KEY_TARGET_CAT, targetCatKey)
            args.putInt(KEY_TARGET_SLOT, targetPhysicalIndex)
            sheet.arguments = args
            sheet.show(fm, TAG)
        }
    }
}
