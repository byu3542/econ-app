package com.economic.dashboard.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

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
        // Rebind the previous row: its run-grouping / timestamp context changed
        if (messages.size() > 1) notifyItemChanged(messages.size() - 2);
    }

    /** Replaces the whole list — used when restoring a persisted conversation. */
    public void setMessages(List<ChatMessage> restored) {
        messages.clear();
        if (restored != null) messages.addAll(restored);
        notifyDataSetChanged();
    }

    /** Streams new text into an existing bubble (SSE partials). */
    public void updateMessageText(int index, String text) {
        if (index < 0 || index >= messages.size()) return;
        messages.get(index).setText(text);
        notifyItemChanged(index);
    }

    public int getLastIndex() { return messages.size() - 1; }

    public void removeTypingIndicator() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isTyping()) {
                messages.remove(i);
                notifyItemRemoved(i);
                break;
            }
        }
    }

    /** Removes the most recent error bubble (before a retry). */
    public void removeLastError() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isError()) {
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

        // Run grouping: label only on the first message of a run from the same
        // sender; timestamp only on the last message of a run.
        boolean runStart = position == 0
                || messages.get(position - 1).isUser() != m.isUser()
                || messages.get(position - 1).isTyping();
        boolean runEnd = position == messages.size() - 1
                || messages.get(position + 1).isUser() != m.isUser();
        holder.tvSenderLabel.setVisibility(runStart ? View.VISIBLE : View.GONE);
        holder.tvTimestamp.setVisibility(runEnd && !m.isTyping() ? View.VISIBLE : View.GONE);
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
                holder.tvMessage.setTextColor(androidx.core.content.ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.error_text));
                if (retryListener != null) {
                    holder.llBubbleContainer.setClickable(true);
                    holder.llBubbleContainer.setOnClickListener(v -> retryListener.onRetry());
                }
            } else {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_analyst);
                holder.tvMessage.setTextColor(androidx.core.content.ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.text_navy));
                holder.llBubbleContainer.setClickable(false);
                holder.llBubbleContainer.setOnClickListener(null);
            }
        }

        // Long-press → copy / share (any real message)
        if (!m.isTyping() && m.getText() != null && !m.getText().isEmpty()) {
            holder.llBubbleContainer.setOnLongClickListener(v -> {
                showMessageMenu(v, m.getText());
                return true;
            });
        } else {
            holder.llBubbleContainer.setOnLongClickListener(null);
        }

        holder.llBubbleContainer.setLayoutParams(bubbleParams);
        holder.tvSenderLabel.setLayoutParams(labelParams);
        holder.tvTimestamp.setLayoutParams(timeParams);
    }

    private void showMessageMenu(View anchor, String text) {
        Context ctx = anchor.getContext();
        PopupMenu menu = new PopupMenu(ctx, anchor);
        menu.getMenu().add(0, 1, 0, "Copy");
        menu.getMenu().add(0, 2, 1, "Share");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Analyst message", text));
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (item.getItemId() == 2) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, text);
                ctx.startActivity(Intent.createChooser(share, "Share message"));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void startDancingDots(ViewHolder holder) {
        stopDancingDots(holder);
        holder.dotAnimators.add(animateDot(holder.dot1, 0));
        holder.dotAnimators.add(animateDot(holder.dot2, 200));
        holder.dotAnimators.add(animateDot(holder.dot3, 400));
    }

    /**
     * ObjectAnimators are not stopped by View.clearAnimation() — they must be
     * cancelled explicitly, otherwise they run forever on recycled views.
     */
    private void stopDancingDots(ViewHolder holder) {
        for (ObjectAnimator a : holder.dotAnimators) a.cancel();
        holder.dotAnimators.clear();
        holder.dot1.setTranslationY(0f);
        holder.dot2.setTranslationY(0f);
        holder.dot3.setTranslationY(0f);
    }

    private ObjectAnimator animateDot(View dot, long delay) {
        // Use dp-based pixels so bounce scales correctly across all screen densities
        float bouncePx = 8f * dot.getResources().getDisplayMetrics().density;
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat("translationY", 0f, -bouncePx, 0f));
        animator.setDuration(600);
        animator.setStartDelay(delay);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.start();
        return animator;
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        stopDancingDots(holder);
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llBubbleContainer, llTypingIndicator;
        TextView tvMessage, tvSenderLabel, tvTimestamp;
        View dot1, dot2, dot3;
        final List<ObjectAnimator> dotAnimators = new ArrayList<>();

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
