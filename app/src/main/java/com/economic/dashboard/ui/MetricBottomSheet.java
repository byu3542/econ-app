package com.economic.dashboard.ui;

import android.content.Context;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.economic.dashboard.R;
import com.economic.dashboard.utils.EdgeToEdgeInsets;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
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
        View sheetScroll = wrap.findViewById(R.id.sheetScroll);
        View content = LayoutInflater.from(ctx).inflate(layoutRes, holder, false);
        holder.addView(content);
        BottomSheetDialog dialog =
                new BottomSheetDialog(ctx, R.style.Theme_EconomicDashboard_BottomSheet24);
        dialog.setContentView(wrap);
        View btn = content.findViewById(R.id.btnClose);
        if (btn != null) btn.setOnClickListener(v -> dialog.dismiss());

        // Clear the gesture-nav inset so the last row and "+ Add alert" pill
        // aren't flush with (or under) the home indicator. This is the raw
        // systemBars() bottom, not the app's bottom-nav-wrapper height — the
        // sheet sits above the bottom nav, not beside it.
        if (sheetScroll != null) {
            sheetScroll.setPadding(sheetScroll.getPaddingLeft(), sheetScroll.getPaddingTop(),
                    sheetScroll.getPaddingRight(),
                    sheetScroll.getPaddingBottom() + EdgeToEdgeInsets.getSystemBottomInsetPx());
        }

        // The sheet is sized to its content rather than a fixed ratio, so it
        // always occupies exactly the space its content needs — never dead
        // space below a short list, never a half-height that clips a taller
        // one and forces an extra drag to see the rest.
        BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);

        // Expanded state stops short of the status bar (+8dp) instead of going
        // flush to y=0 — a sheet flush to the top reads as a full-screen
        // dialog, not a sheet with the presenting screen still peeking above it.
        float density = ctx.getResources().getDisplayMetrics().density;
        behavior.setExpandedOffset(
                EdgeToEdgeInsets.getSystemTopInsetPx() + Math.round(8 * density));

        // The presenting screen recedes as the sheet opens further, so it
        // reads as still "there" behind the sheet rather than just gone.
        View presenting = (ctx instanceof android.app.Activity)
                ? ((android.app.Activity) ctx).findViewById(android.R.id.content) : null;
        if (presenting != null) {
            float maxCornerRadiusPx = 12 * density;
            float[] cornerRadiusPx = {0f};
            presenting.setClipToOutline(true);
            presenting.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx[0]);
                }
            });
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onSlide(@NonNull View sheetView, float slideOffset) {
                    float offset = Math.max(0f, slideOffset);
                    float scale = 1f - (offset * 0.06f);
                    presenting.setScaleX(scale);
                    presenting.setScaleY(scale);
                    cornerRadiusPx[0] = offset * maxCornerRadiusPx;
                    presenting.invalidateOutline();
                }
                @Override public void onStateChanged(@NonNull View sheetView, int newState) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        presenting.setScaleX(1f);
                        presenting.setScaleY(1f);
                        cornerRadiusPx[0] = 0f;
                        presenting.invalidateOutline();
                    }
                }
            });
            dialog.setOnDismissListener(d -> {
                presenting.setScaleX(1f);
                presenting.setScaleY(1f);
                cornerRadiusPx[0] = 0f;
                presenting.invalidateOutline();
            });
        }

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

        // Setting state before the sheet has been laid out is unreliable (the
        // behavior can re-resolve to STATE_COLLAPSED on first measure), so the
        // initial state is set after the first layout pass, via post().
        content.post(() -> {
            if (dialog.isShowing()) behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
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
