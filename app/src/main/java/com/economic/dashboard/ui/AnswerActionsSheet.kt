package com.economic.dashboard.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.economic.dashboard.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Pantrii port U6 (KTD6): the long-press sheet on a thread message. It holds
 * what the always-visible row under each answer used to (copy, regenerate,
 * continue, variant prev/next, the model label) plus the old long-press menu's
 * share, and the time the row no longer prints. ChatAdapter decides which
 * actions apply; this only lays them out.
 */
object AnswerActionsSheet {

    class Action(
        @DrawableRes val icon: Int,
        val label: String,
        val meta: String? = null,
        val run: () -> Unit,
    )

    fun show(ctx: Context, quote: CharSequence, actions: List<Action>) {
        val dialog = BottomSheetDialog(ctx, R.style.Theme_Econ_AnswerSheet)
        val inflater = LayoutInflater.from(dialog.context)
        val content = inflater.inflate(R.layout.sheet_answer_actions, null)
        content.findViewById<TextView>(R.id.tvAnswerSheetQuote).text = quote

        val list: LinearLayout = content.findViewById(R.id.llAnswerSheetActions)
        list.showDividers = LinearLayout.SHOW_DIVIDER_BEGINNING or LinearLayout.SHOW_DIVIDER_MIDDLE
        list.dividerDrawable = GradientDrawable().apply {
            setColor(ContextCompat.getColor(ctx, R.color.line))
            setSize(1, 1)
        }
        for (a in actions) {
            val row = inflater.inflate(R.layout.item_answer_action, list, false)
            row.findViewById<ImageView>(R.id.ivAnswerAction).setImageResource(a.icon)
            row.findViewById<TextView>(R.id.tvAnswerAction).text = a.label
            val meta: TextView = row.findViewById(R.id.tvAnswerActionMeta)
            meta.text = a.meta
            meta.visibility = if (a.meta.isNullOrEmpty()) View.GONE else View.VISIBLE
            row.contentDescription = if (a.meta.isNullOrEmpty()) a.label else "${a.label}, ${a.meta}"
            row.setOnClickListener {
                dialog.dismiss()
                a.run()
            }
            list.addView(row)
        }
        dialog.setContentView(content)
        dialog.show()
    }

    /** "“first words of the message…” · 2:14 PM · Haiku 4.5". */
    fun quoteLine(text: CharSequence, time: String?, model: String?): String {
        val flat = text.toString().replace(Regex("\\s+"), " ").trim()
        val snippet = if (flat.length > QUOTE_CHARS) flat.take(QUOTE_CHARS).trimEnd() + "…" else flat
        val parts = ArrayList<String>(3)
        parts.add("“$snippet”")
        if (!time.isNullOrEmpty()) parts.add(time)
        if (!model.isNullOrEmpty()) parts.add(model)
        return parts.joinToString(" · ")
    }

    private const val QUOTE_CHARS = 60
}
