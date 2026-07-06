package com.economic.dashboard.ui;

import android.app.Dialog;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.analyst.HistoricalContextBuilder;
import com.economic.dashboard.analyst.NewsContextBuilder;
import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.databinding.DialogAiChatBinding;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.news.NewsItem;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.AppExecutors;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AiAnalystBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "AiAnalystBottomSheet";
    private static final String ARG_PREFILL = "prefill_query";
    /** Cap request context at the last N history entries (N/2 turns). */
    private static final int HISTORY_CAP = 20;

    private DialogAiChatBinding binding;

    /** True while a request is in flight — blocks double-sends. */
    private boolean awaitingResponse = false;

    /**
     * 24-month historical trend block built from the Room cache.
     * Preloaded on a background thread in onViewCreated (Room queries are
     * synchronous and must not run on the main thread), then injected into
     * the system prompt by queryClaude(). Empty string if cache is empty.
     */
    private volatile String historicalContext = "";

    /** Opens the sheet with a question that is sent immediately. */
    public static AiAnalystBottomSheet newInstance(String prefillQuery) {
        AiAnalystBottomSheet sheet = new AiAnalystBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PREFILL, prefillQuery);
        sheet.setArguments(args);
        return sheet;
    }

    // ─── Convenience access to shared state in MainActivity ────────────────────

    private MainActivity host() { return (MainActivity) requireActivity(); }
    private ChatAdapter     chatAdapter()         { return host().getChatAdapter(); }
    private JSONArray       conversationHistory() { return host().getConversationHistory(); }
    private OkHttpClient    httpClient()          { return host().getHttpClient(); }
    private String          lastUserQuery()       { return host().getLastUserQuery(); }
    private void            setLastUserQuery(String q) { host().setLastUserQuery(q); }

    /** Runs on the UI thread only if the sheet is still attached to its activity. */
    private void runOnUi(Runnable r) {
        android.app.Activity a = getActivity();
        if (a != null) a.runOnUiThread(() -> { if (isAdded()) r.run(); });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24);
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogAiChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView rvChat = binding.rvChat;
        EditText     etMsg  = binding.etMessage;
        ImageButton  btnSend = binding.btnSend;
        View         btnClose = binding.btnClose;

        View chipYieldCurve    = binding.chipYieldCurve;
        View chipRecession     = binding.chipRecession;
        View chipWages         = binding.chipWages;
        View chipRecentUpdates = binding.chipRecentUpdates;
        View chipRecentNews    = binding.chipRecentNews;

        // Settings: hide the quick-ask chip row (and its "ASK ABOUT" label)
        if (!com.economic.dashboard.utils.SettingsManager.getBool(requireContext(),
                com.economic.dashboard.utils.SettingsManager.KEY_SMART_CHIPS, true)) {
            // chip -> row LinearLayout -> HorizontalScrollView
            View chipScroll = (View) chipYieldCurve.getParent().getParent();
            ViewGroup sheetRoot = (ViewGroup) chipScroll.getParent();
            int scrollIdx = sheetRoot.indexOfChild(chipScroll);
            if (scrollIdx > 0) sheetRoot.getChildAt(scrollIdx - 1).setVisibility(View.GONE);
            chipScroll.setVisibility(View.GONE);
        }

        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(chatAdapter());

        // Preload the 24-month historical context from the Room cache so it's
        // ready before the first question is sent (Room must stay off the
        // main thread). Uses application context — safe if the sheet closes.
        final android.content.Context appCtx = requireContext().getApplicationContext();
        AppExecutors.getInstance().diskIO().execute(() -> historicalContext = HistoricalContextBuilder.build(appCtx));

        if (chatAdapter().getItemCount() > 0)
            rvChat.scrollToPosition(chatAdapter().getItemCount() - 1);

        // ── Set sheet to full screen, keyboard-aware resize ───────────────────
        view.post(() -> {
            Dialog dlg = getDialog();
            if (dlg == null || dlg.getWindow() == null) return;
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            FrameLayout sheet = dlg.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;

            int screenHeight = requireContext().getResources().getDisplayMetrics().heightPixels;
            int sheetHeight = screenHeight;

            sheet.getLayoutParams().height = sheetHeight;
            sheet.setLayoutParams(sheet.getLayoutParams());

            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setFitToContents(true);
            behavior.setHideable(true);
            behavior.setSkipCollapsed(false);
            behavior.setPeekHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

            View decorView = dlg.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (getDialog() == null || getDialog().getWindow() == null) return;
                Rect visibleFrame = new Rect();
                decorView.getWindowVisibleDisplayFrame(visibleFrame);
                int keypadHeight = screenHeight - visibleFrame.bottom;
                if (keypadHeight > screenHeight * 0.15) {
                    int adjustedHeight = screenHeight - keypadHeight;
                    if (sheet.getLayoutParams().height != adjustedHeight) {
                        sheet.getLayoutParams().height = adjustedHeight;
                        sheet.requestLayout();
                        behavior.setPeekHeight(adjustedHeight);
                        if (chatAdapter().getItemCount() > 0)
                            rvChat.post(() -> rvChat.scrollToPosition(chatAdapter().getItemCount() - 1));
                    }
                } else {
                    if (sheet.getLayoutParams().height != sheetHeight) {
                        sheet.getLayoutParams().height = sheetHeight;
                        sheet.requestLayout();
                        behavior.setPeekHeight(sheetHeight);
                    }
                }
            });
        });

        // ── Chips auto-send: one tap asks the question ─────────────────────────
        // Static fallbacks; refreshContextChips() swaps in live values once loaded.
        if (chipYieldCurve != null) chipYieldCurve.setOnClickListener(v -> sendQuery("Analyze the current yield curve health."));
        if (chipRecession != null) chipRecession.setOnClickListener(v -> sendQuery("What is the current recession risk based on this data?"));
        if (chipWages != null) chipWages.setOnClickListener(v -> sendQuery("What are the latest real wage trends?"));
        if (chipRecentUpdates != null) chipRecentUpdates.setOnClickListener(v -> sendQuery(buildRecentUpdatesQuery()));
        if (chipRecentNews != null) chipRecentNews.setOnClickListener(v -> sendQuery(buildRecentNewsQuery()));

        refreshContextChips();
        EconomicViewModel vm = host().getViewModel();
        vm.getCpiData().observe(getViewLifecycleOwner(), d -> refreshContextChips());
        vm.getEmploymentData().observe(getViewLifecycleOwner(), d -> refreshContextChips());
        vm.getTreasuryData().observe(getViewLifecycleOwner(), d -> refreshContextChips());

        // ── Scroll-to-bottom pill ──────────────────────────────────────────────
        binding.pillNewReply.setOnClickListener(v -> {
            if (chatAdapter().getItemCount() > 0)
                rvChat.smoothScrollToPosition(chatAdapter().getItemCount() - 1);
            binding.pillNewReply.setVisibility(View.GONE);
        });
        rvChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (binding == null) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= chatAdapter().getItemCount() - 1)
                    binding.pillNewReply.setVisibility(View.GONE);
            }
        });

        // ── Send state: enabled only with text and no request in flight ───────
        refreshSendEnabled();
        etMsg.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { refreshSendEnabled(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        etMsg.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); return true; }
            return false;
        });

        btnSend.setOnClickListener(v -> sendCurrentMessage());

        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        binding.btnClearChat.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("Clear conversation")
                .setMessage("Delete the full chat history? This can't be undone.")
                .setPositiveButton("Clear", (d, w) -> host().clearChatHistory())
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        // ── Prefill from "Ask about this" entry points ─────────────────────────
        if (savedInstanceState == null && getArguments() != null) {
            String prefill = getArguments().getString(ARG_PREFILL);
            if (prefill != null && !prefill.trim().isEmpty()) {
                getArguments().remove(ARG_PREFILL);
                view.post(() -> sendQuery(prefill.trim()));
            }
        }
    }

    /**
     * Rewrites the quick-ask chips with live readings so they demo the data
     * ("CPI at 2.6% — is that good?" beats a generic label). Falls back to
     * the static text set in the layout when a series hasn't loaded yet.
     */
    private void refreshContextChips() {
        if (binding == null || getActivity() == null) return;
        EconomicViewModel vm = host().getViewModel();

        List<EconomicDataPoint> cpiList = vm.getCpiData().getValue();
        List<EconomicDataPoint> cpiRows = cpiList != null
                ? EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") : null;
        if (cpiRows != null && cpiRows.size() >= 13) {
            double base = cpiRows.get(cpiRows.size() - 13).getValue();
            if (Math.abs(base) > 1e-9) {
                final double yoy = ((cpiRows.get(cpiRows.size() - 1).getValue() - base) / base) * 100.0;
                binding.chipWages.setText(String.format(Locale.US,
                        "CPI at %.1f%% — is that good?", yoy));
                binding.chipWages.setOnClickListener(v -> sendQuery(String.format(Locale.US,
                        "CPI inflation just came in at %.2f%% year-over-year. Is that good or bad, "
                        + "how does it compare historically, and are real wages keeping up?", yoy)));
            }
        }

        EconomicDataPoint unemp = EconomicViewModel.getLatest(
                vm.getEmploymentData().getValue(), "Unemployment Rate");
        if (unemp != null) {
            final double u = unemp.getValue();
            binding.chipRecession.setText(String.format(Locale.US,
                    "Unemployment %.1f%% — recession risk?", u));
            binding.chipRecession.setOnClickListener(v -> sendQuery(String.format(Locale.US,
                    "Unemployment is at %.1f%%. Combined with the rest of the dashboard data, "
                    + "what is the current recession risk?", u)));
        }

        List<EconomicDataPoint> treasury = vm.getTreasuryData().getValue();
        EconomicDataPoint tenY  = EconomicViewModel.getLatest(treasury, "10 Year");
        EconomicDataPoint threeM = EconomicViewModel.getLatest(treasury, "3 Month");
        if (tenY != null && threeM != null) {
            final double spread = tenY.getValue() - threeM.getValue();
            binding.chipYieldCurve.setText(String.format(Locale.US,
                    "10Y–3M at %+.2f%% — healthy?", spread));
            binding.chipYieldCurve.setOnClickListener(v -> sendQuery(String.format(Locale.US,
                    "The 10Y–3M Treasury spread is at %+.2f%%. What does the current yield curve "
                    + "shape signal about the economy?", spread)));
        }
    }

    private void refreshSendEnabled() {
        if (binding == null) return;
        boolean canSend = !awaitingResponse
                && binding.etMessage.getText().toString().trim().length() > 0;
        binding.btnSend.setEnabled(canSend);
        binding.btnSend.setAlpha(canSend ? 1f : 0.4f);
    }

    private void sendCurrentMessage() {
        if (binding == null) return;
        sendQuery(binding.etMessage.getText().toString().trim());
    }

    /** Single entry point for user-visible questions (input, chips, prompts, prefill). */
    private void sendQuery(String userQuery) {
        if (binding == null || awaitingResponse) return;
        if (userQuery == null || userQuery.trim().isEmpty()) return;
        userQuery = userQuery.trim();
        binding.etMessage.setText("");
        ChatMessage userMsg = new ChatMessage(userQuery, true);
        chatAdapter().addMessage(userMsg);
        host().persistChatMessage(userMsg);
        maybeScrollToBottom(true);
        queryClaude(userQuery, binding.rvChat);
    }

    /** Re-sends the last question after a tap on an error bubble. */
    public void resendLastQuery() {
        if (binding == null || awaitingResponse) return;
        String q = lastUserQuery();
        if (q == null || q.isEmpty()) return;
        chatAdapter().removeLastError();
        maybeScrollToBottom(true);
        queryClaude(q, binding.rvChat);
    }

    /** Terminal state of a request: allow sending again. */
    private void requestFinished() {
        awaitingResponse = false;
        refreshSendEnabled();
    }

    /** Autoscrolls only when the user is already near the bottom; otherwise shows the pill. */
    private void maybeScrollToBottom(boolean force) {
        if (binding == null) return;
        RecyclerView rv = binding.rvChat;
        int count = chatAdapter().getItemCount();
        if (count == 0) return;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        boolean nearBottom = lm != null && lm.findLastVisibleItemPosition() >= count - 2;
        if (force || nearBottom) {
            rv.scrollToPosition(count - 1);
            binding.pillNewReply.setVisibility(View.GONE);
        } else {
            binding.pillNewReply.setVisibility(View.VISIBLE);
        }
    }

    private void queryClaude(String userQuery, RecyclerView rv) {
        if (ApiConfig.PROXY_BASE_URL.isEmpty()) {
            chatAdapter().addMessage(new ChatMessage(
                    "AI Analyst is not configured. Deploy the proxy in the proxy/ folder, "
                    + "then set PROXY_BASE_URL in local.properties (see proxy/README.md).", false));
            return;
        }

        setLastUserQuery(userQuery);
        awaitingResponse = true;
        refreshSendEnabled();
        chatAdapter().addMessage(new ChatMessage("", false, true));
        maybeScrollToBottom(false);

        try {
            List<NewsItem> cachedNews = NewsRepository.getInstance().getCachedItems();

            // Cap context at the most recent HISTORY_CAP entries to keep
            // request latency flat in long conversations.
            JSONArray messages = new JSONArray();
            int start = Math.max(0, conversationHistory().length() - HISTORY_CAP);
            for (int i = start; i < conversationHistory().length(); i++)
                messages.put(conversationHistory().get(i));

            String enrichedQuery = userQuery + NewsContextBuilder.buildBrief(cachedNews);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user"); userMsg.put("content", enrichedQuery);
            messages.put(userMsg);

            String dataSnapshot = constructEconomicContext();

            // Settings: response length preference
            String lengthRule = com.economic.dashboard.utils.SettingsManager.getBool(
                    requireContext(), com.economic.dashboard.utils.SettingsManager.KEY_DETAILED_AI, false)
                    ? "Detailed analysis is welcome — up to roughly 500 words when the question warrants it. "
                    : "Keep responses under 200 words unless the user explicitly asks for detail. ";
            String newsContext = NewsContextBuilder.build(cachedNews);

            String systemPrompt = "You are an AI Economic Analyst embedded in the U.S. Economic Monitor app. "
                    + "You have access to the following live data pulled directly from the app, "
                    + "plus 24 months of locally cached historical data (treasury yields, mortgage "
                    + "rates, unemployment, GDP). Always use these exact figures when answering "
                    + "questions — never substitute general knowledge for values that appear in "
                    + "the data below. Use the historical series to discuss trends, compare current "
                    + "values to past readings, and answer questions about specific past months. "
                    + "If a value is listed as 'Unavailable' it has not yet been fetched.\n\n"
                    + "FORMATTING RULES: Do NOT use markdown headers (##, ###, or # prefix). "
                    + "Do NOT use bullet lists with dashes or asterisks. "
                    + "Use short paragraphs and bold text (**word**) only for key figures or labels. "
                    + lengthRule
                    + "When news headlines are provided, weave relevant recent developments into your analysis where appropriate.\n\n"
                    + dataSnapshot + historicalContext + newsContext;

            JSONObject body = new JSONObject();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 1024);
            body.put("stream", true);
            body.put("system", systemPrompt);
            body.put("messages", messages);

            // Calls the proxy (see proxy/README.md) — the Anthropic key stays
            // server-side and is never shipped inside the APK.
            Request request = new Request.Builder()
                    .url(ApiConfig.PROXY_BASE_URL + "/v1/messages")
                    .addHeader("x-app-token", ApiConfig.PROXY_APP_TOKEN)
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                    .build();

            final String committedQuery = userQuery;
            final AtomicBoolean retried = new AtomicBoolean(false);

            httpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    // Mobile networks flake — retry once automatically before surfacing.
                    if (retried.compareAndSet(false, true)) {
                        final Callback self = this;
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> httpClient().newCall(request).enqueue(self), 1500);
                        return;
                    }
                    runOnUi(() -> {
                        requestFinished();
                        chatAdapter().removeTypingIndicator();
                        chatAdapter().addMessage(new ChatMessage("Connection failed — tap to retry.", false, false, true));
                        maybeScrollToBottom(false);
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response r = response) {
                        String contentType = r.header("content-type", "");
                        if (r.isSuccessful() && contentType != null && contentType.contains("text/event-stream")) {
                            handleStreamedResponse(r, committedQuery);
                        } else if (r.isSuccessful()) {
                            handleJsonResponse(r, committedQuery);
                        } else {
                            runOnUi(() -> {
                                requestFinished();
                                chatAdapter().removeTypingIndicator();
                                chatAdapter().addMessage(new ChatMessage("API error " + r.code() + " — tap to retry.", false, false, true));
                                maybeScrollToBottom(false);
                            });
                        }
                    } catch (Exception e) {
                        runOnUi(() -> {
                            requestFinished();
                            chatAdapter().removeTypingIndicator();
                            chatAdapter().addMessage(new ChatMessage("Failed to parse response — tap to retry.", false, false, true));
                            maybeScrollToBottom(false);
                        });
                    }
                }
            });
        } catch (Exception e) {
            requestFinished();
            chatAdapter().removeTypingIndicator();
            chatAdapter().addMessage(new ChatMessage("Error: " + e.getMessage(), false));
        }
    }

    /** Reads Anthropic SSE events, appending text deltas to a live bubble. */
    private void handleStreamedResponse(Response r, String committedQuery) throws Exception {
        if (r.body() == null) throw new IOException("empty body");
        okio.BufferedSource source = r.body().source();
        StringBuilder acc = new StringBuilder();
        final int[] streamIdx = {-1};

        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            JSONObject evt = new JSONObject(payload);
            String type = evt.optString("type");
            if ("content_block_delta".equals(type)) {
                JSONObject delta = evt.optJSONObject("delta");
                String text = delta != null ? delta.optString("text", "") : "";
                if (!text.isEmpty()) {
                    acc.append(text);
                    final String partial = acc.toString();
                    runOnUi(() -> {
                        if (binding == null) return;
                        if (streamIdx[0] < 0) {
                            chatAdapter().removeTypingIndicator();
                            chatAdapter().addMessage(new ChatMessage(partial, false));
                            streamIdx[0] = chatAdapter().getLastIndex();
                        } else {
                            chatAdapter().updateMessageText(streamIdx[0], partial);
                        }
                        maybeScrollToBottom(false);
                    });
                }
            } else if ("message_stop".equals(type)) {
                break;
            } else if ("error".equals(type)) {
                throw new IOException("stream error");
            }
        }

        final String finalText = acc.toString();
        if (finalText.isEmpty()) throw new IOException("empty stream");

        commitToHistory(committedQuery, finalText);
        ChatMessage persisted = new ChatMessage(finalText, false);
        runOnUi(() -> {
            requestFinished();
            // If no delta ever arrived on-screen (edge case), add the bubble now.
            if (streamIdx[0] < 0) {
                chatAdapter().removeTypingIndicator();
                chatAdapter().addMessage(persisted);
            }
            host().persistChatMessage(persisted);
            maybeScrollToBottom(false);
        });
    }

    /**
     * Fallback path for responses not labeled text/event-stream.
     * Older proxy deployments forward Anthropic's SSE stream but stamp it
     * "application/json", so sniff the body: JSON object → parse normally;
     * SSE lines → accumulate the text deltas from the raw string.
     */
    private void handleJsonResponse(Response r, String committedQuery) throws Exception {
        String responseBody = r.body() != null ? r.body().string() : "";
        String trimmed = responseBody.trim();
        String text;
        if (trimmed.startsWith("{")) {
            JSONObject json = new JSONObject(trimmed);
            text = json.getJSONArray("content").getJSONObject(0).getString("text");
        } else if (trimmed.contains("data:")) {
            text = extractTextFromSseString(trimmed);
            if (text.isEmpty()) throw new IOException("empty SSE payload");
        } else {
            throw new IOException("unrecognized response body");
        }

        commitToHistory(committedQuery, text);
        ChatMessage msg = new ChatMessage(text, false);
        runOnUi(() -> {
            requestFinished();
            chatAdapter().removeTypingIndicator();
            chatAdapter().addMessage(msg);
            host().persistChatMessage(msg);
            maybeScrollToBottom(false);
        });
    }

    /** Joins all text deltas from a complete SSE payload delivered as one string. */
    private static String extractTextFromSseString(String body) {
        StringBuilder acc = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            try {
                JSONObject evt = new JSONObject(payload);
                if ("content_block_delta".equals(evt.optString("type"))) {
                    JSONObject delta = evt.optJSONObject("delta");
                    if (delta != null) acc.append(delta.optString("text", ""));
                }
            } catch (Exception ignored) {}
        }
        return acc.toString();
    }

    private void commitToHistory(String userQuery, String assistantText) throws Exception {
        JSONObject committedUser = new JSONObject();
        committedUser.put("role", "user"); committedUser.put("content", userQuery);
        conversationHistory().put(committedUser);

        JSONObject assistantMsg = new JSONObject();
        assistantMsg.put("role", "assistant"); assistantMsg.put("content", assistantText);
        conversationHistory().put(assistantMsg);
    }

    private String constructEconomicContext() {
        EconomicViewModel vm = host().getViewModel();
        StringBuilder sb = new StringBuilder();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).format(new Date());
        sb.append("=== U.S. ECONOMIC MONITOR — LIVE DATA SNAPSHOT ===\n").append("As of: ").append(timestamp).append("\n\n");

        sb.append("CORE INDICATORS\n");
        EconomicDataPoint fedFunds = EconomicViewModel.getLatest(vm.getFedFundsData().getValue(), "Federal Funds Effective Rate");
        sb.append("Fed Funds Rate: ").append(fedFunds != null ? String.format(Locale.US, "%.2f%% (%s)", fedFunds.getValue(), fedFunds.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> gdpList = vm.getGdpData().getValue();
        List<EconomicDataPoint> gdpRows = gdpList != null ? EconomicViewModel.filterBySeries(gdpList, "Gross domestic product") : null;
        if (gdpRows != null && !gdpRows.isEmpty()) {
            int start = Math.max(0, gdpRows.size()-4); double sum = 0;
            for (int i = start; i < gdpRows.size(); i++) sum += gdpRows.get(i).getValue();
            sb.append(String.format(Locale.US, "GDP Growth (4Q Avg): %.2f%%\n", sum/(gdpRows.size()-start)));
            EconomicDataPoint latestGdp = gdpRows.get(gdpRows.size()-1);
            String qLabel = vm.getLatestQuarterLabel().getValue();
            sb.append(String.format(Locale.US, "GDP Growth (Latest Quarter): %.2f%% (%s)\n", latestGdp.getValue(), qLabel != null ? qLabel : latestGdp.getDate()));
        } else { sb.append("GDP Growth (4Q Avg): Unavailable\nGDP Growth (Latest Quarter): Unavailable\n"); }

        List<EconomicDataPoint> cpiList = vm.getCpiData().getValue();
        List<EconomicDataPoint> cpiRows = cpiList != null ? EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") : null;
        if (cpiRows != null && cpiRows.size() >= 13) {
            double yoy = ((cpiRows.get(cpiRows.size()-1).getValue() - cpiRows.get(cpiRows.size()-13).getValue()) / cpiRows.get(cpiRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "CPI-U YoY: %.2f%% (%s)\n", yoy, cpiRows.get(cpiRows.size()-1).getDate()));
        } else { sb.append("CPI-U YoY: Unavailable\n"); }

        EconomicDataPoint unemp = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Unemployment Rate");
        sb.append("Unemployment Rate: ").append(unemp != null ? String.format(Locale.US, "%.1f%% (%s)", unemp.getValue(), unemp.getDate()) : "Unavailable").append("\n");

        EconomicDataPoint lfpr = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Labor Force Participation Rate");
        sb.append("Labor Force Participation: ").append(lfpr != null ? String.format(Locale.US, "%.1f%% (%s)", lfpr.getValue(), lfpr.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> pceList = vm.getPceData().getValue();
        List<EconomicDataPoint> pceRows = pceList != null ? EconomicViewModel.filterBySeries(pceList, "PCE Price Index") : null;
        if (pceRows != null && pceRows.size() >= 13) {
            double yoy = ((pceRows.get(pceRows.size()-1).getValue() - pceRows.get(pceRows.size()-13).getValue()) / pceRows.get(pceRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "PCE Inflation (YoY): %.2f%% (%s)\n", yoy, pceRows.get(pceRows.size()-1).getDate()));
        } else { sb.append("PCE Inflation (YoY): Unavailable\n"); }

        List<EconomicDataPoint> corePceRows = pceList != null ? EconomicViewModel.filterBySeries(pceList, "Core PCE Price Index") : null;
        if (corePceRows != null && corePceRows.size() >= 13) {
            double yoy = ((corePceRows.get(corePceRows.size()-1).getValue() - corePceRows.get(corePceRows.size()-13).getValue()) / corePceRows.get(corePceRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "Core PCE (YoY): %.2f%% (%s)\n", yoy, corePceRows.get(corePceRows.size()-1).getDate()));
        } else { sb.append("Core PCE (YoY): Unavailable\n"); }

        EconomicDataPoint pmi = EconomicViewModel.getLatest(vm.getIsmPmiData().getValue(), "NAPM");
        sb.append("ISM Manufacturing PMI: ").append(pmi != null ? String.format(Locale.US, "%.1f (%s)", pmi.getValue(), pmi.getDate()) : "Unavailable").append("\n");

        EconomicDataPoint hourly = EconomicViewModel.getLatest(vm.getWageData().getValue(), "Average Hourly Earnings - Private");
        sb.append("Avg Hourly Earnings: ").append(hourly != null ? String.format(Locale.US, "$%.2f (%s)", hourly.getValue(), hourly.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> wageList = vm.getWageData().getValue();
        List<EconomicDataPoint> wageRows = wageList != null ? EconomicViewModel.filterBySeries(wageList, "Average Hourly Earnings - Private") : null;
        if (wageRows != null && wageRows.size() >= 13 && cpiRows != null && cpiRows.size() >= 13) {
            double wYoy = ((wageRows.get(wageRows.size()-1).getValue() - wageRows.get(wageRows.size()-13).getValue()) / wageRows.get(wageRows.size()-13).getValue()) * 100.0;
            double cYoy = ((cpiRows.get(cpiRows.size()-1).getValue() - cpiRows.get(cpiRows.size()-13).getValue()) / cpiRows.get(cpiRows.size()-13).getValue()) * 100.0;
            sb.append(String.format(Locale.US, "Real Wage Growth (Wages-CPI spread): %.2f%%\n", wYoy - cYoy));
        } else { sb.append("Real Wage Growth (Wages-CPI spread): Unavailable\n"); }

        sb.append("\nTREASURY YIELD CURVE\n");
        List<EconomicDataPoint> treasuryList = vm.getTreasuryData().getValue();
        String[] maturities = {"1 Month","3 Month","6 Month","1 Year","2 Year","5 Year","10 Year","30 Year"};
        String[] shortLabels = {"1M","3M","6M","1Y","2Y","5Y","10Y","30Y"};
        if (treasuryList != null && !treasuryList.isEmpty()) {
            for (int i = 0; i < maturities.length; i++) {
                EconomicDataPoint yld = EconomicViewModel.getLatest(treasuryList, maturities[i]);
                sb.append(shortLabels[i]).append(": ").append(yld != null ? String.format(Locale.US, "%.2f%%", yld.getValue()) : "N/A");
                sb.append(i < maturities.length-1 ? "  " : "\n");
            }
        } else { sb.append("Unavailable\n"); }

        List<EconomicDataPoint> spread10y2y = vm.getCalculatedSpreadData().getValue();
        if (spread10y2y != null && !spread10y2y.isEmpty()) {
            EconomicDataPoint s = spread10y2y.get(spread10y2y.size()-1);
            sb.append(String.format(Locale.US, "10Y-2Y Spread: %.2f%%%s\n", s.getValue(), s.getValue() < 0 ? " (INVERTED)" : ""));
        } else { sb.append("10Y-2Y Spread: Unavailable\n"); }

        List<EconomicDataPoint> spread10y3m = vm.getCalculatedSpread3MData().getValue();
        if (spread10y3m != null && !spread10y3m.isEmpty()) {
            EconomicDataPoint s = spread10y3m.get(spread10y3m.size()-1);
            sb.append(String.format(Locale.US, "10Y-3M Spread: %.2f%%%s\n", s.getValue(), s.getValue() < 0 ? " (INVERTED)" : ""));
        } else { sb.append("10Y-3M Spread: Unavailable\n"); }

        sb.append("\nHOUSING\n");
        List<EconomicDataPoint> housingList = vm.getHousingData().getValue();
        EconomicDataPoint starts = housingList != null ? EconomicViewModel.getLatest(housingList, "Housing Starts") : null;
        sb.append("Housing Starts: ").append(starts != null ? String.format(Locale.US, "%.0f K units annualized (%s)", starts.getValue(), starts.getDate()) : "Unavailable").append("\n");
        EconomicDataPoint sales = housingList != null ? EconomicViewModel.getLatest(housingList, "Existing Home Sales") : null;
        sb.append("Existing Home Sales: ").append(sales != null ? String.format(Locale.US, "%.0f K units (%s)", sales.getValue(), sales.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> mbsList = vm.getMbsMortgageData().getValue();
        EconomicDataPoint mortgage = mbsList != null ? EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate") : null;
        sb.append("30-Yr Mortgage Rate: ").append(mortgage != null ? String.format(Locale.US, "%.2f%% (%s)", mortgage.getValue(), mortgage.getDate()) : "Unavailable").append("\n");

        sb.append("\nSTOCK MARKET INDICES\n");
        List<EconomicDataPoint> sp500List = vm.getSp500Data().getValue();
        EconomicDataPoint sp500 = sp500List != null && !sp500List.isEmpty() ? sp500List.get(sp500List.size()-1) : null;
        sb.append("S&P 500: ").append(sp500 != null ? String.format(Locale.US, "%.0f (%s)", sp500.getValue(), sp500.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> nasdaqList = vm.getNasdaqData().getValue();
        EconomicDataPoint nasdaq = nasdaqList != null && !nasdaqList.isEmpty() ? nasdaqList.get(nasdaqList.size()-1) : null;
        sb.append("Nasdaq: ").append(nasdaq != null ? String.format(Locale.US, "%.0f (%s)", nasdaq.getValue(), nasdaq.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> vixList = vm.getVixData().getValue();
        EconomicDataPoint vix = vixList != null && !vixList.isEmpty() ? vixList.get(vixList.size()-1) : null;
        sb.append("VIX: ").append(vix != null ? String.format(Locale.US, "%.2f (%s)", vix.getValue(), vix.getDate()) : "Unavailable").append("\n");

        sb.append("\nBOND MARKET SPREADS\n");
        List<EconomicDataPoint> baaList = vm.getBaaSpreadData().getValue();
        EconomicDataPoint baaSp = baaList != null && !baaList.isEmpty() ? baaList.get(baaList.size()-1) : null;
        sb.append("BAA Corporate Spread: ").append(baaSp != null ? String.format(Locale.US, "%.2f%% (%s)", baaSp.getValue(), baaSp.getDate()) : "Unavailable").append("\n");

        List<EconomicDataPoint> hyList = vm.getHySpreadData().getValue();
        EconomicDataPoint hySpread = hyList != null && !hyList.isEmpty() ? hyList.get(hyList.size()-1) : null;
        sb.append("High Yield Spread: ").append(hySpread != null ? String.format(Locale.US, "%.2f%% (%s)", hySpread.getValue(), hySpread.getDate()) : "Unavailable").append("\n");

        sb.append("\n");
        return sb.toString();
    }

    private String buildRecentUpdatesQuery() {
        EconomicViewModel vm = host().getViewModel();
        StringBuilder sb = new StringBuilder("Briefly summarise the most significant recent US economic developments based on the latest dashboard readings:");
        List<EconomicDataPoint> pceList = vm.getPceData().getValue();
        if (pceList != null) {
            List<EconomicDataPoint> pceRows = EconomicViewModel.filterBySeries(pceList, "PCE Price Index");
            if (pceRows.size() >= 13) {
                double yoy = ((pceRows.get(pceRows.size()-1).getValue() - pceRows.get(pceRows.size()-13).getValue()) / pceRows.get(pceRows.size()-13).getValue()) * 100.0;
                sb.append(String.format(Locale.US, " PCE inflation %.2f%% YoY (Fed target 2%%);", yoy));
            }
        }
        EconomicDataPoint unemp = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Unemployment Rate");
        if (unemp != null) sb.append(String.format(Locale.US, " unemployment %.1f%% (%s);", unemp.getValue(), unemp.getDate()));
        List<EconomicDataPoint> spread = vm.getCalculatedSpreadData().getValue();
        if (spread != null && !spread.isEmpty()) {
            double sv = spread.get(spread.size()-1).getValue();
            sb.append(String.format(Locale.US, " 10Y-2Y spread %.2f%%%s.", sv, sv < 0 ? " (inverted)" : ""));
        }
        sb.append(" Which of these readings stand out most, and what do they collectively signal about the near-term economic outlook?");
        return sb.toString();
    }

    private String buildRecentNewsQuery() {
        List<NewsItem> cached = NewsRepository.getInstance().getCachedItems();
        if (cached.isEmpty()) return "What are the most important recent developments in the US economy that I should be aware of right now?";
        StringBuilder sb = new StringBuilder("Based on the latest economic news headlines, what are the most significant stories right now and what do they signal for the economic outlook? Focus on the highest-impact items.");
        int count = 0;
        for (int tier = 2; tier >= 1 && count < 3; tier--) {
            for (NewsItem item : cached) {
                if (count >= 3) break;
                if (item.impactLevel == tier && item.title != null) {
                    sb.append(count == 0 ? " Recent headlines include: \"" : ", \"").append(item.title).append("\"");
                    count++;
                }
            }
        }
        if (count > 0) sb.append(".");
        return sb.toString();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
