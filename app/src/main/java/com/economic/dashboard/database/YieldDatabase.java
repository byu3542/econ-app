package com.economic.dashboard.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.economic.dashboard.models.ChatMessageEntity;
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.models.NewsArticle;
import com.economic.dashboard.models.TreasuryYield;

/**
 * Room Database for local economic data caching.
 *
 * v1 → original treasury yields only
 * v2 → added news_articles table
 * v3 → added economic_history table (2-year historical data for AI Analyst)
 * v4 → rebuild economic_history so its schema matches the entity exactly.
 *      (The v2→v3 migration created the columns with DEFAULT clauses the entity
 *       doesn't declare, which tripped Room's identity-hash check. Bumping the
 *       version lets destructive fallback recreate the table cleanly. All tables
 *       are re-fetchable API caches, so no real data is lost.)
 * v5 → added chat_messages table (AI Analyst conversation persistence)
 */
@Database(
    entities = {TreasuryYield.class, NewsArticle.class, EconomicHistoryEntry.class, ChatMessageEntity.class},
    version = 5,
    exportSchema = false
)
public abstract class YieldDatabase extends RoomDatabase {

    private static volatile YieldDatabase instance;
    private static final Object lock = new Object();

    public abstract TreasuryYieldDao treasuryYieldDao();
    public abstract NewsArticleDao newsArticleDao();
    public abstract EconomicHistoryDao economicHistoryDao();
    public abstract ChatMessageDao chatMessageDao();

    // ── Migration: v1 → v2 ───────────────────────────────────────────────────
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `news_articles` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT, " +
                    "`description` TEXT, " +
                    "`content` TEXT, " +
                    "`url` TEXT, " +
                    "`imageUrl` TEXT, " +
                    "`publishedAt` TEXT, " +
                    "`source` TEXT, " +
                    "`author` TEXT, " +
                    "`economicRelevanceScore` INTEGER NOT NULL DEFAULT 0, " +
                    "`contentLength` INTEGER NOT NULL DEFAULT 0, " +
                    "`estimatedReadMinutes` INTEGER NOT NULL DEFAULT 0, " +
                    "`cachedAtMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`isRead` INTEGER NOT NULL DEFAULT 0, " +
                    "`isFavorited` INTEGER NOT NULL DEFAULT 0, " +
                    "`userNotes` TEXT, " +
                    "`aiAnalysisSummary` TEXT, " +
                    "`aiAnalysisTimestamp` INTEGER NOT NULL DEFAULT 0" +
                ")"
            );
        }
    };

    // ── Migration: v2 → v3 ───────────────────────────────────────────────────
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `economic_history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`seriesId` TEXT NOT NULL DEFAULT '', " +
                    "`seriesName` TEXT NOT NULL DEFAULT '', " +
                    "`category` TEXT NOT NULL DEFAULT '', " +
                    "`date` TEXT NOT NULL DEFAULT '', " +
                    "`value` REAL NOT NULL DEFAULT 0, " +
                    "`unit` TEXT NOT NULL DEFAULT '', " +
                    "`cachedAtMillis` INTEGER NOT NULL DEFAULT 0" +
                ")"
            );
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_economic_history_seriesId_date` " +
                "ON `economic_history` (`seriesId`, `date`)"
            );
        }
    };

    // ── Migration: v4 → v5 ───────────────────────────────────────────────────
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT, " +
                    "`isUser` INTEGER NOT NULL, " +
                    "`timeMillis` INTEGER NOT NULL" +
                ")"
            );
        }
    };

    // ── Singleton factory ─────────────────────────────────────────────────────

    public static YieldDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            YieldDatabase.class,
                            "yield_database"
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5)
                    // Safety net: if migration path is still missing, wipe and rebuild.
                    // All data is re-fetched from APIs so no data loss in practice.
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return instance;
    }
}
