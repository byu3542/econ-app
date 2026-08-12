package com.economic.dashboard.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.core.view.ViewCompat
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

    private var hero: HeroRow? = null
    private var categories: MutableList<CategoryBlock> = ArrayList()
    private var adapter: RowAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // In-progress edits (saved instance state) win over the launch args, so
        // a rotated sheet keeps uncommitted swaps/reorders instead of resetting.
        readState(savedInstanceState ?: arguments)

        // Picker results survive recreation because both sides are re-created by
        // the FragmentManager and this listener is re-registered here — the old
        // retained-lambda wiring silently dropped the selection after rotation.
        childFragmentManager.setFragmentResultListener(
            IndicatorPickerBottomSheet.REQUEST_KEY, this
        ) { _, result ->
            val id = result.getString(IndicatorPickerBottomSheet.KEY_SELECTED_ID) ?: return@setFragmentResultListener
            when (result.getString(IndicatorPickerBottomSheet.KEY_TARGET_KIND)) {
                TARGET_HERO -> hero?.indicatorId = id
                TARGET_SLOT -> {
                    val catKey = result.getString(IndicatorPickerBottomSheet.KEY_TARGET_CAT)
                    val physicalIndex = result.getInt(IndicatorPickerBottomSheet.KEY_TARGET_SLOT, -1)
                    val cat = categories.firstOrNull { it.key == catKey }
                    cat?.slots?.firstOrNull { it.physicalIndex == physicalIndex }?.indicatorId = id
                }
            }
            adapter?.refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        writeState(outState, hero, categories)
    }

    private fun readState(state: Bundle?) {
        state ?: return
        val heroTitle = state.getString(KEY_HERO_TITLE) ?: return
        val heroId = state.getString(KEY_HERO_ID) ?: return
        hero = HeroRow(heroTitle, heroId)

        val keys = state.getStringArrayList(KEY_CAT_KEYS) ?: return
        val titles = state.getStringArrayList(KEY_CAT_TITLES) ?: return
        val out = ArrayList<CategoryBlock>(keys.size)
        for (i in keys.indices) {
            val groups = state.getStringArrayList(KEY_CAT_GROUPS_PREFIX + i) ?: ArrayList()
            val slotIdx = state.getIntArray(KEY_CAT_SLOT_IDX_PREFIX + i) ?: IntArray(0)
            val slotIds = state.getStringArrayList(KEY_CAT_SLOT_IDS_PREFIX + i) ?: ArrayList()
            val slots = ArrayList<SlotRow>(slotIdx.size)
            for (s in slotIdx.indices) slots.add(SlotRow(slotIdx[s], slotIds.getOrElse(s) { "" }))
            out.add(CategoryBlock(keys[i], titles.getOrElse(i) { "" }, groups, slots))
        }
        categories = out
    }

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
        IndicatorPickerBottomSheet.show(
            childFragmentManager, h.indicatorId, null,
            targetKind = TARGET_HERO, targetCatKey = null, targetPhysicalIndex = -1
        )
    }

    private fun openSlotPicker(cat: CategoryBlock, slot: SlotRow) {
        IndicatorPickerBottomSheet.show(
            childFragmentManager, slot.indicatorId, cat.catalogGroups,
            targetKind = TARGET_SLOT, targetCatKey = cat.key, targetPhysicalIndex = slot.physicalIndex
        )
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

        val result = Bundle()
        result.putString(KEY_RESULT_HERO_ID, h.indicatorId)
        result.putStringArrayList(KEY_RESULT_SLOT_IDS, out)
        result.putStringArrayList(KEY_RESULT_CATEGORY_ORDER, order)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "EditCardsBottomSheet"

        /** Fragment-result contract with the caller (DashboardFragment). */
        const val REQUEST_KEY = "edit_cards_result"
        const val KEY_RESULT_HERO_ID = "hero_id"
        /** 6 ids in fixed physical slot order. */
        const val KEY_RESULT_SLOT_IDS = "slot_ids"
        /** Final category-block order (keys), for cluster ordering. */
        const val KEY_RESULT_CATEGORY_ORDER = "category_order"

        /** Picker target routing (also echoed back in the picker's result). */
        const val TARGET_HERO = "hero"
        const val TARGET_SLOT = "slot"

        private const val KEY_HERO_TITLE = "hero_title"
        private const val KEY_HERO_ID = "hero_indicator_id"
        private const val KEY_CAT_KEYS = "cat_keys"
        private const val KEY_CAT_TITLES = "cat_titles"
        private const val KEY_CAT_GROUPS_PREFIX = "cat_groups_"
        private const val KEY_CAT_SLOT_IDX_PREFIX = "cat_slot_idx_"
        private const val KEY_CAT_SLOT_IDS_PREFIX = "cat_slot_ids_"

        @JvmStatic
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            hero: HeroRow,
            categories: List<CategoryBlock>
        ) {
            val sheet = EditCardsBottomSheet()
            val args = Bundle()
            writeState(args, hero, categories.toMutableList())
            sheet.arguments = args
            sheet.show(fm, TAG)
        }

        /** Flattens hero + blocks into [out] — the classes aren't Parcelable, but
         *  every field is a String/Int/list of those, so parallel arrays suffice. */
        private fun writeState(out: Bundle, hero: HeroRow?, categories: List<CategoryBlock>) {
            hero ?: return
            out.putString(KEY_HERO_TITLE, hero.title)
            out.putString(KEY_HERO_ID, hero.indicatorId)
            val keys = ArrayList<String>(categories.size)
            val titles = ArrayList<String>(categories.size)
            for (i in categories.indices) {
                val cat = categories[i]
                keys.add(cat.key)
                titles.add(cat.title)
                out.putStringArrayList(KEY_CAT_GROUPS_PREFIX + i, ArrayList(cat.catalogGroups))
                out.putIntArray(KEY_CAT_SLOT_IDX_PREFIX + i, IntArray(cat.slots.size) { cat.slots[it].physicalIndex })
                out.putStringArrayList(KEY_CAT_SLOT_IDS_PREFIX + i, ArrayList(cat.slots.map { it.indicatorId }))
            }
            out.putStringArrayList(KEY_CAT_KEYS, keys)
            out.putStringArrayList(KEY_CAT_TITLES, titles)
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
    // P7 TICKET-42: this holder's current Move up/down custom-action ids —
    // tracked so a rebind replaces them instead of stacking duplicates.
    var moveUpActionId = View.NO_ID
    var moveDownActionId = View.NO_ID
}

