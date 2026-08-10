package com.economic.dashboard.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.economic.dashboard.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Design branch 2a, direction 1d: "drag to reorder, tap to swap" — the single
 * "Edit cards" entry point for the Overview. A full-height editor listing the
 * hero card (fixed) and the Overview's category blocks; category blocks and
 * the slots within them are drag-reorderable, and each slot's Swap chip opens
 * [IndicatorPickerBottomSheet] scoped to that slot's own category so a card
 * can never take on a metric from another category and go stale-labeled.
 *
 * All edits happen on a local copy ([hero]/[categories]) — nothing is
 * persisted until Done, so Cancel (or swiping the sheet away) discards them.
 */
class EditCardsBottomSheet : BottomSheetDialogFragment() {

    class HeroRow(@JvmField val title: String, @JvmField var indicatorId: String)

    class SlotRow(@JvmField val physicalIndex: Int, @JvmField var indicatorId: String)

    class CategoryBlock(
        @JvmField val key: String,
        @JvmField val title: String,
        @JvmField val catalogGroups: List<String>,
        @JvmField val slots: MutableList<SlotRow>
    )

    fun interface Listener {
        /** [slotIds] is 6 ids in fixed physical slot order; [categoryOrder]
         *  is the final category-block order (keys), for cluster ordering. */
        fun onCardsSaved(heroId: String, slotIds: List<String>, categoryOrder: List<String>)
    }

    private var hero: HeroRow? = null
    private var categories: MutableList<CategoryBlock> = ArrayList()
    private var listener: Listener? = null
    private var adapter: RowAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24)
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // Full-screen editor — open expanded and skip the collapsed stop so a
        // single swipe down dismisses instead of snapping halfway.
        val behavior = dialog.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_edit_cards, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnEditCardsCancel)?.setOnClickListener { dismissAllowingStateLoss() }
        view.findViewById<View>(R.id.btnEditCardsDone)?.setOnClickListener { commitAndDismiss() }

        val rv: RecyclerView = view.findViewById(R.id.rvEditCards) ?: return
        rv.layoutManager = LinearLayoutManager(context)

        val a = RowAdapter(
            hero, categories,
            onSwapHero = { openHeroPicker() },
            onSwapSlot = { cat, slot -> openSlotPicker(cat, slot) }
        )
        adapter = a
        rv.adapter = a

        val touchHelper = ItemTouchHelper(RowDragCallback(a))
        a.touchHelper = touchHelper
        touchHelper.attachToRecyclerView(rv)
    }

    private fun openHeroPicker() {
        val h = hero ?: return
        IndicatorPickerBottomSheet.show(childFragmentManager, h.indicatorId, null) { id ->
            h.indicatorId = id
            adapter?.refresh()
        }
    }

    private fun openSlotPicker(cat: CategoryBlock, slot: SlotRow) {
        IndicatorPickerBottomSheet.show(childFragmentManager, slot.indicatorId, cat.catalogGroups) { id ->
            slot.indicatorId = id
            adapter?.refresh()
        }
    }

    private fun commitAndDismiss() {
        val h = hero
        val cats = adapter?.categories ?: categories
        if (h == null) { dismissAllowingStateLoss(); return }

        val slotIds = arrayOfNulls<String>(6)
        val order = ArrayList<String>()
        for (cat in cats) {
            order.add(cat.key)
            for (slot in cat.slots) {
                if (slot.physicalIndex in 0..5) slotIds[slot.physicalIndex] = slot.indicatorId
            }
        }
        val out = ArrayList<String>(6)
        for (id in slotIds) out.add(id ?: "")

        listener?.onCardsSaved(h.indicatorId, out, order)
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "EditCardsBottomSheet"

        @JvmStatic
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            hero: HeroRow,
            categories: List<CategoryBlock>,
            listener: Listener
        ) {
            val sheet = EditCardsBottomSheet()
            sheet.hero = hero
            sheet.categories = categories.toMutableList()
            sheet.listener = listener
            sheet.show(fm, TAG)
        }
    }
}

// ── RecyclerView adapter + drag callback ────────────────────────────────────

/** One displayed row: the fixed hero, a category header, or a slot within it. */
private sealed class EditCardsRow {
    object Hero : EditCardsRow()
    data class Header(val categoryIndex: Int) : EditCardsRow()
    data class Slot(val categoryIndex: Int, val slotIndex: Int) : EditCardsRow()
}

private const val VIEW_TYPE_HERO = 0
private const val VIEW_TYPE_HEADER = 1
private const val VIEW_TYPE_SLOT = 2

