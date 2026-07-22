package com.economic.dashboard.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.economic.dashboard.analyst.HistoricalContextBuilder;
import com.economic.dashboard.analyst.NewsContextBuilder;
import com.economic.dashboard.analyst.ReleaseCalendar;
import com.economic.dashboard.api.ApiConfig;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.ChatMessage;
import com.economic.dashboard.models.ChatMessageEntity;
import com.economic.dashboard.news.NewsItem;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.NotificationHelper;
import com.economic.dashboard.utils.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Daily 7:30 AM AI morning brief. Sends the cached historical data, upcoming
 * releases, and latest headlines to the AI Analyst (non-streaming) and posts
 * the one-paragraph summary as a notification. The brief is also written into
 * the chat history so it's waiting in the Analyst sheet when the app opens.
 *
 * Off by default — enabled via the "Daily AI morning brief" setting (it costs
 * one API call per day).
 */
public class DailyBriefWorker extends Worker {

    private static final String TAG = "DailyBriefWorker";
    private static final String WORK_ID = "daily_ai_brief";
    private static final int NOTIFICATION_ID_BRIEF = 2003;

    public DailyBriefWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            if (!SettingsManager.getBool(ctx, SettingsManager.KEY_DAILY_BRIEF, false))
                return Result.success();
            if (ApiConfig.PROXY_BASE_URL.isEmpty())
                return Result.success();

            // One brief per calendar day, even if WorkManager reschedules oddly.
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            String already = SettingsManager.getString(ctx, SettingsManager.KEY_LAST_BRIEF_DATE, "");
            if (today.equals(already)) return Result.success();

            // Fresh headlines (synchronous; returns cache on failure)
            List<NewsItem> news;
            try { news = NewsRepository.getInstance().fetchAllFeeds(false); }
            catch (Exception e) { news = NewsRepository.getInstance().getCachedItems(); }

            // TICKET-12 (End): a crisp brief that reads as headline + movers.
            String systemPrompt = "You are an AI Economic Analyst writing a short morning brief "
                    + "for the U.S. Economic Monitor app. Using the historical data, upcoming "
                    + "releases, and headlines below, write the brief in this shape:\n"
                    + "- First line: a punchy headline of at most 8 words, no period.\n"
                    + "- Then 2 to 3 short lines, each naming one key mover and its latest "
                    + "number (e.g. '10Y Treasury 4.33%, +6bp today').\n"
                    + "- Then one closing sentence on what to watch.\n"
                    + "Plain text only — no markdown, no bullet characters, no headers.\n\n"
                    + HistoricalContextBuilder.build(ctx)
                    + ReleaseCalendar.build()
                    + NewsContextBuilder.build(news);

            JSONArray messages = new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", "Write today's morning economic brief."));

            JSONObject body = new JSONObject();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 512);
            body.put("system", systemPrompt);
            body.put("messages", messages);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(ApiConfig.PROXY_BASE_URL + "/v1/messages")
                    .addHeader("x-app-token", ApiConfig.PROXY_APP_TOKEN)
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                    .build();

            String brief = null;
            try (Response r = client.newCall(request).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    JSONObject json = new JSONObject(r.body().string());
                    JSONArray content = json.getJSONArray("content");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject block = content.getJSONObject(i);
                        if ("text".equals(block.optString("type"))) sb.append(block.optString("text"));
                    }
                    brief = sb.toString().trim();
                }
            }
            if (brief == null || brief.isEmpty()) {
                Log.w(TAG, "Empty brief response");
                return Result.retry();
            }

            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_BRIEF_DATE, today);

            // Use the model's first line as the notification headline and the
            // remaining movers as the expanded body — a polished "end" to the
            // day's loop rather than a wall of text under a generic title.
            String headline = brief;
            String detail   = brief;
            int nl = brief.indexOf('\n');
            if (nl > 0) {
                headline = brief.substring(0, nl).trim();
                detail   = brief.substring(nl + 1).trim();
            }
            if (detail.isEmpty()) detail = brief;
            NotificationHelper.notify(ctx, NOTIFICATION_ID_BRIEF, headline, detail);

            // Drop the brief into the persisted chat so it's there on open.
            ChatMessageEntity entity = ChatMessageEntity.from(
                    new ChatMessage("☀️ Morning brief\n\n" + brief, false));
            YieldDatabase.getInstance(ctx).chatMessageDao().insert(entity);

            // TICKET-26: mark that a genuinely new analyst insight exists so the
            // Analyst tab can badge until the user opens it.
            SettingsManager.setAnalystLastInsight(ctx, System.currentTimeMillis());

            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Daily brief failed: " + e.getMessage());
            return Result.success();
        }
    }

    /** Schedules the daily brief at ~7:30 AM local. KEEP policy — idempotent. */
    public static void scheduleDaily(Context context) {
        try {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY, 7);
            next.set(Calendar.MINUTE, 30);
            next.set(Calendar.SECOND, 0);
            if (next.getTimeInMillis() <= System.currentTimeMillis())
                next.add(Calendar.DAY_OF_YEAR, 1);
            long delayMinutes = (next.getTimeInMillis() - System.currentTimeMillis()) / 60000L;

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    DailyBriefWorker.class, 1, TimeUnit.DAYS)
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_ID, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule daily brief: " + e.getMessage());
        }
    }
}
