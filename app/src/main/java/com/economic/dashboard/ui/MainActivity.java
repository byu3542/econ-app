package com.economic.dashboard.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.economic.dashboard.R;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.news.NewsFragment;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.ui.fragments.DashboardFragment;
import com.economic.dashboard.ui.fragments.EconomyFragment;
import com.economic.dashboard.ui.fragments.TreasuryFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.widget.ProgressBar;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    // ─── ViewModel ─────────────────────────────────────────────────────────────
    private EconomicViewModel viewModel;

    // ─── Header views ───────────────────────────────────────────────────────────
    private ProgressBar progressBar;
    private TextView tvHeaderSub;

    // ─── Bottom navigation ──────────────────────────────────────────────────────
    private BottomNavigationView bottomNav;
    private View navActiveIndicator;

    // ─── AI chat state — lives here so it persists across bottom sheet open/close
    // Package-private so AiAnalystBottomSheet can access them directly.
    ChatAdapter   chatAdapter;
    final JSONArray conversationHistory = new JSONArray();
    String lastUserQuery = "";
    final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // ─── Public accessors used by AiAnalystBottomSheet ──────────────────────────
    public ChatAdapter      getChatAdapter()          { return chatAdapter; }
    public JSONArray        getConversationHistory()  { return conversationHistory; }
    public OkHttpClient     getHttpClient()           { return httpClient; }
    public String           getLastUserQuery()        { return lastUserQuery; }
    public void             setLastUserQuery(String q){ lastUserQuery = q; }
    public EconomicViewModel getViewModel()           { return viewModel; }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Hide the default ActionBar — we use a custom header
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Header views
        progressBar  = findViewById(R.id.progressBar);
        tvHeaderSub = findViewById(R.id.tvHeaderSub);

        // Bottom nav views
        bottomNav          = findViewById(R.id.bottomNav);
        navActiveIndicator = findViewById(R.id.navActiveIndicator);

        viewModel = new ViewModelProvider(this).get(EconomicViewModel.class);

        // Initialise chat adapter once — persists across bottom sheet open/close
        chatAdapter = new ChatAdapter(this::retryLastQuery);
        chatAdapter.addMessage(new ChatMessage(
                "Hello! I am your AI Economic Analyst, powered by Claude. "
                + "Ask me anything about the US economic data.", false));

        setupBottomNav();
        updateHeader();
        observeViewModel();

        viewModel.fetchAllData();

        // Warm the news cache in the background so AI Analyst has context immediately
        NewsRepository.getInstance().fetchAllFeedsIfStale();
    }

    // ─── Bottom navigation setup ────────────────────────────────────────────────

    private void setupBottomNav() {
        // Load the default destination immediately (before post fires)
        loadFragment(new DashboardFragment(), "overview");

        // Everything else runs after the nav bar is fully measured so that
        // getWidth() returns the real pixel value, requestLayout() sticks,
        // and the listener captures the correct itemWidth in its closure.
        bottomNav.post(() -> {
            final int itemWidth  = bottomNav.getWidth() / 5;
            // Indicator is 60% of item width, inset 20% from each side (matches mockup)
            final int indWidth   = (int) (itemWidth * 0.6f);
            final int indOffset  = (int) (itemWidth * 0.2f);   // 20% left inset

            // Snap indicator to Overview (index 0) on first load
            navActiveIndicator.getLayoutParams().width = indWidth;
            navActiveIndicator.requestLayout();
            navActiveIndicator.setTranslationX(indOffset);

            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                // AI Analyst — show bottom sheet, keep previous item selected
                if (id == R.id.nav_ai_analyst) {
                    if (getSupportFragmentManager()
                            .findFragmentByTag(AiAnalystBottomSheet.TAG) == null) {
                        new AiAnalystBottomSheet().show(
                                getSupportFragmentManager(), AiAnalystBottomSheet.TAG);
                    }
                    return false;
                }

                // Determine indicator index and load the correct fragment
                int index = 0;
                if (id == R.id.nav_markets) {
                    index = 1;
                    loadFragment(new TreasuryFragment(), "markets");
                } else if (id == R.id.nav_economy) {
                    index = 2;
                    loadFragment(new EconomyFragment(), "economy");
                } else if (id == R.id.navigation_news) {
                    index = 3;
                    loadFragment(new NewsFragment(), "news");
                } else {
                    // nav_overview (default)
                    loadFragment(new DashboardFragment(), "overview");
                }

                // Animate the gold line to the active item, preserving the 20% inset
                navActiveIndicator.animate()
                        .translationX(index * itemWidth + indOffset)
                        .setDuration(200)
                        .setInterpolator(new FastOutSlowInInterpolator())
                        .start();

                return true;
            });
        });
    }

    /** Replace the fragment container, skipping re-add if same tag is already showing. */
    private void loadFragment(Fragment fragment, String tag) {
        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        if (existing != null && existing.isAdded()) return; // already showing
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, fragment, tag)
                .commit();
    }

/** Called by ChatAdapter retry button — delegates to the open bottom sheet. */
    private void retryLastQuery() {
        AiAnalystBottomSheet sheet = (AiAnalystBottomSheet)
                getSupportFragmentManager().findFragmentByTag(AiAnalystBottomSheet.TAG);
        // Sheet handles its own retry via lastUserQuery stored in MainActivity
        // If sheet is not open, nothing to retry
    }

    // ─── Fed Funds history dialog — unchanged ───────────────────────────────────

    public void showFedFundsHistory() {
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
                if (data != null) adapter.setData(data);
            });
        }

        View btnClose = dialogView.findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ─── ViewModel observation — unchanged ──────────────────────────────────────

    private void observeViewModel() {
        viewModel.getIsLoading().observe(this, loading ->
                progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));

        viewModel.getFedFundsData().observe(this, data -> {
            if (data != null) updateHeader();
        });

        viewModel.getErrorMsg().observe(this, error -> {});
    }

    private void updateHeader() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month = cal.get(java.util.Calendar.MONTH);
        int year = cal.get(java.util.Calendar.YEAR);
        int quarter;
        if (month <= 2) quarter = 1;
        else if (month <= 5) quarter = 2;
        else if (month <= 8) quarter = 3;
        else quarter = 4;
        String quarterLabel = "Q" + quarter + " " + year;
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, hh:mm a", Locale.US);
        String dateStr = fmt.format(new Date());
        TextView tvQuarter = findViewById(R.id.tvHeaderQuarter);
        if (tvQuarter != null) {
            tvQuarter.setText(quarterLabel);
        }
        if (tvHeaderSub != null) {
            tvHeaderSub.setText("Updated " + dateStr);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHeader();
    }

    // ─── Options menu (refresh) — unchanged ─────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            viewModel.fetchAllData();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
