package com.economic.dashboard.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.utils.AppExecutors;
import com.economic.dashboard.views.SparklineView;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonSpansFactory;

import org.commonmark.node.StrongEmphasis;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public interface OnRetryListener {
        void onRetry();
    }

    /** Callbacks into MainActivity for interactive analyst replies. */
    public interface OnAnalystActionListener {
        /** A tappable metric citation was tapped — navigate to that tab. */
        void onMetricTapped(String tabKey);
        /** "Ask AI" chosen from the text-selection menu. */
        void onAskAi(String query);
    }

    /** Partial-bind payload: only the message text changed (SSE streaming). */
    private static final Object PAYLOAD_TEXT = new Object();

    /** Timestamps only render when the next message is at least this far away. */
    private static final long TIME_GAP_MS = 15 * 60 * 1000L;

    /** Inline chart tag emitted by the model: [CHART:DGS10:24M] */
    private static final Pattern CHART_TAG =
            Pattern.compile("\\[CHART:([A-Z0-9_]+):(\\d{1,2})M?\\]");

    /** Human labels for chart captions. */
    private static final Map<String, String> SERIES_LABELS = new HashMap<>();
    static {
        SERIES_LABELS.put("DGS10", "10-Year Treasury Yield");
        SERIES_LABELS.put("DGS2", "2-Year Treasury Yield");
        SERIES_LABELS.put("DGS3MO", "3-Month T-Bill Yield");
        SERIES_LABELS.put("MORTGAGE30US", "30-Year Mortgage Rate");
        SERIES_LABELS.put("LNS14000000", "Unemployment Rate");
        SERIES_LABELS.put("GDP_BEA_T10101", "GDP Growth (QoQ annualized)");
    }

    /**
     * Metric names → bottom-nav tab. First occurrence of each keyword in an
     * analyst reply becomes a tappable link that jumps to that screen.
     * LinkedHashMap: longer/more specific phrases first so they win the range.
     */
    private static final Map<String, String> METRIC_TABS = new LinkedHashMap<>();
    static {
        METRIC_TABS.put("yield curve", "markets");
        METRIC_TABS.put("fed funds rate", "overview");
        METRIC_TABS.put("federal funds rate", "overview");
        METRIC_TABS.put("unemployment rate", "economy");
        METRIC_TABS.put("unemployment", "economy");
        METRIC_TABS.put("cpi", "economy");
        METRIC_TABS.put("inflation", "economy");
        METRIC_TABS.put("gdp", "economy");
        METRIC_TABS.put("mortgage rate", "economy");
        METRIC_TABS.put("housing starts", "economy");
        METRIC_TABS.put("treasury", "markets");
        METRIC_TABS.put("s&p 500", "markets");
        METRIC_TABS.put("nasdaq", "markets");
        METRIC_TABS.put("vix", "markets");
    }

    /** Series data cache so recycled rows don't re-query Room. */
    private static final Map<String, List<Double>> seriesCache = new HashMap<>();

    private final List<ChatMessage> messages = new ArrayList<>();
    private final OnRetryListener retryListener;
    private final OnAnalystActionListener actionListener;
    private Markwon markwon;

    public ChatAdapter(OnRetryListener retryListener, OnAnalystActionListener actionListener) {
        this.retryListener = retryListener;
        this.actionListener = actionListener;
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

    /** Streams new text into an existing bubble (SSE partials) via a payload bind. */
    public void updateMessageText(int index, String text) {
        if (index < 0 || index >= messages.size()) return;
        messages.get(index).setText(text);
        notifyItemChanged(index, PAYLOAD_TEXT);
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

    /** Lazily builds Markwon; **bold** key figures render in gold ink. */
    private void ensureMarkwon(Context ctx) {
        if (markwon != null) return;
        final int goldInk = androidx.core.content.ContextCompat.getColor(ctx, R.color.gold_ink);
        markwon = Markwon.builder(ctx)
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
                        builder.appendFactory(StrongEmphasis.class,
                                (configuration, props) -> new ForegroundColorSpan(goldInk));
                    }
                })
                .build();
    }

    private void renderText(ViewHolder holder, ChatMessage m) {
        if (!m.isUser() && !m.isError()) {
            String display = stripChartTags(m.getText());
            markwon.setMarkdown(holder.tvMessage, display);
            addMetricCitations(holder.tvMessage);
        } else {
            holder.tvMessage.setText(m.getText());
        }
    }

    /** Removes [CHART:...] tags from the visible text (rendered separately). */
    private static String stripChartTags(String text) {
        if (text == null) return "";
        return CHART_TAG.matcher(text).replaceAll("").replace("\n\n\n", "\n\n").trim();
    }

    /**
     * Turns the first occurrence of each known indicator name into a tappable
     * link that navigates to the matching dashboard tab. Chat becomes navigation.
     */
    private void addMetricCitations(TextView tv) {
        if (actionListener == null) return;
        CharSequence cs = tv.getText();
        if (!(cs instanceof Spannable)) return;
        Spannable spannable = (Spannable) cs;
        String lower = cs.toString().toLowerCase(Locale.US);

        List<int[]> claimed = new ArrayList<>();
        boolean any = false;
        for (Map.Entry<String, String> e : METRIC_TABS.entrySet()) {
            int idx = lower.indexOf(e.getKey());
            if (idx < 0) continue;
            int end = idx + e.getKey().length();
            boolean overlaps = false;
            for (int[] c : claimed)
                if (idx < c[1] && end > c[0]) { overlaps = true; break; }
            if (overlaps) continue;
            claimed.add(new int[]{idx, end});
            final String tabKey = e.getValue();
            spannable.setSpan(new ClickableSpan() {
                @Override public void onClick(@NonNull View widget) {
                    actionListener.onMetricTapped(tabKey);
                }
                @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                    ds.setUnderlineText(true); // keep bubble text color, just underline
                }
            }, idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            any = true;
        }
        if (any) tv.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /** Binds the inline sparkline if the analyst reply contains a chart tag. */
    private void bindSparkline(ViewHolder holder, ChatMessage m) {
        if (m.isUser() || m.isError() || m.isTyping() || m.getText() == null) {
            holder.hideSparkline(); return;
        }
        Matcher matcher = CHART_TAG.matcher(m.getText());
        if (!matcher.find()) { holder.hideSparkline(); return; }

        final String seriesId = matcher.group(1);
        int months = 24;
        try { months = Math.max(1, Math.min(24, Integer.parseInt(matcher.group(2)))); }
        catch (Exception ignored) {}

        String label = SERIES_LABELS.get(seriesId);
        if (label == null) { holder.hideSparkline(); return; }

        holder.sparkline.setVisibility(View.VISIBLE);
        holder.tvChartCaption.setVisibility(View.VISIBLE);
        holder.tvChartCaption.setText(String.format(Locale.US, "%s — last %d months", label, months));
        holder.sparkline.setLineColor(androidx.core.content.ContextCompat.getColor(
                holder.itemView.getContext(), R.color.gold_ink));

        final String cacheKey = seriesId + ":" + months;
        List<Double> cached;
        synchronized (seriesCache) { cached = seriesCache.get(cacheKey); }
        if (cached != null) { holder.sparkline.setData(cached); return; }

        holder.sparkline.setTag(cacheKey);
        final Context appCtx = holder.itemView.getContext().getApplicationContext();
        final int m2 = months;
        AppExecutors.getInstance().diskIO().execute(() -> {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.MONTH, -m2);
            String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
            List<EconomicHistoryEntry> rows =
                    YieldDatabase.getInstance(appCtx).economicHistoryDao().getSeriesSync(seriesId);
            final List<Double> values = new ArrayList<>();
            if (rows != null)
                for (EconomicHistoryEntry row : rows)
                    if (row.date.compareTo(cutoff) >= 0) values.add(row.value);
            synchronized (seriesCache) { seriesCache.put(cacheKey, values); }
            holder.sparkline.post(() -> {
                // Guard against recycling: only draw if this row still wants this series
                if (cacheKey.equals(holder.sparkline.getTag())) {
                    if (values.size() >= 2) holder.sparkline.setData(values);
                    else holder.hideSparkline();
                }
            });
        });
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            // Streaming update: swap the text only — no full rebind, no flicker.
            ensureMarkwon(holder.itemView.getContext());
            renderText(holder, messages.get(position));
            bindSparkline(holder, messages.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage m = messages.get(position);
        ensureMarkwon(holder.itemView.getContext());

        if (m.isTyping()) {
            holder.tvMessage.setVisibility(View.GONE);
            holder.llTypingIndicator.setVisibility(View.VISIBLE);
            startDancingDots(holder);
        } else {
            holder.tvMessage.setVisibility(View.VISIBLE);
            holder.llTypingIndicator.setVisibility(View.GONE);
            stopDancingDots(holder);
            renderText(holder, m);
        }

        bindSparkline(holder, m);

        // Attached image (vision messages) — transient, shown only this session
        if (m.getImageBitmap() != null) {
            holder.ivChatImage.setVisibility(View.VISIBLE);
            holder.ivChatImage.setImageBitmap(m.getImageBitmap());
        } else {
            holder.ivChatImage.setVisibility(View.GONE);
            holder.ivChatImage.setImageDrawable(null);
        }

        // Run grouping: label only on the first message of a run from the same
        // sender; timestamp only at the end of a run AND when a real time break
        // follows (or it's the newest message) — keeps the transcript quiet.
        boolean runStart = position == 0
                || messages.get(position - 1).isUser() != m.isUser()
                || messages.get(position - 1).isTyping();
        boolean runEnd = position == messages.size() - 1
                || messages.get(position + 1).isUser() != m.isUser();
        boolean showTime = runEnd && !m.isTyping()
                && (position == messages.size() - 1
                    || messages.get(position + 1).getTimeMillis() - m.getTimeMillis() > TIME_GAP_MS);
        holder.tvSenderLabel.setVisibility(runStart ? View.VISIBLE : View.GONE);
        holder.tvTimestamp.setVisibility(showTime ? View.VISIBLE : View.GONE);
        holder.tvTimestamp.setText(m.getTimestamp());

        DisplayMetrics dm = holder.itemView.getResources().getDisplayMetrics();
        // Short user questions read better narrower; analyst prose gets more room.
        int maxBubbleWidth = (int) (dm.widthPixels * (m.isUser() ? 0.72f : 0.80f));

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
            holder.tvMessage.setTextIsSelectable(false);
            holder.tvMessage.setCustomSelectionActionModeCallback(null);
            holder.tvRetry.setVisibility(View.GONE);
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
                holder.tvMessage.setTextIsSelectable(false);
                holder.tvRetry.setVisibility(View.VISIBLE);
                if (retryListener != null) {
                    holder.tvRetry.setOnClickListener(v -> retryListener.onRetry());
                    holder.llBubbleContainer.setClickable(true);
                    holder.llBubbleContainer.setOnClickListener(v -> retryListener.onRetry());
                }
            } else {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_analyst);
                holder.tvMessage.setTextColor(androidx.core.content.ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.text_navy));
                holder.tvRetry.setVisibility(View.GONE);
                holder.llBubbleContainer.setClickable(false);
                holder.llBubbleContainer.setOnClickListener(null);
                if (!m.isTyping()) {
                    // Toggle off/on so selection keeps working on recycled views
                    holder.tvMessage.setTextIsSelectable(false);
                    holder.tvMessage.setTextIsSelectable(true);
                    attachAskAiSelection(holder.tvMessage);
                }
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

    /** Adds an "Ask AI" item to the text-selection toolbar of analyst bubbles. */
    private void attachAskAiSelection(TextView tv) {
        if (actionListener == null) return;
        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, R.id.menu_ask_ai, 0, "Ask AI");
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != R.id.menu_ask_ai) return false;
                int start = tv.getSelectionStart(), end = tv.getSelectionEnd();
                if (start > end) { int t = start; start = end; end = t; }
                if (start >= 0 && end <= tv.getText().length() && start < end) {
                    String selected = tv.getText().subSequence(start, end).toString().trim();
                    if (!selected.isEmpty())
                        actionListener.onAskAi("Tell me more about this: \"" + selected + "\"");
                }
                mode.finish();
                return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) {}
        });
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
        TextView tvMessage, tvSenderLabel, tvTimestamp, tvRetry, tvChartCaption;
        ImageView ivChatImage;
        SparklineView sparkline;
        View dot1, dot2, dot3;
        final List<ObjectAnimator> dotAnimators = new ArrayList<>();

        ViewHolder(View v) {
            super(v);
            llBubbleContainer = v.findViewById(R.id.llBubbleContainer);
            llTypingIndicator = v.findViewById(R.id.llTypingIndicator);
            tvMessage         = v.findViewById(R.id.tvMessage);
            tvSenderLabel     = v.findViewById(R.id.tvSenderLabel);
            tvTimestamp       = v.findViewById(R.id.tvTimestamp);
            tvRetry           = v.findViewById(R.id.tvRetry);
            tvChartCaption    = v.findViewById(R.id.tvChartCaption);
            ivChatImage       = v.findViewById(R.id.ivChatImage);
            sparkline         = v.findViewById(R.id.sparklineChart);
            dot1              = v.findViewById(R.id.dot1);
            dot2              = v.findViewById(R.id.dot2);
            dot3              = v.findViewById(R.id.dot3);
        }

        void hideSparkline() {
            sparkline.setVisibility(View.GONE);
            tvChartCaption.setVisibility(View.GONE);
            sparkline.setTag(null);
        }
    }
}
