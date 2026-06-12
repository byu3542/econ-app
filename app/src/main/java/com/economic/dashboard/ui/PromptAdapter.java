package com.economic.dashboard.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;

import java.util.List;

/**
 * RecyclerView adapter for displaying smart prompt suggestions.
 *
 * Each prompt has an emoji indicator and clickable button to use the prompt.
 */
public class PromptAdapter extends RecyclerView.Adapter<PromptAdapter.ViewHolder> {

    private final List<String> prompts;
    private final OnPromptClickListener listener;

    public interface OnPromptClickListener {
        void onPromptClick(String prompt);
    }

    public PromptAdapter(List<String> prompts, OnPromptClickListener listener) {
        this.prompts = prompts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_smart_prompt, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String prompt = prompts.get(position);

        String emoji = "";
        String text = prompt;

        int spaceIndex = prompt.indexOf(" ");
        if (spaceIndex > 0) {
            emoji = prompt.substring(0, spaceIndex);
            text = prompt.substring(spaceIndex + 1);
        }

        holder.tvEmoji.setText(emoji);
        holder.tvText.setText(text);

        holder.btnUse.setOnClickListener(v -> listener.onPromptClick(prompt));
        holder.itemView.setOnClickListener(v -> listener.onPromptClick(prompt));
    }

    @Override
    public int getItemCount() {
        return prompts.size();
    }

    public void updatePrompts(List<String> newPrompts) {
        this.prompts.clear();
        if (newPrompts != null) {
            this.prompts.addAll(newPrompts);
        }
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvEmoji;
        TextView tvText;
        Button btnUse;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvEmoji = itemView.findViewById(R.id.tvPromptEmoji);
            tvText = itemView.findViewById(R.id.tvPromptText);
            btnUse = itemView.findViewById(R.id.btnUsePrompt);
        }
    }
}
