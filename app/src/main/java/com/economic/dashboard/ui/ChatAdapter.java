package com.economic.dashboard.ui;

import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.models.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage m = messages.get(position);
        holder.tvMessage.setText(m.getText());
        holder.tvTimestamp.setText(m.getTimestamp());

        LinearLayout.LayoutParams bubbleParams = (LinearLayout.LayoutParams) holder.llBubbleContainer.getLayoutParams();
        LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) holder.tvSenderLabel.getLayoutParams();
        LinearLayout.LayoutParams timeParams = (LinearLayout.LayoutParams) holder.tvTimestamp.getLayoutParams();

        if (m.isUser()) {
            holder.tvSenderLabel.setText("YOU");
            bubbleParams.gravity = Gravity.END;
            labelParams.gravity = Gravity.END;
            timeParams.gravity = Gravity.END;
            
            holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_user);
            holder.tvMessage.setTextColor(Color.WHITE);
        } else {
            holder.tvSenderLabel.setText("ANALYST");
            bubbleParams.gravity = Gravity.START;
            labelParams.gravity = Gravity.START;
            timeParams.gravity = Gravity.START;
            
            holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_analyst);
            holder.tvMessage.setTextColor(Color.parseColor("#1a1a2e")); // text_navy
        }

        holder.llBubbleContainer.setLayoutParams(bubbleParams);
        holder.tvSenderLabel.setLayoutParams(labelParams);
        holder.tvTimestamp.setLayoutParams(timeParams);
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llBubbleContainer;
        TextView tvMessage, tvSenderLabel, tvTimestamp;
        ViewHolder(View v) {
            super(v);
            llBubbleContainer = v.findViewById(R.id.llBubbleContainer);
            tvMessage         = v.findViewById(R.id.tvMessage);
            tvSenderLabel     = v.findViewById(R.id.tvSenderLabel);
            tvTimestamp       = v.findViewById(R.id.tvTimestamp);
        }
    }
}
