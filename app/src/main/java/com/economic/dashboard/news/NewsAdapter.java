package com.economic.dashboard.news;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NewsAdapter extends ListAdapter<NewsItem, RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HERO = 0;
    private static final int VIEW_TYPE_ROW  = 1;

    private static final DiffUtil.ItemCallback<NewsItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<NewsItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull NewsItem o, @NonNull NewsItem n) {
                    if (o.url == null || n.url == null) return false;
                    return o.url.equals(n.url);
                }
                @Override
                public boolean areContentsTheSame(@NonNull NewsItem o, @NonNull NewsItem n) {
                    if (o.url == null || n.url == null) return false;
                    return o.url.equals(n.url)
                            && (o.title != null ? o.title.equals(n.title) : n.title == null);
                }
            };

    public NewsAdapter() {
        super(DIFF_CALLBACK);
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? VIEW_TYPE_HERO : VIEW_TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HERO) {
            View v = inflater.inflate(R.layout.item_news_hero, parent, false);
            return new HeroViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_news_row, parent, false);
            return new RowViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        NewsItem item = getItem(position);
        if (holder instanceof HeroViewHolder) {
            ((HeroViewHolder) holder).bind(item);
        } else {
            ((RowViewHolder) holder).bind(item);
        }
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

        void bind(NewsItem item) {
            tvSource.setText(item.source != null ? item.source.toUpperCase(Locale.US) : "");
            tvTimeAgo.setText(formatTimeAgo(item.pubDateMillis));
            tvHeadline.setText(item.title);
            tvSummary.setText(item.summary);
            tvSummary.setVisibility(item.summary != null && !item.summary.isEmpty()
                    ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> openUrl(v.getContext(), item.url));
        }
    }

    // ─── Row ViewHolder ─────────────────────────────────────────────────────────

    static class RowViewHolder extends RecyclerView.ViewHolder {
        View     impactBar;
        TextView tvSource, tvTimeAgo, tvHeadline, tvTag;

        RowViewHolder(View v) {
            super(v);
            impactBar  = v.findViewById(R.id.view_impact_bar);
            tvSource   = v.findViewById(R.id.tv_row_source);
            tvTimeAgo  = v.findViewById(R.id.tv_row_time);
            tvHeadline = v.findViewById(R.id.tv_row_headline);
            tvTag      = v.findViewById(R.id.tv_row_tag);
        }

        void bind(NewsItem item) {
            tvSource.setText(item.source != null ? item.source.toUpperCase(Locale.US) : "");
            tvTimeAgo.setText(formatTimeAgo(item.pubDateMillis));
            tvHeadline.setText(item.title);
            tvTag.setText(item.tag != null ? item.tag : "GENERAL");
            setTagColor(tvTag, item.tag != null ? item.tag : "GENERAL");
            setImpactBarColor(impactBar, item.impactLevel);

            itemView.setOnClickListener(v -> openUrl(v.getContext(), item.url));
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

    static void setTagColor(TextView tv, String tag) {
        int bgColor, textColor;
        switch (tag) {
            case "FED":
                bgColor   = 0x140f1729;
                textColor = 0xFF0f1729;
                break;
            case "INFLATION":
                bgColor   = 0x18f0a500;
                textColor = 0xFFa06000;
                break;
            case "JOBS":
                bgColor   = 0x1822c55e;
                textColor = 0xFF166534;
                break;
            case "YIELDS":
                bgColor   = 0x1ec9a84c;
                textColor = 0xFF7a5a10;
                break;
            case "ECONOMY":
                bgColor   = 0x183b82f6;
                textColor = 0xFF1e40af;
                break;
            case "RESEARCH":
                bgColor   = 0x186366f1;
                textColor = 0xFF3730a3;
                break;
            case "HOUSING":
                bgColor   = 0x1806b6d4;
                textColor = 0xFF0e7490;
                break;
            default: // GENERAL
                bgColor   = 0x14888888;
                textColor = 0xFF666666;
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
                break;
            case 1:
                bar.setBackgroundColor(0x59c9a84c); // gold @ ~35% alpha
                break;
            default:
                bar.setBackgroundColor(0x00000000); // transparent
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