private class HeroVH(view: View) : RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.tvEditCardsHeroName)
    val swap: View = view.findViewById(R.id.btnEditCardsHeroSwap)
}

private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
    val handle: View = view.findViewById(R.id.dragHandleHeader)
    val title: TextView = view.findViewById(R.id.tvEditCardsHeaderTitle)
}

private class SlotVH(view: View) : RecyclerView.ViewHolder(view) {
    val handle: View = view.findViewById(R.id.dragHandleSlot)
    val name: TextView = view.findViewById(R.id.tvEditCardsSlotName)
    val swap: View = view.findViewById(R.id.btnEditCardsSwap)
}

/**
 * Flattens hero + category blocks into a row list for display, and mutates
 * the underlying [categories] structure in place as the user drags.
 */
private class RowAdapter(
    private val hero: EditCardsBottomSheet.HeroRow?,
    val categories: MutableList<EditCardsBottomSheet.CategoryBlock>,
    private val onSwapHero: () -> Unit,
    private val onSwapSlot: (EditCardsBottomSheet.CategoryBlock, EditCardsBottomSheet.SlotRow) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var touchHelper: ItemTouchHelper? = null
    private var rows: List<EditCardsRow> = emptyList()

    init { rebuildRows() }

    private fun rebuildRows() {
        val out = ArrayList<EditCardsRow>()
        if (hero != null) out.add(EditCardsRow.Hero)
        for (ci in categories.indices) {
            out.add(EditCardsRow.Header(ci))
            for (si in categories[ci].slots.indices) out.add(EditCardsRow.Slot(ci, si))
        }
        rows = out
    }

    /** Rebuild + redraw after a swap picks a new indicator for one row. */
    fun refresh() {
        rebuildRows()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is EditCardsRow.Hero -> VIEW_TYPE_HERO
        is EditCardsRow.Header -> VIEW_TYPE_HEADER
        is EditCardsRow.Slot -> VIEW_TYPE_SLOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HERO -> HeroVH(inflater.inflate(R.layout.item_edit_cards_hero, parent, false))
            VIEW_TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_edit_cards_header, parent, false))
            else -> SlotVH(inflater.inflate(R.layout.item_edit_cards_slot, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is EditCardsRow.Hero -> {
                val h = hero ?: return
                val vh = holder as HeroVH
                vh.name.text = DashboardIndicatorCatalog.label(h.indicatorId)
                vh.swap.setOnClickListener { onSwapHero() }
            }
            is EditCardsRow.Header -> {
                val cat = categories[row.categoryIndex]
                val vh = holder as HeaderVH
                vh.title.text = cat.title
                vh.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(vh)
                    false
                }
            }
            is EditCardsRow.Slot -> {
                val cat = categories[row.categoryIndex]
                val slot = cat.slots[row.slotIndex]
                val vh = holder as SlotVH
                vh.name.text = DashboardIndicatorCatalog.label(slot.indicatorId)
                vh.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(vh)
                    false
                }
                vh.swap.setOnClickListener { onSwapSlot(cat, slot) }
            }
        }
    }

    /**
     * Called by [RowDragCallback.onMove]. Slot rows may only swap with another
     * slot in the SAME category (a category's slot count/order never leaks
     * into a neighboring category); header rows swap whole category blocks.
     * Returns false — leaving the drag a no-op — for any other combination.
     */
    fun onDrag(fromPos: Int, toPos: Int): Boolean {
        val from = rows.getOrNull(fromPos) ?: return false
        val to = rows.getOrNull(toPos) ?: return false
        return when {
            from is EditCardsRow.Slot && to is EditCardsRow.Slot && from.categoryIndex == to.categoryIndex -> {
                java.util.Collections.swap(categories[from.categoryIndex].slots, from.slotIndex, to.slotIndex)
                rebuildRows()
                notifyItemMoved(fromPos, toPos)
                true
            }
            from is EditCardsRow.Header && to is EditCardsRow.Header && from.categoryIndex != to.categoryIndex -> {
                val block = categories.removeAt(from.categoryIndex)
                categories.add(to.categoryIndex, block)
                rebuildRows()
                notifyDataSetChanged()
                true
            }
            else -> false
        }
    }
}

/** Drag-handle-triggered reordering (no long-press) — see [RowAdapter.onDrag]. */
private class RowDragCallback(private val adapter: RowAdapter) :
    ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

    override fun onMove(
        recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ): Boolean = adapter.onDrag(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { /* no swipe action */ }

    override fun isLongPressDragEnabled(): Boolean = false

    /** Same-type only, so a drag never straddles a header/slot boundary mid-gesture. */
    override fun canDropOver(
        recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ): Boolean = current.itemViewType == target.itemViewType
}
