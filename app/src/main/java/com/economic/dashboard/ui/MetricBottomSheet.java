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
        View wrap = LayoutInflater.from(ctx).inflate(R.layout.sheet_metric_wrapper, null);
        FrameLayout holder = wrap.findViewById(R.id.sheetContent);
        View content = LayoutInflater.from(ctx).inflate(layoutRes, holder, false);
        holder.addView(content);
        BottomSheetDialog dialog =
                new BottomSheetDialog(ctx, R.style.Theme_EconomicDashboard_BottomSheet24);
        dialog.setContentView(wrap);
        View btn = content.findViewById(R.id.btnClose);
        if (btn != null) btn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        return content;
    }
}
