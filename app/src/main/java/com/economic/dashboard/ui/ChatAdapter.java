package com.economic.dashboard.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.models.ChatMessage;

import io.noties.markwon.Markwon;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public interface OnRetryListener {
        void onRetry();
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private final OnRetryListener retryListener;
    private Markwon markwon;

    public ChatAdapter(OnRetryListener retryListener) {
        this.retryListener = retryListener;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void removeTypingIndicator() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isTyping()) {
                messages.remove(i);
                notifyItemRemoved(i);
                break;
            }
        }
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

        // Initialise Markwon once, lazily using the first available context
        if (markwon == null) {
            markwon = Markwon.create(holder.itemView.getContext());
        }

        if (m.isTyping()) {
            holder.tvMessage.setVisibility(View.GONE);
            holder.llTypingIndicator.setVisibility(View.VISIBLE);
            startDancingDots(holder);
        } else {
            holder.tvMessage.setVisibility(View.VISIBLE);
            holder.llTypingIndicator.setVisibility(View.GONE);
            stopDancingDots(holder);
            // Render markdown for analyst messages; plain text for user / error
            if (!m.isUser() && !m.isError()) {
                markwon.setMarkdown(holder.tvMessage, m.getText());
            } else {
                holder.tvMessage.setText(m.getText());
            }
        }

        holder.tvTimestamp.setText(m.getTimestamp());

        DisplayMetrics dm = holder.itemView.getResources().getDisplayMetrics();
        int maxBubbleWidth = (int) (dm.widthPixels * 0.78f);

        ConstraintLayout.LayoutParams bubbleParams = (ConstraintLayout.LayoutParams) holder.llBubbleContainer.getLayoutParams();
        bubbleParams.matchConstraintMaxWidth = maxBubbleWidth;
        ConstraintLayout.LayoutParams labelParams  = (ConstraintLayout.LayoutParams) holder.tvSenderLabel.getLayoutParams();
        ConstraintLayout.LayoutParams timeParams   = (ConstraintLayout.LayoutParams) holder.tvTimestamp.getLayoutParams();

        if (m.isUser()) {
            holder.tvSenderLabel.setText("YOU");
            bubbleParams.horizontalBias = 1.0f;
            labelParams.horizontalBias  = 1.0f;
            timeParams.horizontalBias   = 1.0f;
            holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_user);
            holder.tvMessage.setTextColor(Color.WHITE);
            holder.llBubbleContainer.setClickable(false);
            holder.llBubbleContainer.setOnClickListener(null);
        } else {
            holder.tvSenderLabel.setText(m.isError() ? "ERROR" : "ANALYST");
            bubbleParams.horizontalBias = 0.0f;
            labelParams.horizontalBias  = 0.0f;
            timeParams.horizontalBias   = 0.0f;
            if (m.isError()) {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_error);
                holder.tvMessage.setTextColor(Color.parseColor("#B00020"));
                if (retryListener != null) {
                    holder.llBubbleContainer.setClickable(true);
                    holder.llBubbleContainer.setOnClickListener(v -> retryListener.onRetry());
                }
            } else {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_analyst);
                holder.tvMessage.setTextColor(Color.parseColor("#1a1a2e"));
                holder.llBubbleContainer.setClickable(false);
                holder.llBubbleContainer.setOnClickListener(null);
            }
        }

        holder.llBubbleContainer.setLayoutParams(bubbleParams);
        holder.tvSenderLabel.setLayoutParams(labelParams);
        holder.tvTimestamp.setLayoutParams(timeParams);
    }

    private void startDancingDots(ViewHolder holder) {
        animateDot(holder.dot1, 0);
        animateDot(holder.dot2, 200);
        animateDot(holder.dot3, 400);
    }

    private void stopDancingDots(ViewHolder holder) {
        holder.dot1.clearAnimation();
        holder.dot2.clearAnimation();
        holder.dot3.clearAnimation();
    }

    private void animateDot(View dot, long delay) {
        // Use dp-based pixels so bounce scales correctly across all screen densities
        float bouncePx = 8f * dot.getResources().getDisplayMetrics().density;
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat("translationY", 0f, -bouncePx, 0f));
        animator.setDuration(600);
        animator.setStartDelay(delay);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.start();
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llBubbleContainer, llTypingIndicator;
        TextView tvMessage, tvSenderLabel, tvTimestamp;
        View dot1, dot2, dot3;

        ViewHolder(View v) {
            super(v);
            llBubbleContainer = v.findViewById(R.id.llBubbleContainer);
            llTypingIndicator = v.findViewById(R.id.llTypingIndicator);
            tvMessage         = v.findViewById(R.id.tvMessage);
            tvSenderLabel     = v.findViewById(R.id.tvSenderLabel);
            tvTimestamp       = v.findViewById(R.id.tvTimestamp);
            dot1              = v.findViewById(R.id.dot1);
            dot2              = v.findViewById(R.id.dot2);
            dot3              = v.findViewById(R.id.dot3);
        }
    }
}