private class SlotVH(view: View) : RecyclerView.ViewHolder(view) {
    val handle: View = view.findViewById(R.id.dragHandleSlot)
    val name: TextView = view.findViewById(R.id.tvEditCardsSlotName)
    val swap: View = view.findViewById(R.id.btnEditCardsSwap)
    var moveUpActionId = View.NO_ID
    var moveDownActionId = View.NO_ID
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
                val label = DashboardIndicatorCatalog.label(h.indicatorId)
                vh.name.text = label
                // P7 TICKET-42: TalkBack names the target, not a bare "Swap".
                vh.swap.contentDescription = vh.swap.context.getString(R.string.a11y_swap, label)
                vh.swap.setOnClickListener { onSwapHero() }
            }
            is EditCardsRow.Header -> {
                val cat = categories[row.categoryIndex]
                val vh = holder as HeaderVH
                vh.title.text = cat.title
                // P7 TICKET-42: label the handle and offer Move up/down custom
                // actions — the TalkBack path into the same moves dragging does.
                vh.handle.contentDescription =
                    vh.handle.context.getString(R.string.a11y_reorder_category, cat.title)
                val ids = replaceMoveActions(
                    vh.handle, cat.title, vh.moveUpActionId, vh.moveDownActionId,
                    canUp = row.categoryIndex > 0,
                    canDown = row.categoryIndex < categories.size - 1
                ) { vh.bindingAdapterPosition }
                vh.moveUpActionId = ids.first
                vh.moveDownActionId = ids.second
                vh.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(vh)
                    false
                }
            }
            is EditCardsRow.Slot -> {
                val cat = categories[row.categoryIndex]
                val slot = cat.slots[row.slotIndex]
                val vh = holder as SlotVH
                val label = DashboardIndicatorCatalog.label(slot.indicatorId)
                vh.name.text = label
                // P7 TICKET-42: name + role on both affordances ("Reorder S&P
                // 500", "Swap S&P 500") plus Move up/down custom actions.
                vh.handle.contentDescription = vh.handle.context.getString(R.string.a11y_reorder, label)
                vh.swap.contentDescription = vh.swap.context.getString(R.string.a11y_swap, label)
                val ids = replaceMoveActions(
                    vh.handle, label, vh.moveUpActionId, vh.moveDownActionId,
                    canUp = row.slotIndex > 0,
                    canDown = row.slotIndex < cat.slots.size - 1
                ) { vh.bindingAdapterPosition }
                vh.moveUpActionId = ids.first
                vh.moveDownActionId = ids.second
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
     * into a neighboring category); header rows swap whole ADJACENT category
     * blocks — [moveCategoryBlock]'s walk math assumes the two blocks touch,
     * and a fast fling can hand this a header 2+ categories away. Returning
     * false leaves the drag a no-op; ItemTouchHelper re-delivers against the
     * intermediate neighbor as the gesture settles.
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
            from is EditCardsRow.Header && to is EditCardsRow.Header &&
                Math.abs(from.categoryIndex - to.categoryIndex) == 1 -> {
                moveCategoryBlock(from.categoryIndex, to.categoryIndex)
                true
            }
            else -> false
        }
    }

    /**
     * Moves a whole category block (header + slots) past its neighbor using
     * per-row [notifyItemMoved] calls only. A [notifyDataSetChanged] here would
     * detach the dragged ViewHolder and cancel the gesture mid-drag — the user
     * had to re-grab the handle for every single position. The dragged holder
     * itself is never rebound; [RowDragCallback.clearView] does the full rebind
     * once the gesture ends.
     */
    private fun moveCategoryBlock(fromCi: Int, toCi: Int) {
        // Flat row start positions before mutation (hero occupies row 0).
        val start = IntArray(categories.size)
        var pos = if (hero != null) 1 else 0
        for (ci in categories.indices) {
            start[ci] = pos
            pos += 1 + categories[ci].slots.size
        }
        val movedSize = 1 + categories[fromCi].slots.size
        val otherSize = 1 + categories[toCi].slots.size

        val block = categories.removeAt(fromCi)
        categories.add(toCi, block)
        rebuildRows()

        if (fromCi < toCi) {
            // Dragged block moves down: walk each neighbor row up in front of it.
            for (i in 0 until otherSize) notifyItemMoved(start[toCi] + i, start[fromCi] + i)
        } else {
            // Dragged block moves up: walk each neighbor row down past it.
            val regionEnd = start[toCi] + movedSize + otherSize - 1
            repeat(otherSize) { notifyItemMoved(start[toCi], regionEnd) }
        }
    }

    /**
     * P7 TICKET-42: swaps in this bind's "Move up"/"Move down" TalkBack custom
     * actions and returns their ids. The ids from the holder's previous bind
     * are removed first — recycled holders would otherwise stack duplicate
     * menu entries until the framework's action-id pool ran dry. An action is
     * only added where the equivalent drag could land, so the first movable
     * row offers no "Move up" and the last no "Move down" (every mutation
     * ends in [refresh], so bind-time bounds stay true).
     */
    private fun replaceMoveActions(
        handle: View, name: String, oldUpId: Int, oldDownId: Int,
        canUp: Boolean, canDown: Boolean, position: () -> Int
    ): Pair<Int, Int> {
        if (oldUpId != View.NO_ID) ViewCompat.removeAccessibilityAction(handle, oldUpId)
        if (oldDownId != View.NO_ID) ViewCompat.removeAccessibilityAction(handle, oldDownId)
        val ctx = handle.context
        val upId = if (!canUp) View.NO_ID else ViewCompat.addAccessibilityAction(
            handle, ctx.getString(R.string.a11y_move_up, name)
        ) { _, _ -> onAccessibilityMove(position(), up = true, handle, name) }
        val downId = if (!canDown) View.NO_ID else ViewCompat.addAccessibilityAction(
            handle, ctx.getString(R.string.a11y_move_down, name)
        ) { _, _ -> onAccessibilityMove(position(), up = false, handle, name) }
        return Pair(upId, downId)
    }

    /**
     * P7 TICKET-42: the non-drag path into reordering. Funnels through the
     * exact mutations the drag path uses — [onDrag] for slots (which enforces
     * the same-category rule) and [moveCategoryBlock] for whole category
     * blocks — so an accessibility move can never produce an order a drag
     * couldn't, and writeState/onSaveInstanceState round-trip identically.
     */
    private fun onAccessibilityMove(position: Int, up: Boolean, handle: View, name: String): Boolean {
        val moved = when (val row = rows.getOrNull(position)) {
            is EditCardsRow.Slot -> onDrag(position, if (up) position - 1 else position + 1)
            is EditCardsRow.Header -> {
                val toCi = row.categoryIndex + if (up) -1 else 1
                if (toCi in categories.indices) { moveCategoryBlock(row.categoryIndex, toCi); true } else false
            }
            else -> false
        }
        // No gesture ends on this path, so clearView never fires — do its full
        // rebind here to keep row indices and action bounds current.
        if (moved) {
            refresh()
            // Review finding: the rebind is silent and accessibility focus stays
            // at the old position (now the swapped-in neighbor), so confirm what
            // moved by name — a repeat activation on the wrong row then at least
            // announces the neighbor's name, not a phantom success.
            handle.announceForAccessibility(
                handle.context.getString(
                    if (up) R.string.a11y_moved_up else R.string.a11y_moved_down, name
                )
            )
        }
        return moved
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

    /** Gesture over — now the full rebind is safe (it would cancel a live drag). */
    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.refresh()
    }

    /** Same-type only, so a drag never straddles a header/slot boundary mid-gesture. */
    override fun canDropOver(
        recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ): Boolean = current.itemViewType == target.itemViewType
}
