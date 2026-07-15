package com.economic.dashboard.news;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * News list: hero for the newest story, then rows grouped under
 * Today / Yesterday / This week / Earlier section headers.
 * Read articles are dimmed (state kept in SharedPreferences).
 */
public class NewsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HERO   = 0;
    private static final int VIEW_TYPE_ROW    = 1;
    private static final int VIEW_TYPE_HEADER = 2;

    private static final String PREFS_READ = "news_read_state";
    private static final String KEY_READ_URLS = "read_urls";
    private static final int MAX_READ_URLS = 400;

    /** One displayed row: either a section header or a news item. */
    private static class Row {
        final int type; final NewsItem item; final String header;
        Row(int type, NewsItem item, String header) {
            this.type = type; this.item = item; this.header = header;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public void submitList(List<NewsItem> items) {
        rows.clear();
        if (items != null && !items.isEmpty()) {
            rows.add(new Row(VIEW_TYPE_HERO, items.get(0), null));
            String lastBucket = null;
            for (int i = 1; i < items.size(); i++) {
                NewsItem item = items.get(i);
                String bucket = bucketFor(item.pubDateMillis);
                if (!bucket.equals(lastBucket)) {
                    rows.add(new Row(VIEW_TYPE_HEADER, null, bucket));
                    lastBucket = bucket;
                }
                rows.add(new Row(VIEW_TYPE_ROW, item, null));
            }
        }
        notifyDataSetChanged();
    }

    private static String bucketFor(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        if (millis >= todayStart) return "Today";
        if (millis >= todayStart - 86_400_000L) return "Yesterday";
        if (millis >= todayStart - 6 * 86_400_000L) return "This week";
        return "Earlier";
    }

    @Override
    public int getItemCount() { return rows.size(); }

    @Override
    public int getItemViewType(int position) { return rows.get(position).type; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HERO) {
            return new HeroViewHolder(inflater.inflate(R.layout.item_news_hero, parent, false));
        } else if (viewType == VIEW_TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_news_header, parent, false));
        } else {
            return new RowViewHolder(inflater.inflate(R.layout.item_news_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeroViewHolder) {
            ((HeroViewHolder) holder).bind(row.item, this);
        } else if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(row.header);
        } else {
            ((RowViewHolder) holder).bind(row.item, this);
        }
    }

    // ─── Read state ─────────────────────────────────────────────────────────────

    static boolean isRead(Context ctx, String url) {
        if (url == null) return false;
        return prefs(ctx).getStringSet(KEY_READ_URLS, new HashSet<>()).contains(url);
    }

    static void markRead(Context ctx, String url) {
        if (url == null) return;
        Set<String> current = new HashSet<>(prefs(ctx).getStringSet(KEY_READ_URLS, new HashSet<>()));
        if (!current.add(url)) return;
        if (current.size() > MAX_READ_URLS) current.clear(); // crude cap; re-dims naturally
        prefs(ctx).edit().putStringSet(KEY_READ_URLS, current).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_READ, Context.MODE_PRIVATE);
    }

    void openAndMarkRead(RecyclerView.ViewHolder holder, NewsItem item) {
        Context ctx = holder.itemView.getContext();
        markRead(ctx, item.url);
        int pos = holder.getBindingAdapterPosition();
        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
        openUrl(ctx, item.url);
    }

    private static void applyReadDim(View headline, Context ctx, String url) {
        headline.setAlpha(isRead(ctx, url) ? 0.5f : 1f);
    }

    // ─── Long-press → AI Analyst ────────────────────────────────────────────────

    /** Long-press menu: analyze the article, or ask one of the smart prompts. */
    void showAnalyzeMenu(View anchor, NewsItem item) {
        Context ctx = anchor.getContext();
        androidx.fragment.app.FragmentActivity activity = findActivity(ctx);
        if (activity == null || item.title == null) return;

        android.widget.PopupMenu menu = new android.widget.PopupMenu(ctx, anchor);
        menu.getMenu().add(0, 1, 0, "✨ Analyze with AI Analyst");
        java.util.List<String> prompts =
                com.economic.dashboard.analyst.SmartPromptGenerator.generatePromptsForHeadline(item.title);
        int shown = Math.min(2, prompts.size());
        for (int i = 0; i < shown; i++)
            menu.getMenu().add(0, 2 + i, 1 + i, prompts.get(i));

        final java.util.List<String> finalPrompts = prompts;
        menu.setOnMenuItemClickListener(mi -> {
            String query;
            if (mi.getItemId() == 1) {
                StringBuilder sb = new StringBuilder("Analyze this news story in the context of the current economic data:\n\n");
                sb.append("\"").append(item.title).append("\"");
                if (item.source != null) sb.append(" — ").append(item.source);
                if (item.summary != null && !item.summary.isEmpty()) {
                    String sum = item.summary.length() > 300 ? item.summary.substring(0, 300) + "…" : item.summary;
                    sb.append("\n\n").append(sum);
                }
                sb.append("\n\nWhat does this mean for the economic outlook?");
                query = sb.toString();
            } else {
                int idx = mi.getItemId() - 2;
                if (idx < 0 || idx >= finalPrompts.size()) return false;
                query = "Regarding the headline \"" + item.title + "\": " + finalPrompts.get(idx);
            }
            com.economic.dashboard.analyst.AskAnalyst.openWithQuery(activity, query);
            return true;
        });
        menu.show();
    }

    private static androidx.fragment.app.FragmentActivity findActivity(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof androidx.fragment.app.FragmentActivity)
                return (androidx.fragment.app.FragmentActivity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    // ─── Header ViewHolder ──────────────────────────────────────────────────────

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tv;
        HeaderViewHolder(View v) { super(v); tv = v.findViewById(R.id.tv_section_header); }
        void bind(String header) { tv.setText(header); }
    }

    // ─── Hero ViewHolder ────────────────────────────────────────────────────────

    static class HeroViewHolder extends RecyclerView.ViewHolder {
        TextView tvSource, tvTimeAgo, tvHeadline, tvSummary;

        HeroViewHolder(View v) {
            super(v);
            tvSource   = v.findViewById(R.id.tv_hero_source);
            tvTimeAgo  = v.findViewById(R.id.tv_hero_time);
            tvHeadline = v.findViewById(R.id.tv_hero_headline);
            tvSummary  = v.findViewById(R.id.tv_hero_summary);
        }

        void bind(NewsItem item, NewsAdapter adapter) {
            tvSource.setText(item.source != null ? item.source.toUpperCase(Locale.US) : "");
            tvTimeAgo.setText(formatTimeAgo(item.pubDateMillis));
            tvHeadline.setText(item.title);
            tvSummary.setText(item.summary);
            tvSummary.setVisibility(item.summary != null && !item.summary.isEmpty()
                    ? View.VISIBLE : View.GONE);
            applyReadDim(tvHeadline, itemView.getContext(), item.url);

            itemView.setOnClickListener(v -> adapter.openAndMarkRead(this, item));
            itemView.setOnLongClickListener(v -> { adapter.showAnalyzeMenu(v, item); return true; });
        }
    }

    // ─── Row ViewHolder ─────────────────────────────────────────────────────────

    static class RowViewHolder extends RecyclerView.ViewHolder {
        View      impactBar;
        TextView  tvSource, tvTimeAgo, tvHeadline, tvTag;
        ImageView ivThumb;

        RowViewHolder(View v) {
            super(v);
            impactBar  = v.findViewById(R.id.view_impact_bar);
            tvSource   = v.findViewById(R.id.tv_row_source);
            tvTimeAgo  = v.findViewById(R.id.tv_row_time);
            tvHeadline = v.findViewById(R.id.tv_row_headline);
            tvTag      = v.findViewById(R.id.tv_row_tag);
            ivThumb    = v.findViewById(R.id.iv_row_thumb);
        }

        void bind(NewsItem item, NewsAdapter adapter) {
            tvSource.setText(item.source != null ? item.source.toUpperCase(Locale.US) : "");
            tvTimeAgo.setText(formatTimeAgo(item.pubDateMillis));
            tvHeadline.setText(item.title);
            tvTag.setText(item.tag != null ? item.tag : "GENERAL");
            setTagColor(tvTag, item.tag != null ? item.tag : "GENERAL");
            setImpactBarColor(impactBar, item.impactLevel);
            applyReadDim(tvHeadline, itemView.getContext(), item.url);
            ThumbLoader.load(ivThumb, item.imageUrl);

            itemView.setOnClickListener(v -> adapter.openAndMarkRead(this, item));
            itemView.setOnLongClickListener(v -> { adapter.showAnalyzeMenu(v, item); return true; });
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    static String formatTimeAgo(long pubMillis) {
        long diff = System.currentTimeMillis() - pubMillis;
        long minutes = diff / 60_000;
        long hours   = diff / 3_600_000;
        long days    = diff / 86_400_000;

        if (minutes < 60)  return minutes + "m ago";
        if (hours   < 24)  return hours   + "h ago";
        if (days    < 7)   return days    + "d ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(pubMillis));
    }

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static void setTagColor(TextView tv, String tag) {
        boolean night = isNight(tv.getContext());
        int bgColor, textColor;
        switch (tag) {
            case "FED":
                bgColor   = night ? 0x2E8fa3c8 : 0x140f1729;
                textColor = night ? 0xFFB9C6E0 : 0xFF0f1729;
                break;
            case "INFLATION":
                bgColor   = 0x18f0a500;
                textColor = night ? 0xFFF0B23E : 0xFFa06000;
                break;
            case "JOBS":
                bgColor   = 0x1822c55e;
                textColor = night ? 0xFF6EE7A0 : 0xFF166534;
                break;
            case "YIELDS":
                bgColor   = 0x1ec9a84c;
                textColor = night ? 0xFFDDBE6B : 0xFF7a5a10;
                break;
            case "ECONOMY":
                bgColor   = 0x183b82f6;
                textColor = night ? 0xFF8FB8F8 : 0xFF1e40af;
                break;
            case "RESEARCH":
                bgColor   = 0x186366f1;
                textColor = night ? 0xFFA5A8F5 : 0xFF3730a3;
                break;
            case "HOUSING":
                bgColor   = 0x1806b6d4;
                textColor = night ? 0xFF67D9EE : 0xFF0e7490;
                break;
            default: // GENERAL
                bgColor   = 0x14888888;
                textColor = night ? 0xFF9AA3B5 : 0xFF666666;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(tv.getContext(), 10));
        bg.setColor(bgColor);
        tv.setBackground(bg);
        tv.setTextColor(textColor);
    }

    static void setImpactBarColor(View bar, int impactLevel) {
        switch (impactLevel) {
            case 2:
                bar.setBackgroundColor(0xFFc9a84c); // gold
                bar.setContentDescription("High impact story");
                bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                break;
            case 1:
                bar.setBackgroundColor(0x59c9a84c); // gold @ ~35% alpha
                bar.setContentDescription("Medium impact story");
                bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                break;
            default:
                bar.setBackgroundColor(0x00000000); // transparent
                bar.setContentDescription(null);
                bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    static void openUrl(Context context, String url) {
        if (url == null || url.isEmpty()) return;
        try {
            CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF0F1729)
                    .build();
            CustomTabsIntent customTab = new CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .build();
            customTab.launchUrl(context, Uri.parse(url));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static float dp(Context ctx, float dp) {
        return dp * ctx.getResources().getDisplayMetrics().density;
    }
}
