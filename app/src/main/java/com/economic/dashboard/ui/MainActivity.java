package com.economic.dashboard.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.economic.dashboard.R;
import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.EconomicDataPoint;
import android.widget.ProgressBar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private EconomicViewModel viewModel;
    private EconomicPagerAdapter pagerAdapter;
    private ProgressBar progressBar;
    private ViewPager2 viewPager;
    private TabLayout tabLayout;

    private TextView tvEyebrow, tvHeaderBadgeValue, tvHeaderDate;
    private View headerBadge, btnAiAnalysis;

    // Claude AI - Increased timeouts to prevent "Error: timeout"
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = findViewById(R.id.progressBar);
        viewPager   = findViewById(R.id.viewPager);
        tabLayout   = findViewById(R.id.tabLayout);

        tvEyebrow          = findViewById(R.id.tvEyebrow);
        tvHeaderBadgeValue = findViewById(R.id.tvHeaderBadgeValue);
        tvHeaderDate       = findViewById(R.id.tvHeaderDate);
        headerBadge        = findViewById(R.id.header_badge);
        btnAiAnalysis      = findViewById(R.id.btnAiAnalysis);

        viewModel = new ViewModelProvider(this).get(EconomicViewModel.class);

        setupViewPager();
        updateEyebrow();
        observeViewModel();

        if (headerBadge != null) {
            headerBadge.setOnClickListener(v -> showFedFundsHistory());
        }

        if (btnAiAnalysis != null) {
            btnAiAnalysis.setOnClickListener(v -> showAiChat());
        }

        viewModel.fetchAllData();
    }

    private void showFedFundsHistory() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fed_funds_history, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        RecyclerView rv = dialogView.findViewById(R.id.rvFedFundsHistory);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            FedFundsHistoryAdapter adapter = new FedFundsHistoryAdapter();
            rv.setAdapter(adapter);

            viewModel.getFedFundsHistory().observe(this, data -> {
                if (data != null) {
                    adapter.setData(data);
                }
            });
        }

        View btnClose = dialogView.findViewById(R.id.btnClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void showAiChat() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_chat, null);
        
        // Use full screen dialog theme
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_EconomicDashboard_FullScreenDialog)
                .setView(dialogView)
                .create();

        // Ensure the dialog's window covers the full screen
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            );
        }

        RecyclerView rvChat = dialogView.findViewById(R.id.rvChat);
        EditText etMessage = dialogView.findViewById(R.id.etMessage);
        ImageButton btnSend = dialogView.findViewById(R.id.btnSend);
        View btnClose = dialogView.findViewById(R.id.btnClose);

        // Chip references
        View chipYieldCurve = dialogView.findViewById(R.id.chipYieldCurve);
        View chipRecession = dialogView.findViewById(R.id.chipRecession);
        View chipWages = dialogView.findViewById(R.id.chipWages);

        ChatAdapter adapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(adapter);

        adapter.addMessage(new ChatMessage("Hello! I am your Claude-powered Economic Analyst. Ask me anything about the data.", false));

        // Click listeners for chips
        if (chipYieldCurve != null) {
            chipYieldCurve.setOnClickListener(v -> etMessage.setText("Analyze the current yield curve health."));
        }
        if (chipRecession != null) {
            chipRecession.setOnClickListener(v -> etMessage.setText("What is the current recession risk based on this data?"));
        }
        if (chipWages != null) {
            chipWages.setOnClickListener(v -> etMessage.setText("What are the latest real wage trends?"));
        }

        btnSend.setOnClickListener(v -> {
            String userQuery = etMessage.getText().toString().trim();
            if (!userQuery.isEmpty()) {
                adapter.addMessage(new ChatMessage(userQuery, true));
                etMessage.setText("");
                rvChat.scrollToPosition(adapter.getItemCount() - 1);
                
                queryClaude(userQuery, adapter, rvChat);
            }
        });

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void queryClaude(String userQuery, ChatAdapter adapter, RecyclerView rv) {
        if (ApiConfig.ANTHROPIC_API_KEY.equals("YOUR_ANTHROPIC_API_KEY") || ApiConfig.ANTHROPIC_API_KEY.isEmpty()) {
            adapter.addMessage(new ChatMessage("Please provide a valid Anthropic API Key in your local.properties file (ANTHROPIC_API_KEY=your_key).", false));
            return;
        }

        String context = constructEconomicContext();
        String fullPrompt = "You are an expert economic analyst. Here is the current US economic data from the dashboard:\n" +
                context + "\n\nUser Question: " + userQuery + "\n\nPlease provide a concise analysis based on this data.";

        try {
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", fullPrompt);

            JSONArray messages = new JSONArray();
            messages.put(message);

            JSONObject body = new JSONObject();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 1024);
            body.put("messages", messages);

            Request request = new Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", ApiConfig.ANTHROPIC_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("Claude", "Query failed", e);
                    String errorDetails = e.getMessage() != null ? e.getMessage() : "Unknown connection error";
                    runOnUiThread(() -> {
                        adapter.addMessage(new ChatMessage("Error: " + errorDetails, false));
                        rv.smoothScrollToPosition(adapter.getItemCount() - 1);
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response r = response) {
                        String responseBody = r.body() != null ? r.body().string() : "";
                        if (r.isSuccessful()) {
                            JSONObject json = new JSONObject(responseBody);
                            String text = json.getJSONArray("content").getJSONObject(0).getString("text");
                            runOnUiThread(() -> {
                                adapter.addMessage(new ChatMessage(text, false));
                                rv.smoothScrollToPosition(adapter.getItemCount() - 1);
                            });
                        } else {
                            Log.e("Claude", "API error " + r.code() + ": " + responseBody);
                            String apiError = extractApiError(responseBody, r.code());
                            runOnUiThread(() -> {
                                adapter.addMessage(new ChatMessage(apiError, false));
                                rv.smoothScrollToPosition(adapter.getItemCount() - 1);
                            });
                        }
                    } catch (Exception e) {
                        Log.e("Claude", "Response parse failed", e);
                        runOnUiThread(() -> {
                            adapter.addMessage(new ChatMessage("Error parsing response.", false));
                            rv.smoothScrollToPosition(adapter.getItemCount() - 1);
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.e("Claude", "Request build failed", e);
            adapter.addMessage(new ChatMessage("Error building request: " + e.getMessage(), false));
        }
    }

    private String extractApiError(String responseBody, int code) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("error")) {
                JSONObject error = json.getJSONObject("error");
                String message = error.optString("message", "Unknown error");
                return "Claude API error (" + code + "): " + message;
            }
        } catch (Exception ignored) {}
        return "Claude API error (" + code + "). See Logcat for details.";
    }

    private String constructEconomicContext() {
        StringBuilder sb = new StringBuilder();
        
        EconomicDataPoint fedFunds = EconomicViewModel.getLatest(viewModel.getFedFundsData().getValue(), "Federal Funds Effective Rate");
        if (fedFunds != null) sb.append("- Fed Funds Rate: ").append(fedFunds.getValue()).append("%\n");
        
        EconomicDataPoint gdp = EconomicViewModel.getLatest(viewModel.getGdpData().getValue(), "Gross domestic product");
        if (gdp != null) sb.append("- GDP Growth: ").append(gdp.getValue()).append("% (").append(gdp.getDate()).append(")\n");
        
        EconomicDataPoint unemp = EconomicViewModel.getLatest(viewModel.getEmploymentData().getValue(), "Unemployment Rate");
        if (unemp != null) sb.append("- Unemployment Rate: ").append(unemp.getValue()).append("%\n");
        
        EconomicDataPoint cpi = EconomicViewModel.getLatest(viewModel.getCpiData().getValue(), "CPI-U All Items");
        if (cpi != null) sb.append("- CPI Index: ").append(cpi.getValue()).append("\n");

        List<EconomicDataPoint> spreadData = viewModel.getCalculatedSpreadData().getValue();
        if (spreadData != null && !spreadData.isEmpty()) {
            EconomicDataPoint latestSpread = spreadData.get(spreadData.size() - 1);
            sb.append("- 10Y-2Y Yield Spread: ").append(String.format(Locale.US, "%.2f%%", latestSpread.getValue())).append("\n");
        }

        return sb.toString();
    }

    private void setupViewPager() {
        pagerAdapter = new EconomicPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(5);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("Overview");   break;
                case 1: tab.setText("Treasury");   break;
                case 2: tab.setText("GDP");        break;
                case 3: tab.setText("Employment"); break;
                case 4: tab.setText("CPI");        break;
                case 5: tab.setText("Wages");      break;
            }
        }).attach();
    }

    private void updateEyebrow() {
        Calendar cal = Calendar.getInstance();
        int month = cal.get(Calendar.MONTH);
        int quarter = (month / 3) + 1;
        int year = cal.get(Calendar.YEAR);
        String text = String.format(Locale.US, "U.S. ECONOMIC MONITOR  ·  Q%d %d", quarter, year);
        if (tvEyebrow != null) tvEyebrow.setText(text);
    }

    private void observeViewModel() {
        viewModel.getIsLoading().observe(this, loading -> {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        });

        viewModel.getFedFundsData().observe(this, data -> {
            if (data != null) {
                EconomicDataPoint p = EconomicViewModel.getLatest(data, "Federal Funds Effective Rate");
                if (p != null && tvHeaderBadgeValue != null) {
                    tvHeaderBadgeValue.setText(String.format(Locale.US, "%.2f%%", p.getValue()));
                    updateTimestamp();
                }
            }
        });

        viewModel.getErrorMsg().observe(this, error -> {});
    }

    private void updateTimestamp() {
        if (tvHeaderDate != null) {
            String now = new SimpleDateFormat("MMM dd, yyyy  ·  hh:mm a z", Locale.US).format(new Date());
            tvHeaderDate.setText("Updated: " + now);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            viewModel.fetchAllData();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
