package com.economic.dashboard.ui.assistant

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.analyst.ConversationSummary
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.databinding.FragmentChatHistoryBinding
import com.economic.dashboard.databinding.ItemChatHistoryBinding
import com.economic.dashboard.ui.MainActivity
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.AppExecutors
import com.google.android.material.snackbar.Snackbar

/**
 * Pantrii port U8 (R7, KTD8, AE3): the archived conversations, newest first.
 *
 * Pushed over the assistant the same way the assistant is pushed over a tab
 * (add + hide, back-stack entry), so back returns to the thread and
 * MainActivity keeps its chrome hidden — the assistant is still on the stack.
 * Tap reopens a conversation (the current one is archived first); swipe
 * deletes it after a Snackbar Undo window.
 */
class ChatHistoryFragment : Fragment() {

    private var binding: FragmentChatHistoryBinding? = null
    private val items = ArrayList<ConversationSummary>()
    private val adapter = HistoryAdapter()

    /** Swiped away but not yet deleted: Undo puts it back, anything else
     *  (Snackbar timeout, leaving the screen) commits the delete. */
    private var pendingDelete: ConversationSummary? = null
    private var reopening = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val b = FragmentChatHistoryBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        val padTop = b.root.paddingTop
        val padBottom = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, padTop + bars.top, v.paddingRight, padBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)

        b.btnHistoryBack.setOnClickListener { parentFragmentManager.popBackStack() }
        PressFeedback.attach(b.btnHistoryBack)

        b.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        b.rvHistory.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) swipeDelete(pos)
            }
        }).attachToRecyclerView(b.rvHistory)

        load()
    }

    private fun load() {
        val db = YieldDatabase.getInstance(requireContext().applicationContext)
        AppExecutors.diskIO().execute {
            val summaries = ConversationSummary.group(db.chatMessageDao().getArchived())
            activity?.runOnUiThread {
                if (binding == null) return@runOnUiThread
                items.clear()
                items.addAll(summaries)
                adapter.notifyDataSetChanged()
                updateEmpty()
            }
        }
    }

    private fun updateEmpty() {
        val b = binding ?: return
        b.tvHistoryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun reopen(s: ConversationSummary) {
        if (reopening) return
        reopening = true
        commitPendingDelete()
        (activity as? MainActivity)?.reopenConversation(s) {
            if (isAdded) parentFragmentManager.popBackStack(TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun swipeDelete(pos: Int) {
        commitPendingDelete()
        val s = items.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        updateEmpty()
        pendingDelete = s
        val root = binding?.root ?: return
        Snackbar.make(root, R.string.history_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.history_undo) {
                if (pendingDelete !== s) return@setAction
                pendingDelete = null
                val at = pos.coerceAtMost(items.size)
                items.add(at, s)
                adapter.notifyItemInserted(at)
                updateEmpty()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION && pendingDelete === s) commitPendingDelete()
                }
            })
            .show()
    }

    /** Only rows still archived are deleted (the DAO checks), so a delete
     *  can never reach the active thread. */
    private fun commitPendingDelete() {
        val s = pendingDelete ?: return
        pendingDelete = null
        val ctx = context?.applicationContext ?: activity?.applicationContext ?: return
        AppExecutors.diskIO().execute {
            YieldDatabase.getInstance(ctx).chatMessageDao().deleteArchived(s.rowIds)
        }
    }

    override fun onDestroyView() {
        commitPendingDelete()
        binding = null
        super.onDestroyView()
    }

    private fun metaFor(s: ConversationSummary): String {
        val date = DateUtils.getRelativeTimeSpanString(
            s.lastMillis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
        val count = resources.getQuantityString(R.plurals.history_messages,
            s.messageCount, s.messageCount)
        return "$date · $count"
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        inner class Holder(val b: ItemChatHistoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemChatHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = items[position]
            h.b.tvHistoryTitle.text = s.title
            h.b.tvHistoryMeta.text = metaFor(s)
            h.b.root.contentDescription = s.title + ". " + h.b.tvHistoryMeta.text
            h.b.root.setOnClickListener { reopen(s) }
        }
    }

    companion object {
        const val TAG = "ChatHistoryFragment"

        /** Pushes history over the assistant, like AssistantFragment.show. */
        @JvmStatic
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            val tx = fm.beginTransaction().setReorderingAllowed(true)
            fm.findFragmentByTag(AssistantFragment.TAG)?.let { tx.hide(it) }
            tx.add(R.id.fragmentContainer, ChatHistoryFragment(), TAG)
                .addToBackStack(TAG)
                .commit()
        }
    }
}
