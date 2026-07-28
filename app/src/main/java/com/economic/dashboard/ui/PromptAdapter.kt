package com.economic.dashboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R

/**
 * RecyclerView adapter for displaying smart prompt suggestions.
 *
 * Each prompt has an emoji indicator and clickable button to use the prompt.
 */
class PromptAdapter(
    private val prompts: MutableList<String>,
    private val listener: OnPromptClickListener
) : RecyclerView.Adapter<PromptAdapter.ViewHolder>() {

    fun interface OnPromptClickListener {
        fun onPromptClick(prompt: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_smart_prompt, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val prompt = prompts[position]

        var emoji = ""
        var text = prompt

        val spaceIndex = prompt.indexOf(" ")
        if (spaceIndex > 0) {
            emoji = prompt.substring(0, spaceIndex)
            text = prompt.substring(spaceIndex + 1)
        }

        holder.tvEmoji.text = emoji
        holder.tvText.text = text

        holder.btnUse.setOnClickListener { listener.onPromptClick(prompt) }
        holder.itemView.setOnClickListener { listener.onPromptClick(prompt) }
    }

    override fun getItemCount(): Int = prompts.size

    fun updatePrompts(newPrompts: List<String>?) {
        prompts.clear()
        if (newPrompts != null) {
            prompts.addAll(newPrompts)
        }
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvEmoji: TextView = itemView.findViewById(R.id.tvPromptEmoji)
        val tvText: TextView = itemView.findViewById(R.id.tvPromptText)
        val btnUse: Button = itemView.findViewById(R.id.btnUsePrompt)
    }
}
