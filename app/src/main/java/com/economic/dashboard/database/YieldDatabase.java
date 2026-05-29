package com.economic.dashboard.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

<<<<<<< Updated upstream
=======
import com.economic.dashboard.models.EconomicHistoryEntry;
import com.economic.dashboard.models.NewsArticle;
>>>>>>> Stashed changes
import com.economic.dashboard.models.TreasuryYield;

/**
 * Room Database for local economic data caching.
 * Singleton pattern ensures only one database instance exists.
 *
 * v1 → original treasury yields only
 * v2 → added news articles table
 * v3 → added economic_history table (2-year historical data for AI Analyst)
 */
<<<<<<< Updated upstream
@Database(entities = {TreasuryYield.class}, version = 1, exportSchema = false)
=======
@Database(
    entities = {TreasuryYield.class, NewsArticle.class, EconomicHistoryEntry.class},
    version = 3,
    exportSchema = false
)
>>>>>>> Stashed changes
public abstract class YieldDatabase extends RoomDatabase {

    private static volatile YieldDatabase instance;
    private static final Object lock = new Object();

    // ── DAOs ──────────────────────────────────────────────────────────────────

    /** Treasury yield curve snapshot (current rates) */
    public abstract TreasuryYieldDao treasuryYieldDao();

<<<<<<< Updated upstream
=======
    /** News articles cached from RSS / NewsAPI */
    public abstract NewsArticleDao newsArticleDao();

    /** 2-year historical data for AI Analyst context */
    public abstract EconomicHistoryDao economicHistoryDao();

    // ── Migration: v2 → v3 ───────────────────────────────────────────────────

    /**
     * Adds the economic_history table introduced in database version 3.
     * Preserves all existing rows in treasury_yields and news_articles.
     */
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

    // ── Singleton factory ─────────────────────────────────────────────────────

>>>>>>> Stashed changes
    /**
     * Get singleton instance of the database.
     * Uses double-checked locking for thread safety.
     *
     * @param context Application context
     * @return Singleton YieldDatabase instance
     */
    public static YieldDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            YieldDatabase.class,
                            "yield_database"
                    )
                    .addMigrations(MIGRATION_2_3)
                    .build();
                }
            }
        }
        return instance;
    }
}
