package com.economic.dashboard.ui.shortcuts

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.utils.HapticUtil

/**
 * Settings parity U7 (R11, KTD6): Settings › Shortcuts. One list holding
 * "Your shortcuts" (drag handles, remove buttons) and "More shortcuts" (add
 * buttons that disable at six). Every change edits [editor]'s working copy;
 * the page's Save button writes it. Reordering mirrors EditCardsBottomSheet:
 * drag from the handle, or TalkBack's Move up / Move down, which call the
 * same [ShortcutEditor.move] and announce the new position.
 */
class ShortcutsEditorAdapter(
    private val editor: ShortcutEditor,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val title: Int, val showCount: Boolean) : Row()
        data class Mine(val shortcut: Shortcut, val index: Int) : Row()
        data class More(val shortcut: Shortcut) : Row()
    }

    private var rows: List<Row> = build()
    var touchHelper: ItemTouchHelper? = null

    private fun build(): List<Row> {
        val out = ArrayList<Row>()
        out.add(Row.Header(R.string.shortcuts_editor_yours, showCount = true))
        editor.ids.forEachIndexed { i, id -> ShortcutCatalog.find(id)?.let { out.add(Row.Mine(it, i)) } }
        val more = editor.more()
        if (more.isNotEmpty()) {
            out.add(Row.Header(R.string.shortcuts_editor_more, showCount = false))
            more.forEach { id -> ShortcutCatalog.find(id)?.let { out.add(Row.More(it)) } }
        }
        return out
    }

    /** Sections and counts all shift together, so the whole list rebinds. */
    fun refresh() {
        rows = build()
        notifyDataSetChanged()
        onChanged()
    }

    /** True for rows in "Your shortcuts", the only ones a drag may land on. */
    fun isMine(position: Int): Boolean = rows.getOrNull(position) is Row.Mine

    /** Drag step: swap within "Your shortcuts" through the shared mutation. */
    fun onDrag(from: Int, to: Int): Boolean {
        val a = rows.getOrNull(from) as? Row.Mine ?: return false
        val b = rows.getOrNull(to) as? Row.Mine ?: return false
        if (!editor.move(a.index, b.index)) return false
        val list = rows.toMutableList()
        list[from] = b.copy(index = a.index)
        list[to] = a.copy(index = b.index)
        rows = list
        notifyItemMoved(from, to)
        return true
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Mine -> TYPE_MINE
        is Row.More -> TYPE_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(inflater.inflate(R.layout.item_shortcut_edit_header, parent, false))
        else RowVH(inflater.inflate(R.layout.item_shortcut_edit_row, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = rows[position]) {
            is Row.Header -> {
                val h = holder as HeaderVH
                h.title.setText(row.title)
                h.count.visibility = if (row.showCount) View.VISIBLE else View.GONE
                h.count.text = ctx.getString(R.string.shortcuts_editor_count, editor.ids.size, ShortcutCatalog.MAX)
            }
            is Row.Mine -> bindMine(holder as RowVH, row)
            is Row.More -> bindMore(holder as RowVH, row)
        }
    }

    private fun bindMine(h: RowVH, row: Row.Mine) {
        val ctx = h.itemView.context
        bindTile(h, row.shortcut)
        h.itemView.alpha = 1f
        h.handle.visibility = View.VISIBLE
        // Drag starts on touch-down; TalkBack reorders through the move actions.
        h.handle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(h)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            false
        }
        h.action.setImageResource(R.drawable.ic_remove)
        h.action.isEnabled = true
        h.action.contentDescription = ctx.getString(R.string.shortcuts_editor_remove_cd, ctx.getString(row.shortcut.label))
        h.action.setOnClickListener {
            HapticUtil.selection(it)
            if (editor.remove(row.shortcut.id)) refresh()
        }
        replaceMoveActions(h, row)
    }

    private fun bindMore(h: RowVH, row: Row.More) {
        val ctx = h.itemView.context
        bindTile(h, row.shortcut)
        clearMoveActions(h)
        h.handle.visibility = View.INVISIBLE
        h.handle.setOnTouchListener(null)
        val canAdd = !editor.isFull
        // Disabled at six, as Pantrii dims the whole "More" list.
        h.itemView.alpha = if (canAdd) 1f else 0.45f
        h.action.setImageResource(R.drawable.ic_add)
        h.action.isEnabled = canAdd
        h.action.contentDescription = ctx.getString(R.string.shortcuts_editor_add_cd, ctx.getString(row.shortcut.label))
        h.action.setOnClickListener {
            HapticUtil.selection(it)
            if (editor.add(row.shortcut.id)) refresh()
        }
    }

    private fun bindTile(h: RowVH, s: Shortcut) {
        val ctx = h.itemView.context
        h.icon.setImageResource(s.icon)
        h.icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, s.tint.fill))
        h.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, s.tint.icon))
        h.label.setText(s.label)
    }

    /** Old ids come off first so recycled rows never stack duplicate actions. */
    private fun clearMoveActions(h: RowVH) {
        if (h.upId != View.NO_ID) ViewCompat.removeAccessibilityAction(h.itemView, h.upId)
        if (h.downId != View.NO_ID) ViewCompat.removeAccessibilityAction(h.itemView, h.downId)
        h.upId = View.NO_ID
        h.downId = View.NO_ID
    }

    private fun replaceMoveActions(h: RowVH, row: Row.Mine) {
        clearMoveActions(h)
        val ctx = h.itemView.context
        val name = ctx.getString(row.shortcut.label)
        val last = editor.ids.lastIndex
        if (row.index > 0) h.upId = ViewCompat.addAccessibilityAction(
            h.itemView, ctx.getString(R.string.a11y_move_up, name)
        ) { v, _ -> accessibilityMove(v, row.index, row.index - 1) }
        if (row.index < last) h.downId = ViewCompat.addAccessibilityAction(
            h.itemView, ctx.getString(R.string.a11y_move_down, name)
        ) { v, _ -> accessibilityMove(v, row.index, row.index + 1) }
    }

    /** TalkBack's path: the drag's mutation, then the new position spoken. */
    private fun accessibilityMove(v: View, from: Int, to: Int): Boolean {
        if (!editor.move(from, to)) return false
        refresh()
        v.announceForAccessibility(
            v.context.getString(R.string.shortcuts_editor_moved, to + 1, editor.ids.size))
        return true
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvEditHeader)
        val count: TextView = v.findViewById(R.id.tvEditCount)
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val handle: ImageView = v.findViewById(R.id.ivEditHandle)
        val icon: ImageView = v.findViewById(R.id.ivEditIcon)
        val label: TextView = v.findViewById(R.id.tvEditLabel)
        val action: ImageView = v.findViewById(R.id.btnEditAction)
        var upId = View.NO_ID
        var downId = View.NO_ID
    }

    /** Handle-only drag inside "Your shortcuts". */
    class DragCallback(private val adapter: ShortcutsEditorAdapter) :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
            adapter.onDrag(vh.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { /* no swipe */ }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
            adapter.isMine(target.bindingAdapterPosition)

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (adapter.isMine(vh.bindingAdapterPosition)) super.getMovementFlags(rv, vh) else 0

        /** Gesture over: rebind so indices and move actions match the new order. */
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            adapter.refresh()
        }
    }

    /**
     * Draws each section's rows as one card (surface fill, line stroke,
     * radius_card), so the list reads like the Settings groups without
     * nesting a RecyclerView per section.
     */
    class CardDecoration(rv: RecyclerView) : RecyclerView.ItemDecoration() {
        private val radius = rv.resources.getDimension(R.dimen.radius_card)
        private val stroke = rv.resources.getDimension(R.dimen.stroke_card)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(rv.context, R.color.surface)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = ContextCompat.getColor(rv.context, R.color.line)
        }
        private val rect = RectF()

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            var top = Float.NaN
            var bottom = 0f
            fun flush() {
                if (top.isNaN()) return
                rect.set(parent.paddingLeft + stroke / 2, top, parent.width - parent.paddingRight - stroke / 2, bottom)
                c.drawRoundRect(rect, radius, radius, fill)
                c.drawRoundRect(rect, radius, radius, line)
                top = Float.NaN
            }
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (parent.getChildViewHolder(child) is RowVH) {
                    if (top.isNaN()) top = child.top.toFloat()
                    bottom = child.bottom.toFloat()
                } else flush()
            }
            flush()
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MINE = 1
        private const val TYPE_MORE = 2
    }
}
