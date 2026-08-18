package com.economic.dashboard.workers

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.economic.dashboard.analyst.AnalystIdentity
import com.economic.dashboard.analyst.HistoricalContextBuilder
import com.economic.dashboard.analyst.ModelConfig
import com.economic.dashboard.analyst.NewsContextBuilder
import com.economic.dashboard.analyst.ReleaseCalendar
import com.economic.dashboard.api.ApiConfig
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.ChatMessageEntity
import com.economic.dashboard.news.NewsItem
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.utils.NotificationHelper
import com.economic.dashboard.utils.SettingsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily 7:30 AM AI morning brief. Sends the cached historical data, upcoming
 * releases, and latest headlines to the AI Analyst (non-streaming) and posts
 * the one-paragraph summary as a notification. The brief is also written into
 * the chat history so it's waiting in the Analyst sheet when the app opens.
 *
 * Off by default — enabled via the "Daily AI morning brief" setting (it costs
 * one API call per day).
 */
class DailyBriefWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            if (!SettingsManager.getBool(ctx, SettingsManager.KEY_DAILY_BRIEF, false))
                return Result.success()
            if (ApiConfig.PROXY_BASE_URL.isEmpty())
                return Result.success()

            // One brief per calendar day, even if WorkManager reschedules oddly.
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val already = SettingsManager.getString(ctx, SettingsManager.KEY_LAST_BRIEF_DATE, "")
            if (today == already) return Result.success()

            // TICKET-P2-7 (AI Law 29): suppression rule — a brief with no
            // material change does not fire. Material = the historical DATA
            // block; headlines are deliberately excluded because they churn
            // every day and would defeat suppression. The block is built from
            // cached data rows only (no "today" timestamp), so its hash is
            // stable across a morning where nothing new was released.
            // Suppression does NOT stamp KEY_LAST_BRIEF_DATE, so a same-day
            // rerun can still fire if data lands later.
            val historicalBlock = HistoricalContextBuilder.build(ctx)
            val fingerprint = sha256(historicalBlock)
            val lastFingerprint = SettingsManager.getString(
                ctx, SettingsManager.KEY_LAST_BRIEF_FINGERPRINT, "")
            if (fingerprint == lastFingerprint) {
                Log.i(TAG, "Daily brief suppressed: no material data change since last brief")
                return Result.success()
            }

            // Fresh headlines (synchronous; returns cache on failure)
            val news: List<NewsItem> = try {
                NewsRepository.getInstance().fetchAllFeeds(false)
            } catch (e: Exception) {
                NewsRepository.getInstance().cachedItems
            }

            // TICKET-12 (End): a crisp brief that reads as headline + movers.
            val systemPrompt = "You are the Econ Analyst writing a short morning brief " +
                "for the U.S. Economic Monitor app. Using the historical data, upcoming " +
                "releases, and headlines below, write the brief in this shape:\n" +
                "- First line: a punchy headline of at most 8 words, no period.\n" +
                "- Then 2 to 3 short lines, each naming one key mover and its latest " +
                "number (e.g. '10Y Treasury 4.33%, +6bp today').\n" +
                "- Then one closing sentence on what to watch.\n" +
                "Plain text only — no markdown, no bullet characters, no headers.\n\n" +
                historicalBlock +
                ReleaseCalendar.build() +
                NewsContextBuilder.build(news)

            val messages = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Write today's morning economic brief.")
            )

            val body = JSONObject()
            body.put("model", ModelConfig.MODEL_FAST)
            body.put("max_tokens", 512)
            body.put("system", systemPrompt)
            body.put("messages", messages)

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // Non-streamed request: nothing arrives until the whole brief is
                // generated, so the read timeout must cover full generation time,
                // not one inter-byte gap.
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(ApiConfig.PROXY_BASE_URL + "/v1/messages")
                .addHeader("x-app-token", ApiConfig.PROXY_APP_TOKEN)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var brief: String? = null
            client.newCall(request).execute().use { r ->
                val responseBody = r.body
                if (r.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody.string())
                    val content = json.getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if ("text" == block.optString("type")) sb.append(block.optString("text"))
                    }
                    brief = sb.toString().trim()
                }
            }
            val finalBrief = brief
            if (finalBrief.isNullOrEmpty()) {
                Log.w(TAG, "Empty brief response")
                return Result.retry()
            }

            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_BRIEF_DATE, today)
            SettingsManager.setString(ctx, SettingsManager.KEY_LAST_BRIEF_FINGERPRINT, fingerprint)

            // Use the model's first line as the notification headline and the
            // remaining movers as the expanded body — a polished "end" to the
            // day's loop rather than a wall of text under a generic title.
            var headline = finalBrief
            var detail = finalBrief
            val nl = finalBrief.indexOf('\n')
            if (nl > 0) {
                headline = finalBrief.substring(0, nl).trim()
                detail = finalBrief.substring(nl + 1).trim()
            }
            if (detail.isEmpty()) detail = finalBrief
            // TICKET-P2-8: the marker travels with AI-generated content —
            // the notification title and the persisted chat entry both carry it.
            NotificationHelper.notify(ctx, NOTIFICATION_ID_BRIEF,
                AnalystIdentity.MARKER + " " + headline, detail)

            // Drop the brief into the persisted chat so it's there on open.
            val entity = ChatMessageEntity.from(ChatMessage(
                AnalystIdentity.MARKER + " Morning brief — " + AnalystIdentity.NAME +
                    "\n\n" + finalBrief, false))
            YieldDatabase.getInstance(ctx).chatMessageDao().insert(entity)

            // TICKET-26: mark that a genuinely new analyst insight exists so the
            // Analyst tab can badge until the user opens it.
            SettingsManager.setAnalystLastInsight(ctx, System.currentTimeMillis())

            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Daily brief failed: " + e.message)
            // A timeout means the brief was never produced — retry (WorkManager
            // backoff bounds it) instead of silently dropping the day's brief.
            return if (e is java.net.SocketTimeoutException) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "DailyBriefWorker"
        private const val WORK_ID = "daily_ai_brief"
        private const val NOTIFICATION_ID_BRIEF = 2003

        /** Hex SHA-256 of the historical data block (TICKET-P2-7 fingerprint). */
        private fun sha256(s: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
            return sb.toString()
        }

        /** Schedules the daily brief at ~7:30 AM local. KEEP policy — idempotent. */
        @JvmStatic
        fun scheduleDaily(context: Context) {
            try {
                scheduleDailyAt(context, DailyBriefWorker::class.java, WORK_ID, 7, 30)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule daily brief: " + e.message)
            }
        }
    }
}
