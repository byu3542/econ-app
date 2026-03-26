package com.economic.dashboard.ui;

import android.app.Dialog;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.analyst.NewsContextBuilder;
import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.news.NewsItem;
import com.economic.dashboard.news.NewsRepository;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI Economic Analyst panel — shown as a BottomSheetDialogFragment covering 62% of the screen.
 * The data screen behind it remains visible (and slightly dimmed by the scrim).
 *
 * Chat state (adapter + conversation history) is owned by MainActivity so it survives
 * dialog close/reopen.  This fragment accesses it via the parent activity.
 */
public class AiAnalystBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "AiAnalystBottomSheet";

    // ─── Convenience access to shared state in MainActivity ────────────────────

    private MainActivity host() {
        return (MainActivity) requireActivity();
    }

    private ChatAdapter     chatAdapter()         { return host().getChatAdapter(); }
    private JSONArray       conversationHistory() { return host().getConversationHistory(); }
    private OkHttpClient    httpClient()          { return host().getHttpClient(); }
    private String          lastUserQuery()       { return host().getLastUserQuery(); }
    private void            setLastUserQuery(String q) { host().setLastUserQuery(q); }

    // ─── BottomSheetDialogFragment overrides ───────────────────────────────────

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24);
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Reuse the existing dialog_ai_chat layout unchanged
        return inflater.inflate(R.layout.dialog_ai_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ── Wire up views (before view.post so keyboard listener can capture them) ──
        RecyclerView rvChat = view.findViewById(R.id.rvChat);
        EditText     etMsg  = view.findViewById(R.id.etMessage);
        ImageButton  btnSend = view.findViewById(R.id.btnSend);
        View         btnClose = view.findViewById(R.id.btnClose);

        View chipYieldCurve    = view.findViewById(R.id.chipYieldCurve);
        View chipRecession     = view.findViewById(R.id.chipRecession);
        View chipWages         = view.findViewById(R.id.chipWages);
        View chipRecentUpdates = view.findViewById(R.id.chipRecentUpdates);
        View chipRecentNews    = view.findViewById(R.id.chipRecentNews);

        // Attach the persistent adapter (history survives close/reopen)
        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(chatAdapter());

        // Scroll to latest message
        if (chatAdapter().getItemCount() > 0) {
            rvChat.scrollToPosition(chatAdapter().getItemCount() - 1);
        }

        // ── Set sheet to 80%, keyboard-aware resize ───────────────────────────
        view.post(() -> {
            Dialog dlg = getDialog();
            if (dlg == null || dlg.getWindow() == null) return;

            // Ensure adjustResize on the Dialog's own window
            dlg.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            FrameLayout sheet = dlg.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;

            int screenHeight = requireContext().getResources()
                    .getDisplayMetrics().heightPixels;
            int sheetHeight = (int) (screenHeight * 0.80f);

            sheet.getLayoutParams().height = sheetHeight;
            sheet.setLayoutParams(sheet.getLayoutParams());

            BottomSheetBehavior<FrameLayout> behavior =
                    BottomSheetBehavior.from(sheet);
            behavior.setFitToContents(true);
            behavior.setHideable(true);
            behavior.setSkipCollapsed(false);
            behavior.setPeekHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

            // ── Keyboard-aware dynamic height adjustment ─────────────────────
            View decorView = dlg.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (getDialog() == null || getDialog().getWindow() == null) return;

                Rect visibleFrame = new Rect();
                decorView.getWindowVisibleDisplayFrame(visibleFrame);
                int keypadHeight = screenHeight - visibleFrame.bottom;

                if (keypadHeight > screenHeight * 0.15) {
                    // Keyboard is visible — shrink sheet so input bar sits above it
                    int adjustedHeight = screenHeight - keypadHeight;
                    if (sheet.getLayoutParams().height != adjustedHeight) {
                        sheet.getLayoutParams().height = adjustedHeight;
                        sheet.requestLayout();
                        behavior.setPeekHeight(adjustedHeight);
                        // Scroll chat to bottom so latest message stays visible
                        if (chatAdapter().getItemCount() > 0) {
                            rvChat.post(() -> rvChat.scrollToPosition(
                                    chatAdapter().getItemCount() - 1));
                        }
                    }
                } else {
                    // Keyboard is hidden — restore original 80% height
                    if (sheet.getLayoutParams().height != sheetHeight) {
                        sheet.getLayoutParams().height = sheetHeight;
                        sheet.requestLayout();
                        behavior.setPeekHeight(sheetHeight);
                    }
                }
            });
        });

        // ── Chip listeners ────────────────────────────────────────────────────
        if (chipYieldCurve != null) chipYieldCurve.setOnClickListener(v ->
                etMsg.setText("Analyze the current yield curve health."));
        if (chipRecession != null) chipRecession.setOnClickListener(v ->
                etMsg.setText("What is the current recession risk based on this data?"));
        if (chipWages != null) chipWages.setOnClickListener(v ->
                etMsg.setText("What are the latest real wage trends?"));
        if (chipRecentUpdates != null) chipRecentUpdates.setOnClickListener(v ->
                etMsg.setText(buildRecentUpdatesQuery()));
        if (chipRecentNews != null) chipRecentNews.setOnClickListener(v ->
                etMsg.setText(buildRecentNewsQuery()));

        // ── Send button ───────────────────────────────────────────────────────
        btnSend.setOnClickListener(v -> {
            String userQuery = etMsg.getText().toString().trim();
            if (!userQuery.isEmpty()) {
                etMsg.setText("");
                chatAdapter().addMessage(new ChatMessage(userQuery, true));
                rvChat.scrollToPosition(chatAdapter().getItemCount() - 1);
                queryClaude(userQuery, rvChat);
            }
        });

        // ── Close button ──────────────────────────────────────────────────────
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }

    }

    // ─── Claude query ───────────────────────────────────────────────────────────

    private void queryClaude(String userQuery, RecyclerView rv) {
        if (ApiConfig.ANTHROPIC_API_KEY.equals("YOUR_ANTHROPIC_API_KEY")
                || ApiConfig.ANTHROPIC_API_KEY.isEmpty()) {
            chatAdapter().addMessage(new ChatMessage(
                    "Please provide a valid Anthropic API Key in local.properties.", false));
            return;
        }

        setLastUserQuery(userQuery);

        // Show typing indicator immediately
        chatAdapter().addMessage(new ChatMessage("", false, true));
        scrollToBottom(rv);

        try {
            // Build messages: committed history + new user message
            List<NewsItem> cachedNews = NewsRepository.getInstance().getCachedItems();

            JSONArray messages = new JSONArray();
            for (int i = 0; i < conversationHistory().length(); i++) {
                messages.put(conversationHistory().get(i));
            }
            // Append a brief headline note to the user turn for immediate context
            String enrichedQuery = userQuery + NewsContextBuilder.buildBrief(cachedNews);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", enrichedQuery);
            messages.put(userMsg);

            // Build system prompt with full data snapshot + news context
            String dataSnapshot = constructEconomicContext();
            String newsContext = NewsContextBuilder.build(cachedNews);

            // DEBUG: Log the full context so we can verify in Logcat
            Log.d("AiAnalyst", "=== CONTEXT SNAPSHOT ===\n" + dataSnapshot + newsContext);

            String systemPrompt = "You are an AI Economic Analyst embedded in the U.S. Economic Monitor app. "
                    + "You have access to the following live data pulled directly from the app. "
                    + "Always use these exact figures when answering questions — never substitute "
                    + "general knowledge for values that appear in the data snapshot below. "
                    + "If a value is listed as 'Unavailable' it has not yet been fetched.\n\n"
                    + "FORMATTING RULES: Do NOT use markdown headers (##, ###, or # prefix). "
                    + "Do NOT use bullet lists with dashes or asterisks. "
                    + "Use short paragraphs and bold text (**word**) only for key figures or labels. "
                    + "Keep responses under 200 words unless the user explicitly asks for detail. "
                    + "When news headlines are provided, weave relevant recent developments "
                    + "into your analysis where appropriate.\n\n"
                    + dataSnapshot
                    + newsContext;

            JSONObject body = new JSONObject();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 1024);
            body.put("system", systemPrompt);
            body.put("messages", messages);

            Request request = new Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", ApiConfig.ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                    .build();

            httpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("Claude", "Query failed", e);
                    requireActivity().runOnUiThread(() -> {
                        chatAdapter().removeTypingIndicator();
                        chatAdapter().addMessage(new ChatMessage(
                                "Connection failed — tap to retry.", false, false, true));
                        scrollToBottom(rv);
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response)
                        throws IOException {
                    try (Response r = response) {
                        String responseBody = r.body() != null ? r.body().string() : "";
                        if (r.isSuccessful()) {
                            JSONObject json = new JSONObject(responseBody);
                            String text = json.getJSONArray("content")
                                    .getJSONObject(0).getString("text");

                            // Commit exchange to history only on success
                            JSONObject committedUser = new JSONObject();
                            committedUser.put("role", "user");
                            committedUser.put("content", userQuery);
                            conversationHistory().put(committedUser);

                            JSONObject assistantMsg = new JSONObject();
                            assistantMsg.put("role", "assistant");
                            assistantMsg.put("content", text);
                            conversationHistory().put(assistantMsg);

                            requireActivity().runOnUiThread(() -> {
                                chatAdapter().removeTypingIndicator();
                                chatAdapter().addMessage(new ChatMessage(text, false));
                                scrollToBottom(rv);
                            });
                        } else {
                            Log.e("Claude", "API error " + r.code() + ": " + responseBody);
                            requireActivity().runOnUiThread(() -> {
                                chatAdapter().removeTypingIndicator();
                                chatAdapter().addMessage(new ChatMessage(
                                        "API error " + r.code() + " — tap to retry.",
                                        false, false, true));
                                scrollToBottom(rv);
                            });
                        }
                    } catch (Exception e) {
                        Log.e("Claude", "Response parse failed", e);
                        requireActivity().runOnUiThread(() -> {
                            chatAdapter().removeTypingIndicator();
                            chatAdapter().addMessage(new ChatMessage(
                                    "Failed to parse response — tap to retry.",
                                    false, false, true));
                            scrollToBottom(rv);
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.e("Claude", "Request build failed", e);
            chatAdapter().removeTypingIndicator();
            chatAdapter().addMessage(new ChatMessage("Error: " + e.getMessage(), false));
        }
    }

    private void scrollToBottom(RecyclerView rv) {
        if (rv != null && chatAdapter().getItemCount() > 0) {
            rv.smoothScrollToPosition(chatAdapter().getItemCount() - 1);
        }
    }

    // ─── Economic context builder — full data snapshot ─────────────────────────

    private String constructEconomicContext() {
        EconomicViewModel vm = host().getViewModel();
        StringBuilder sb = new StringBuilder();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).format(new Date());
        sb.append("=== U.S. ECONOMIC MONITOR — LIVE DATA SNAPSHOT ===\n");
        sb.append("As of: ").append(timestamp).append("\n\n");

        // ── CORE INDICATORS ──────────────────────────────────────────────
        sb.append("CORE INDICATORS\n");

        // Fed Funds Rate
        EconomicDataPoint fedFunds = EconomicViewModel.getLatest(
                vm.getFedFundsData().getValue(), "Federal Funds Effective Rate");
        sb.append("Fed Funds Rate: ").append(fedFunds != null
                ? String.format(Locale.US, "%.2f%% (%s)", fedFunds.getValue(), fedFunds.getDate())
                : "Unavailable").append("\n");

        // GDP Growth — 4Q Rolling Average
        List<EconomicDataPoint> gdpList = vm.getGdpData().getValue();
        List<EconomicDataPoint> gdpRows = gdpList != null
                ? EconomicViewModel.filterBySeries(gdpList, "Gross domestic product")
                : null;
        if (gdpRows != null && !gdpRows.isEmpty()) {
            int start = Math.max(0, gdpRows.size() - 4);
            double sum = 0;
            for (int i = start; i < gdpRows.size(); i++) sum += gdpRows.get(i).getValue();
            double avg = sum / (gdpRows.size() - start);
            sb.append(String.format(Locale.US, "GDP Growth (4Q Avg): %.2f%%\n", avg));
        } else {
            sb.append("GDP Growth (4Q Avg): Unavailable\n");
        }

        // GDP Growth — Latest Quarter
        if (gdpRows != null && !gdpRows.isEmpty()) {
            EconomicDataPoint latestGdp = gdpRows.get(gdpRows.size() - 1);
            String qLabel = vm.getLatestQuarterLabel().getValue();
            sb.append(String.format(Locale.US, "GDP Growth (Latest Quarter): %.2f%% (%s)\n",
                    latestGdp.getValue(), qLabel != null ? qLabel : latestGdp.getDate()));
        } else {
            sb.append("GDP Growth (Latest Quarter): Unavailable\n");
        }

        // CPI-U YoY
        List<EconomicDataPoint> cpiList = vm.getCpiData().getValue();
        List<EconomicDataPoint> cpiRows = cpiList != null
                ? EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") : null;
        if (cpiRows != null && cpiRows.size() >= 13) {
            double cpiYoy = ((cpiRows.get(cpiRows.size()-1).getValue()
                    - cpiRows.get(cpiRows.size()-13).getValue())
                    / cpiRows.get(cpiRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "CPI-U YoY: %.2f%% (%s)\n",
                    cpiYoy, cpiRows.get(cpiRows.size()-1).getDate()));
        } else {
            sb.append("CPI-U YoY: Unavailable\n");
        }

        // Unemployment Rate
        EconomicDataPoint unemp = EconomicViewModel.getLatest(
                vm.getEmploymentData().getValue(), "Unemployment Rate");
        sb.append("Unemployment Rate: ").append(unemp != null
                ? String.format(Locale.US, "%.1f%% (%s)", unemp.getValue(), unemp.getDate())
                : "Unavailable").append("\n");

        // Labor Force Participation
        EconomicDataPoint lfpr = EconomicViewModel.getLatest(
                vm.getEmploymentData().getValue(), "Labor Force Participation Rate");
        sb.append("Labor Force Participation: ").append(lfpr != null
                ? String.format(Locale.US, "%.1f%% (%s)", lfpr.getValue(), lfpr.getDate())
                : "Unavailable").append("\n");

        // PCE Inflation (Fed's preferred)
        List<EconomicDataPoint> pceList = vm.getPceData().getValue();
        List<EconomicDataPoint> pceRows = pceList != null
                ? EconomicViewModel.filterBySeries(pceList, "PCE Price Index") : null;
        if (pceRows != null && pceRows.size() >= 13) {
            double pceYoy = ((pceRows.get(pceRows.size()-1).getValue()
                    - pceRows.get(pceRows.size()-13).getValue())
                    / pceRows.get(pceRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "PCE Inflation (YoY): %.2f%% (%s)\n",
                    pceYoy, pceRows.get(pceRows.size()-1).getDate()));
        } else {
            sb.append("PCE Inflation (YoY): Unavailable\n");
        }

        // Core PCE
        List<EconomicDataPoint> corePceRows = pceList != null
                ? EconomicViewModel.filterBySeries(pceList, "Core PCE Price Index") : null;
        if (corePceRows != null && corePceRows.size() >= 13) {
            double coreYoy = ((corePceRows.get(corePceRows.size()-1).getValue()
                    - corePceRows.get(corePceRows.size()-13).getValue())
                    / corePceRows.get(corePceRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "Core PCE (YoY): %.2f%% (%s)\n",
                    coreYoy, corePceRows.get(corePceRows.size()-1).getDate()));
        } else {
            sb.append("Core PCE (YoY): Unavailable\n");
        }

        // ISM Manufacturing PMI
        EconomicDataPoint pmi = EconomicViewModel.getLatest(
                vm.getIsmPmiData().getValue(), "NAPM");
        sb.append("ISM Manufacturing PMI: ").append(pmi != null
                ? String.format(Locale.US, "%.1f (%s)", pmi.getValue(), pmi.getDate())
                : "Unavailable").append("\n");

        // Wages
        EconomicDataPoint hourly = EconomicViewModel.getLatest(
                vm.getWageData().getValue(), "Average Hourly Earnings - Private");
        sb.append("Avg Hourly Earnings: ").append(hourly != null
                ? String.format(Locale.US, "$%.2f (%s)", hourly.getValue(), hourly.getDate())
                : "Unavailable").append("\n");
        EconomicDataPoint weekly = EconomicViewModel.getLatest(
                vm.getWageData().getValue(), "Average Weekly Earnings - Private");
        sb.append("Avg Weekly Earnings: ").append(weekly != null
                ? String.format(Locale.US, "$%.2f (%s)", weekly.getValue(), weekly.getDate())
                : "Unavailable").append("\n");

        // Real wage spread (wages YoY minus CPI YoY)
        List<EconomicDataPoint> wageList = vm.getWageData().getValue();
        List<EconomicDataPoint> wageRows = wageList != null
                ? EconomicViewModel.filterBySeries(wageList, "Average Hourly Earnings - Private") : null;
        if (wageRows != null && wageRows.size() >= 13 && cpiRows != null && cpiRows.size() >= 13) {
            double wYoy = ((wageRows.get(wageRows.size()-1).getValue()
                    - wageRows.get(wageRows.size()-13).getValue())
                    / wageRows.get(wageRows.size()-13).getValue()) * 100.0;
            double cYoy = ((cpiRows.get(cpiRows.size()-1).getValue()
                    - cpiRows.get(cpiRows.size()-13).getValue())
                    / cpiRows.get(cpiRows.size()-13).getValue()) * 100.0;
            double spread = wYoy - cYoy;
            sb.append(String.format(Locale.US, "Real Wage Growth (Wages-CPI spread): %.2f%%\n", spread));
        } else {
            sb.append("Real Wage Growth (Wages-CPI spread): Unavailable\n");
        }

        // ── TREASURY YIELD CURVE ─────────────────────────────────────────
        sb.append("\nTREASURY YIELD CURVE\n");
        List<EconomicDataPoint> treasuryList = vm.getTreasuryData().getValue();
        String[] maturities = {"1 Month", "3 Month", "6 Month", "1 Year",
                               "2 Year", "5 Year", "10 Year", "30 Year"};
        String[] shortLabels = {"1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y", "30Y"};
        if (treasuryList != null && !treasuryList.isEmpty()) {
            for (int i = 0; i < maturities.length; i++) {
                EconomicDataPoint yld = EconomicViewModel.getLatest(treasuryList, maturities[i]);
                sb.append(shortLabels[i]).append(": ").append(yld != null
                        ? String.format(Locale.US, "%.2f%%", yld.getValue())
                        : "N/A");
                sb.append(i < maturities.length - 1 ? "  " : "\n");
            }
        } else {
            sb.append("Unavailable\n");
        }

        // Spreads
        List<EconomicDataPoint> spread10y2y = vm.getCalculatedSpreadData().getValue();
        if (spread10y2y != null && !spread10y2y.isEmpty()) {
            EconomicDataPoint s = spread10y2y.get(spread10y2y.size() - 1);
            sb.append(String.format(Locale.US, "10Y-2Y Spread: %.2f%%%s\n",
                    s.getValue(), s.getValue() < 0 ? " (INVERTED)" : ""));
        } else {
            sb.append("10Y-2Y Spread: Unavailable\n");
        }

        List<EconomicDataPoint> spread10y3m = vm.getCalculatedSpread3MData().getValue();
        if (spread10y3m != null && !spread10y3m.isEmpty()) {
            EconomicDataPoint s = spread10y3m.get(spread10y3m.size() - 1);
            sb.append(String.format(Locale.US, "10Y-3M Spread: %.2f%%%s\n",
                    s.getValue(), s.getValue() < 0 ? " (INVERTED)" : ""));
        } else {
            sb.append("10Y-3M Spread: Unavailable\n");
        }

        // ── HOUSING ──────────────────────────────────────────────────────
        sb.append("\nHOUSING\n");
        List<EconomicDataPoint> housingList = vm.getHousingData().getValue();
        EconomicDataPoint starts = housingList != null
                ? EconomicViewModel.getLatest(housingList, "Housing Starts") : null;
        sb.append("Housing Starts: ").append(starts != null
                ? String.format(Locale.US, "%.0f K units annualized (%s)", starts.getValue(), starts.getDate())
                : "Unavailable").append("\n");

        EconomicDataPoint sales = housingList != null
                ? EconomicViewModel.getLatest(housingList, "Existing Home Sales") : null;
        sb.append("Existing Home Sales: ").append(sales != null
                ? String.format(Locale.US, "%.0f K units (%s)", sales.getValue(), sales.getDate())
                : "Unavailable").append("\n");

        // MBS & Mortgage — from mbsMortgageData
        List<EconomicDataPoint> mbsList = vm.getMbsMortgageData().getValue();

        EconomicDataPoint mortgage = mbsList != null
                ? EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate") : null;
        sb.append("30-Yr Mortgage Rate: ").append(mortgage != null
                ? String.format(Locale.US, "%.2f%% (%s)", mortgage.getValue(), mortgage.getDate())
                : "Unavailable").append("\n");

        EconomicDataPoint bankMbs = mbsList != null
                ? EconomicViewModel.getLatest(mbsList, "Bank MBS Holdings") : null;
        sb.append("Bank MBS Holdings: ").append(bankMbs != null
                ? String.format(Locale.US, "$%.1f B (%s)", bankMbs.getValue(), bankMbs.getDate())
                : "Unavailable").append("\n");

        EconomicDataPoint fedMbs = mbsList != null
                ? EconomicViewModel.getLatest(mbsList, "Fed MBS Holdings") : null;
        sb.append("Fed MBS Holdings: ").append(fedMbs != null
                ? String.format(Locale.US, "$%.1f M (%s)", fedMbs.getValue(), fedMbs.getDate())
                : "Unavailable").append("\n");

        sb.append("\n");
        return sb.toString();
    }

    private String buildRecentUpdatesQuery() {
        EconomicViewModel vm = host().getViewModel();
        StringBuilder sb = new StringBuilder(
                "Briefly summarise the most significant recent US economic developments "
                + "based on the latest dashboard readings:");

        // PCE inflation (Fed target)
        List<EconomicDataPoint> pceList = vm.getPceData().getValue();
        if (pceList != null) {
            List<EconomicDataPoint> pceRows =
                    EconomicViewModel.filterBySeries(pceList, "PCE Price Index");
            if (pceRows.size() >= 13) {
                double yoy = ((pceRows.get(pceRows.size()-1).getValue()
                        - pceRows.get(pceRows.size()-13).getValue())
                        / pceRows.get(pceRows.size()-13).getValue()) * 100.0;
                sb.append(String.format(Locale.US, " PCE inflation %.2f%% YoY (Fed target 2%%);", yoy));
            }
        }

        // CPI-U
        List<EconomicDataPoint> cpiList = vm.getCpiData().getValue();
        if (cpiList != null) {
            List<EconomicDataPoint> cpiRows =
                    EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items");
            if (cpiRows.size() >= 13) {
                double yoy = ((cpiRows.get(cpiRows.size()-1).getValue()
                        - cpiRows.get(cpiRows.size()-13).getValue())
                        / cpiRows.get(cpiRows.size()-13).getValue()) * 100.0;
                sb.append(String.format(Locale.US, " CPI-U %.2f%% YoY;", yoy));
            }
        }

        // Unemployment
        EconomicDataPoint unemp = EconomicViewModel.getLatest(
                vm.getEmploymentData().getValue(), "Unemployment Rate");
        if (unemp != null)
            sb.append(String.format(Locale.US,
                    " unemployment %.1f%% (%s);", unemp.getValue(), unemp.getDate()));

        // GDP 4-quarter rolling avg
        List<EconomicDataPoint> gdpList = vm.getGdpData().getValue();
        if (gdpList != null) {
            List<EconomicDataPoint> gdpRows =
                    EconomicViewModel.filterBySeries(gdpList, "Gross domestic product");
            if (!gdpRows.isEmpty()) {
                int start = Math.max(0, gdpRows.size() - 4);
                double sum = 0;
                for (int i = start; i < gdpRows.size(); i++) sum += gdpRows.get(i).getValue();
                double avg = sum / (gdpRows.size() - start);
                sb.append(String.format(Locale.US, " GDP 4Q avg %.2f%%;", avg));
            }
        }

        // Housing starts
        List<EconomicDataPoint> housingList = vm.getHousingData().getValue();
        if (housingList != null) {
            EconomicDataPoint starts =
                    EconomicViewModel.getLatest(housingList, "Housing Starts");
            if (starts != null)
                sb.append(String.format(Locale.US,
                        " housing starts %.0fK (annualized);", starts.getValue()));
            EconomicDataPoint sales =
                    EconomicViewModel.getLatest(housingList, "Existing Home Sales");
            if (sales != null)
                sb.append(String.format(Locale.US,
                        " existing home sales %.2fM;", sales.getValue() / 1_000_000.0));
        }

        // Wages
        EconomicDataPoint wage = EconomicViewModel.getLatest(
                vm.getWageData().getValue(), "Average Hourly Earnings - Private");
        if (wage != null)
            sb.append(String.format(Locale.US,
                    " avg hourly earnings $%.2f (%s);", wage.getValue(), wage.getDate()));

        // MBS & Mortgage
        List<EconomicDataPoint> mbsList = vm.getMbsMortgageData().getValue();
        if (mbsList != null) {
            EconomicDataPoint mortgage = EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate");
            if (mortgage != null)
                sb.append(String.format(Locale.US,
                        " 30yr mortgage %.2f%%;", mortgage.getValue()));
            EconomicDataPoint bankMbs = EconomicViewModel.getLatest(mbsList, "Bank MBS Holdings");
            if (bankMbs != null)
                sb.append(String.format(Locale.US,
                        " bank MBS $%.1fB;", bankMbs.getValue()));
            EconomicDataPoint fedMbs = EconomicViewModel.getLatest(mbsList, "Fed MBS Holdings");
            if (fedMbs != null)
                sb.append(String.format(Locale.US,
                        " Fed MBS $%.1fM;", fedMbs.getValue()));
        }

        // 10Y-2Y spread
        List<EconomicDataPoint> spread = vm.getCalculatedSpreadData().getValue();
        if (spread != null && !spread.isEmpty()) {
            double sv = spread.get(spread.size()-1).getValue();
            sb.append(String.format(Locale.US,
                    " 10Y-2Y spread %.2f%%%s.", sv, sv < 0 ? " (inverted)" : ""));
        }

        sb.append(" Which of these readings stand out most, and what do they collectively "
                + "signal about the near-term economic outlook?");
        return sb.toString();
    }

    private String buildRecentNewsQuery() {
        List<NewsItem> cached = NewsRepository.getInstance().getCachedItems();
        if (cached.isEmpty()) {
            return "What are the most important recent developments in the US economy "
                    + "that I should be aware of right now?";
        }
        StringBuilder sb = new StringBuilder(
                "Based on the latest economic news headlines, what are the most significant "
                + "stories right now and what do they signal for the economic outlook? "
                + "Focus on the highest-impact items.");
        // Mention the top 3 high-impact headlines explicitly so Claude can address them
        int count = 0;
        for (int tier = 2; tier >= 1 && count < 3; tier--) {
            for (NewsItem item : cached) {
                if (count >= 3) break;
                if (item.impactLevel == tier && item.title != null) {
                    if (count == 0) sb.append(" Recent headlines include: \"").append(item.title).append("\"");
                    else sb.append(", \"").append(item.title).append("\"");
                    count++;
                }
            }
        }
        if (count > 0) sb.append(".");
        return sb.toString();
    }
}
