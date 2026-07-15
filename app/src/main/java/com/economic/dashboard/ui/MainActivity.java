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
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.databinding.ActivityMainBinding;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.ChatMessageEntity;
import com.economic.dashboard.news.NewsFragment;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.ui.fragments.DashboardFragment;
import com.economic.dashboard.ui.fragments.EconomyFragment;
import com.economic.dashboard.ui.fragments.MarketsFragment;
import com.economic.dashboard.utils.AppExecutors;
import com.economic.dashboard.utils.SettingsManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.widget.ProgressBar;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private static final String WELCOME_TEXT =
            "Hello! I am your AI Economic Analyst, powered by Claude. "
            + "I can see all of the dashboard's live data — ask me anything about it. "
            + "Tip: long-press any card on the Overview screen to ask about that metric.";
    /** Newest rows kept in the persisted chat table. */
    private static final int CHAT_PERSIST_LIMIT = 200;

    private EconomicViewModel viewModel;
    private ActivityMainBinding binding;

    /** True only on a fresh launch (not on theme-change/rotation recreates) —
        gates "open to default tab" and "refresh cache on open". */
    private boolean freshLaunch = false;

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
        freshLaunch = (savedInstanceState == null);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Edge-to-edge: draw behind system bars, then pad the header and
        // bottom nav so content clears them (required on Android 15+).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            binding.headerContainer.setPadding(0, bars.top, 0, 0);
            binding.bottomNavWrapper.setPadding(0, 0, 0, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        viewModel = new ViewModelProvider(this).get(EconomicViewModel.class);

        chatAdapter = new ChatAdapter(this::retryLastQuery);
        loadPersistedChat();

        setupBottomNav();

        binding.fabAiAnalyst.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(AiAnalystBottomSheet.TAG) == null)
                new AiAnalystBottomSheet().show(getSupportFragmentManager(), AiAnalystBottomSheet.TAG);
        });
        binding.btnSettings.setOnClickListener(v -> showSettingsSheet());
        updateHeader();
        observeViewModel();
        viewModel.fetchAllData();
        if (freshLaunch && SettingsManager.getBool(this, SettingsManager.KEY_REFRESH_ON_OPEN, false)) {
            CacheManager.forceRefreshAll(this, success -> runOnUiThread(this::updateHeader));
        }
        NewsRepository.getInstance().fetchAllFeedsIfStale();
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /** Gear icon in the header — opens the settings bottom sheet. */
    private void showSettingsSheet() {
        if (getSupportFragmentManager().findFragmentByTag(SettingsBottomSheet.TAG) == null)
            new SettingsBottomSheet().show(getSupportFragmentManager(), SettingsBottomSheet.TAG);
    }

    /** Asks for the Android 13+ notification permission if not yet granted. */
    public void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
        }
    }

    /** If the user denies the notification prompt, turn the toggles back off. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1001) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            SettingsManager.setBool(this, SettingsManager.KEY_NOTIFY_BIG_MOVES, false);
            SettingsManager.setBool(this, SettingsManager.KEY_NOTIFY_RELEASES, false);
            android.widget.Toast.makeText(this, R.string.notifications_denied,
                    android.widget.Toast.LENGTH_LONG).show();
            androidx.fragment.app.Fragment f =
                    getSupportFragmentManager().findFragmentByTag(SettingsBottomSheet.TAG);
            if (f instanceof SettingsBottomSheet)
                ((SettingsBottomSheet) f).onNotificationPermissionDenied();
        }
    }

    // ── Chat persistence ─────────────────────────────────────────────────────

    /** Restores the saved conversation; falls back to the welcome message. */
    private void loadPersistedChat() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            List<ChatMessageEntity> saved =
                    YieldDatabase.getInstance(this).chatMessageDao().getAll();
            List<ChatMessage> restored = new ArrayList<>();
            for (ChatMessageEntity e : saved) restored.add(e.toChatMessage());
            runOnUiThread(() -> {
                if (restored.isEmpty()) {
                    chatAdapter.addMessage(new ChatMessage(WELCOME_TEXT, false));
                } else {
                    chatAdapter.setMessages(restored);
                    rebuildConversationHistory(restored);
                }
            });
        });
    }

    /**
     * Rebuilds the Claude-facing history from restored messages.
     * Only complete user→assistant pairs are kept — the API requires
     * strictly alternating roles, and a failed request can leave an
     * unanswered user message in the persisted log.
     */
    private void rebuildConversationHistory(List<ChatMessage> restored) {
        try {
            while (conversationHistory.length() > 0) conversationHistory.remove(0);
            for (int i = 0; i < restored.size() - 1; i++) {
                ChatMessage q = restored.get(i), a = restored.get(i + 1);
                if (q.isUser() && !a.isUser()) {
                    JSONObject user = new JSONObject();
                    user.put("role", "user"); user.put("content", q.getText());
                    conversationHistory.put(user);
                    JSONObject assistant = new JSONObject();
                    assistant.put("role", "assistant"); assistant.put("content", a.getText());
                    conversationHistory.put(assistant);
                    i++;
                }
            }
        } catch (Exception ignored) {}
    }

    /** Saves a message so the conversation survives app restarts. */
    public void persistChatMessage(ChatMessage m) {
        if (m == null || m.isTyping() || m.isError()) return;
        AppExecutors.getInstance().diskIO().execute(() -> {
            YieldDatabase db = YieldDatabase.getInstance(this);
            db.chatMessageDao().insert(ChatMessageEntity.from(m));
            db.chatMessageDao().trim(CHAT_PERSIST_LIMIT);
        });
    }

    /** Wipes the stored conversation and resets the visible chat. */
    public void clearChatHistory() {
        AppExecutors.getInstance().diskIO().execute(() ->
                YieldDatabase.getInstance(this).chatMessageDao().clearAll());
        while (conversationHistory.length() > 0) conversationHistory.remove(0);
        lastUserQuery = "";
        chatAdapter.setMessages(new ArrayList<>());
        chatAdapter.addMessage(new ChatMessage(WELCOME_TEXT, false));
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void setupBottomNav() {
        loadFragment(new DashboardFragment(), "overview");

        binding.bottomNav.post(() -> {
            final int itemWidth = binding.bottomNav.getWidth() / 5;
            final int indWidth  = (int)(itemWidth * 0.6f);
            final int indOffset = (int)(itemWidth * 0.2f);

            binding.navActiveIndicator.getLayoutParams().width = indWidth;
            binding.navActiveIndicator.requestLayout();
            binding.navActiveIndicator.setTranslationX(indOffset);

            binding.bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                // Center slot (nav_ai_placeholder) is disabled — the floating
                // fabAiAnalyst button handles the AI Analyst instead.
                int index = 0;
                String title = "Overview";
                if (id == R.id.nav_markets) { index = 1; title = "Markets"; loadFragment(new MarketsFragment(), "markets"); }
                else if (id == R.id.nav_economy) { index = 3; title = "Economy"; loadFragment(new EconomyFragment(), "economy"); }
                else if (id == R.id.navigation_news) { index = 4; title = "News"; loadFragment(new NewsFragment(), "news"); }
                else { loadFragment(new DashboardFragment(), "overview"); }
                binding.tvHeaderLine1.setText(title);

                binding.navActiveIndicator.animate()
                        .translationX(index * itemWidth + indOffset)
                        .setDuration(200)
                        .setInterpolator(new FastOutSlowInInterpolator())
                        .start();

                return true;
            });

            // Open to the user's preferred tab (fresh launches only, so a
            // theme change or rotation doesn't yank the user off their screen)
            if (freshLaunch) {
                freshLaunch = false;
                int defTab = SettingsManager.getDefaultTab(MainActivity.this);
                if (defTab == 1)      binding.bottomNav.setSelectedItemId(R.id.nav_markets);
                else if (defTab == 2) binding.bottomNav.setSelectedItemId(R.id.nav_economy);
                else if (defTab == 3) binding.bottomNav.setSelectedItemId(R.id.navigation_news);
            }
        });
    }

    private void loadFragment(Fragment fragment, String tag) {
        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        if (existing != null && existing.isAdded()) return;
        getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, fragment, tag).commit();
    }

    /** Tap-to-retry on an error bubble — resends via the open analyst sheet. */
    private void retryLastQuery() {
        Fragment f = getSupportFragmentManager().findFragmentByTag(AiAnalystBottomSheet.TAG);
        if (f instanceof AiAnalystBottomSheet && f.isAdded())
            ((AiAnalystBottomSheet) f).resendLastQuery();
    }

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
                binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));
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
        TextView tvQuarter = binding.tvHeaderQuarter;
        if (tvQuarter != null) tvQuarter.setText("Q" + quarter + " " + year);
        // Cache status indicator: show real cache age, not the wall clock
        CacheManager.getStatus(this, status ->
                runOnUiThread(() -> binding.tvHeaderSub.setText(status.toDisplayString())));
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
