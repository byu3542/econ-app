package com.economic.dashboard.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.news.NewsFragment;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.ui.fragments.DashboardFragment;
import com.economic.dashboard.ui.fragments.EconomyFragment;
import com.economic.dashboard.ui.fragments.MarketsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.widget.ProgressBar;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private EconomicViewModel viewModel;
    private ProgressBar progressBar;
    private TextView tvHeaderSub;
    private BottomNavigationView bottomNav;
    private View navActiveIndicator;

    ChatAdapter chatAdapter;
    final JSONArray conversationHistory = new JSONArray();
    String lastUserQuery = "";
    final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public ChatAdapter      getChatAdapter()          { return chatAdapter; }
    public JSONArray        getConversationHistory()  { return conversationHistory; }
    public OkHttpClient     getHttpClient()           { return httpClient; }
    public String           getLastUserQuery()        { return lastUserQuery; }
    public void             setLastUserQuery(String q){ lastUserQuery = q; }
    public EconomicViewModel getViewModel()           { return viewModel; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        progressBar = findViewById(R.id.progressBar);
        tvHeaderSub = findViewById(R.id.tvHeaderSub);
        bottomNav = findViewById(R.id.bottomNav);
        navActiveIndicator = findViewById(R.id.navActiveIndicator);

        viewModel = new ViewModelProvider(this).get(EconomicViewModel.class);

        chatAdapter = new ChatAdapter(this::retryLastQuery);
        chatAdapter.addMessage(new ChatMessage(
                "Hello! I am your AI Economic Analyst, powered by Claude. "
                + "Ask me anything about the US economic data.", false));

        setupBottomNav();
        updateHeader();
        observeViewModel();
        viewModel.fetchAllData();
        NewsRepository.getInstance().fetchAllFeedsIfStale();
    }

    private void setupBottomNav() {
        loadFragment(new DashboardFragment(), "overview");

        bottomNav.post(() -> {
            final int itemWidth = bottomNav.getWidth() / 5;
            final int indWidth  = (int)(itemWidth * 0.6f);
            final int indOffset = (int)(itemWidth * 0.2f);

            navActiveIndicator.getLayoutParams().width = indWidth;
            navActiveIndicator.requestLayout();
            navActiveIndicator.setTranslationX(indOffset);

            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                if (id == R.id.nav_ai_analyst) {
                    if (getSupportFragmentManager().findFragmentByTag(AiAnalystBottomSheet.TAG) == null)
                        new AiAnalystBottomSheet().show(getSupportFragmentManager(), AiAnalystBottomSheet.TAG);
                    return false;
                }

                int index = 0;
                if (id == R.id.nav_markets) { index = 1; loadFragment(new MarketsFragment(), "markets"); }
                else if (id == R.id.nav_economy) { index = 2; loadFragment(new EconomyFragment(), "economy"); }
                else if (id == R.id.navigation_news) { index = 3; loadFragment(new NewsFragment(), "news"); }
                else { loadFragment(new DashboardFragment(), "overview"); }

                navActiveIndicator.animate()
                        .translationX(index * itemWidth + indOffset)
                        .setDuration(200)
                        .setInterpolator(new FastOutSlowInInterpolator())
                        .start();

                return true;
            });
        });
    }

    private void loadFragment(Fragment fragment, String tag) {
        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        if (existing != null && existing.isAdded()) return;
        getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, fragment, tag).commit();
    }

    private void retryLastQuery() {}

    public void showFedFundsHistory() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fed_funds_history, null);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        RecyclerView rv = dialogView.findViewById(R.id.rvFedFundsHistory);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            FedFundsHistoryAdapter adapter = new FedFundsHistoryAdapter();
            rv.setAdapter(adapter);
            viewModel.getFedFundsHistory().observe(this, data -> { if (data != null) adapter.setData(data); });
        }
        View btnClose = dialogView.findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void observeViewModel() {
        viewModel.getIsLoading().observe(this, loading ->
                progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));
        viewModel.getFedFundsData().observe(this, data -> { if (data != null) updateHeader(); });
        viewModel.getErrorMsg().observe(this, error -> {
            if (error != null && !error.isEmpty()) {
                android.util.Log.w("MainActivity", "Data fetch error: " + error);
            }
        });
    }

    private void updateHeader() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month = cal.get(java.util.Calendar.MONTH), year = cal.get(java.util.Calendar.YEAR);
        int quarter = month <= 2 ? 1 : month <= 5 ? 2 : month <= 8 ? 3 : 4;
        TextView tvQuarter = findViewById(R.id.tvHeaderQuarter);
        if (tvQuarter != null) tvQuarter.setText("Q" + quarter + " " + year);
        // Cache status indicator: show real cache age, not the wall clock
        if (tvHeaderSub != null) {
            CacheManager.getStatus(this, status ->
                    runOnUiThread(() -> tvHeaderSub.setText(status.toDisplayString())));
        }
    }

    @Override
    protected void onResume() { super.onResume(); updateHeader(); }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.main_menu, menu); return true; }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            // Refresh live dashboard data AND force-refresh the local cache
            android.widget.Toast.makeText(this, R.string.refreshing_data,
                    android.widget.Toast.LENGTH_SHORT).show();
            viewModel.fetchAllData();
            CacheManager.forceRefreshAll(this, success ->
                    runOnUiThread(this::updateHeader));
            return true;
        }
        if (item.getItemId() == R.id.action_clear_cache) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.clear_cache)
                    .setMessage(R.string.clear_cache_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            CacheManager.clearAllCaches(this, success ->
                                    runOnUiThread(() -> {
                                        android.widget.Toast.makeText(this,
                                                R.string.cache_cleared,
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        updateHeader();
                                    })))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
