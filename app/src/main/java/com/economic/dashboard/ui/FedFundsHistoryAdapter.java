package com.economic.dashboard.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.models.EconomicDataPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FedFundsHistoryAdapter extends RecyclerView.Adapter<FedFundsHistoryAdapter.ViewHolder> {

    private final List<FedChange> items = new ArrayList<>();

    public static class FedChange {
        public String date;
        public double rate;
        public double bpsChange;

        public FedChange(String date, double rate, double bpsChange) {
            this.date = date;
            this.rate = rate;
            this.bpsChange = bpsChange;
        }
    }

    public void setData(List<EconomicDataPoint> history) {
        items.clear();
        if (history == null || history.size() < 2) return;

        // history is sorted newest to oldest from FRED if we use sort_order=desc
        // We want to calculate change from previous (older) month.
        for (int i = 0; i < history.size() - 1; i++) {
            EconomicDataPoint current = history.get(i);
            EconomicDataPoint previous = history.get(i + 1);

            double diff = current.getValue() - previous.getValue();
            double bps = diff * 100.0;

            String displayDate = current.getDate();
            if (displayDate.length() >= 7) {
                displayDate = displayDate.substring(5, 7) + "-" + displayDate.substring(0, 4);
            }

            items.add(new FedChange(displayDate, current.getValue(), bps));
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fed_funds_history, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FedChange item = items.get(position);
        holder.tvDate.setText(item.date);
        holder.tvRate.setText(String.format(Locale.US, "%.2f%%", item.rate));

        String sign = item.bpsChange >= 0 ? "+" : "";
        holder.tvChange.setText(String.format(Locale.US, "%s%.0f bps", sign, item.bpsChange));

        if (item.bpsChange > 0) {
            holder.tvChange.setTextColor(Color.parseColor("#EF9A9A")); // Red-ish for rate hikes (usually bad for markets)
        } else if (item.bpsChange < 0) {
            holder.tvChange.setTextColor(Color.parseColor("#A5D6A7")); // Green-ish for cuts
        } else {
            holder.tvChange.setTextColor(Color.parseColor("#BBBBBB"));
            holder.tvChange.setText("0 bps");
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate, tvRate, tvChange;
        ViewHolder(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tvHistoryDate);
            tvRate = v.findViewById(R.id.tvHistoryRate);
            tvChange = v.findViewById(R.id.tvHistoryChange);
        }
    }
}
