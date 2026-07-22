package com.economic.dashboard.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.economic.dashboard.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Shows metric-detail layouts as modal bottom sheets (replaces center AlertDialogs). */
public final class MetricBottomSheet {
    private MetricBottomSheet() {}

    /**
     * Inflates layoutRes inside the sheet chrome (rounded navy top + drag handle),
     * wires the optional btnClose, and shows the sheet.
     * @return the inflated content view so callers can bind data to it.
     */
    public static View show(Context ctx, int layoutRes) {
        return show(ctx, layoutRes, null, null);
    }

    /**
     * TICKET-24 overload: when {@code seriesKey}/{@code seriesLabel} are given,
     * appends an "＋ Add alert" affordance to the sheet that opens the
     * threshold-rule dialog for that metric.
     */
    public static View show(Context ctx, int layoutRes, String seriesKey, String seriesLabel) {
        View wrap = LayoutInflater.from(ctx).inflate(R.layout.sheet_metric_wrapper, null);
        FrameLayout holder = wrap.findViewById(R.id.sheetContent);
        View content = LayoutInflater.from(ctx).inflate(layoutRes, holder, false);
        holder.addView(content);
        BottomSheetDialog dialog =
                new BottomSheetDialog(ctx, R.style.Theme_EconomicDashboard_BottomSheet24);
        dialog.setContentView(wrap);
        View btn = content.findViewById(R.id.btnClose);
        if (btn != null) btn.setOnClickListener(v -> dialog.dismiss());

        // TICKET-24: metric-aware sheets get an "Add alert" row appended to the
        // content column (sheet content roots are vertical LinearLayouts).
        if (seriesKey != null && seriesLabel != null
                && content instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout column = (android.widget.LinearLayout) content;
            View row = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_add_alert_row, column, false);
            View tap = row.findViewById(R.id.tvAddAlert);
            (tap != null ? tap : row).setOnClickListener(
                    v -> showAddAlertDialog(ctx, seriesKey, seriesLabel));
            column.addView(row);
        }

        dialog.show();
        return content;
    }

    /** TICKET-24: dialog to create (or clear) threshold alert rules for a metric. */
    private static void showAddAlertDialog(Context ctx, String seriesKey, String seriesLabel) {
        View form = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_alert, null);
        android.widget.TextView tvName = form.findViewById(R.id.tvAlertMetricName);
        android.widget.RadioGroup rg   = form.findViewById(R.id.rgAlertOp);
        android.widget.EditText etThr  = form.findViewById(R.id.etAlertThreshold);
        android.widget.TextView tvEx   = form.findViewById(R.id.tvExistingAlerts);
        android.widget.TextView btnRm  = form.findViewById(R.id.btnRemoveAlerts);

        if (tvName != null) tvName.setText(seriesLabel);

        // Show existing rules for this metric + a remove affordance.
        java.util.List<com.economic.dashboard.alerts.AlertRule> existing =
                com.economic.dashboard.utils.SettingsManager.getAlertRules(ctx);
        StringBuilder sb = new StringBuilder();
        for (com.economic.dashboard.alerts.AlertRule r : existing) {
            if (seriesKey.equals(r.seriesKey)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(r.describe());
            }
        }
        if (sb.length() > 0) {
            if (tvEx != null)  { tvEx.setText(sb.toString()); tvEx.setVisibility(View.VISIBLE); }
            if (btnRm != null) btnRm.setVisibility(View.VISIBLE);
        }

        androidx.appcompat.app.AlertDialog alertDialog =
                new androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setView(form)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            String op = com.economic.dashboard.alerts.AlertRule.OP_ABOVE;
                            if (rg != null) {
                                int checked = rg.getCheckedRadioButtonId();
                                if (checked == R.id.rbBelow)  op = com.economic.dashboard.alerts.AlertRule.OP_BELOW;
                                if (checked == R.id.rbChange) op = com.economic.dashboard.alerts.AlertRule.OP_CHANGE;
                            }
                            double thr = 0;
                            if (!com.economic.dashboard.alerts.AlertRule.OP_CHANGE.equals(op)) {
                                try {
                                    thr = Double.parseDouble(
                                            etThr != null ? etThr.getText().toString().trim() : "");
                                } catch (NumberFormatException e) {
                                    android.widget.Toast.makeText(ctx,
                                            R.string.alert_threshold_hint,
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    return;
                                }
                            }
                            com.economic.dashboard.utils.SettingsManager.addAlertRule(ctx,
                                    new com.economic.dashboard.alerts.AlertRule(
                                            seriesKey, seriesLabel, op, thr));
                            android.widget.Toast.makeText(ctx, R.string.alert_saved,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

        if (btnRm != null) {
            btnRm.setOnClickListener(v -> {
                com.economic.dashboard.utils.SettingsManager.removeAlertRulesFor(ctx, seriesKey);
                android.widget.Toast.makeText(ctx, R.string.alerts_removed,
                        android.widget.Toast.LENGTH_SHORT).show();
                alertDialog.dismiss();
            });
        }

        alertDialog.show();
    }
}
