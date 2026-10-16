package com.economic.dashboard.ui.shortcuts

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.utils.HapticUtil
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Settings parity U6 (R10, KTD7): what the center button opens. The user's
 * shortcuts (up to six) in a three-column grid, a gear to the editor, and the
 * full-width Analyst button. The sheet only dismisses and hands the choice to
 * its [Host]; MainActivity performs every action.
 */
class ShortcutsBottomSheet : BottomSheetDialogFragment() {

    interface Host {
        fun runShortcut(action: ShortcutAction)
        fun openAnalystFromShortcuts()
        fun openShortcutsEditor()
    }

    private fun host(): Host? = activity as? Host

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, R.style.Theme_Econ_Sheet)
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // Short sheet: open fully so the Analyst button is never below the fold.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.sheet_shortcuts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = view.context
        val shortcuts = SettingsManager.getShortcuts(ctx).mapNotNull { ShortcutCatalog.find(it) }

        val rv: RecyclerView = view.findViewById(R.id.rvShortcuts)
        rv.layoutManager = GridLayoutManager(ctx, COLUMNS)
        rv.adapter = TileAdapter(shortcuts) { s ->
            HapticUtil.selection(view)
            dismissAllowingStateLoss()
            host()?.runShortcut(s.action)
        }
        rv.visibility = if (shortcuts.isEmpty()) View.GONE else View.VISIBLE

        view.findViewById<View>(R.id.btnShortcutsEdit).setOnClickListener {
            dismissAllowingStateLoss()
            host()?.openShortcutsEditor()
        }
        val analyst = view.findViewById<View>(R.id.btnShortcutsAnalyst)
        PressFeedback.attach(analyst)
        analyst.setOnClickListener {
            dismissAllowingStateLoss()
            host()?.openAnalystFromShortcuts()
        }
    }

    private class TileAdapter(
        private val items: List<Shortcut>,
        private val onTap: (Shortcut) -> Unit,
    ) : RecyclerView.Adapter<TileAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivShortcutIcon)
            val label: TextView = v.findViewById(R.id.tvShortcutLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_tile, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            val ctx = h.itemView.context
            h.icon.setImageResource(s.icon)
            h.icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, s.tint.fill))
            h.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, s.tint.icon))
            h.label.setText(s.label)
            h.itemView.setOnClickListener { onTap(s) }
        }
    }

    companion object {
        const val TAG = "ShortcutsBottomSheet"
        private const val COLUMNS = 3

        @JvmStatic
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            ShortcutsBottomSheet().show(fm, TAG)
        }
    }
}
