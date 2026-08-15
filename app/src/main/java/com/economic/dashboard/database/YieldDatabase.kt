package com.economic.dashboard.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.economic.dashboard.models.ChatMessageEntity
import com.economic.dashboard.models.EconomicHistoryEntry
import com.economic.dashboard.models.NewsArticle
import com.economic.dashboard.models.TreasuryYield

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
 * v6 → added chat_messages.charts (TICKET-P2-2: the analyst's render_chart
 *      calls, as JSON, so charts survive a restart now that they no longer ride
 *      inside the message text as a [CHART:...] tag)
 *
 * Every migration here is load-bearing in a way the destructive fallback below
 * hides: a MISSING or wrong migration does not crash, it silently wipes. The
 * other tables are re-fetchable API caches, but chat_messages is the one table
 * holding data that cannot be re-downloaded — the user's conversation.
 *
 * Note: the `news_articles` table (NewsArticle) is retained in the schema but has
 * no DAO — the NewsAPI path that populated it was never wired up and was removed
 * in kotlin batch 12a. The entity stays because dropping it is a schema change
 * requiring a version bump and migration. The live News tab uses the separate
 * RSS-backed news/NewsRepository and does not touch this table.
 */
@Database(
    entities = [TreasuryYield::class, NewsArticle::class, EconomicHistoryEntry::class, ChatMessageEntity::class],
    version = 6,
    exportSchema = false
)
abstract class YieldDatabase : RoomDatabase() {

    abstract fun treasuryYieldDao(): TreasuryYieldDao
    abstract fun economicHistoryDao(): EconomicHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: YieldDatabase? = null
        private val lock = Any()

        // ── Migration: v1 → v2 ───────────────────────────────────────────────────
        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
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
                )
            }
        }

        // ── Migration: v2 → v3 ───────────────────────────────────────────────────
        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
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
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_economic_history_seriesId_date` " +
                        "ON `economic_history` (`seriesId`, `date`)"
                )
            }
        }

        // ── Migration: v4 → v5 ───────────────────────────────────────────────────
        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT, " +
                        "`isUser` INTEGER NOT NULL, " +
                        "`timeMillis` INTEGER NOT NULL" +
                        ")"
                )
            }
        }

        // ── Migration: v5 → v6 ───────────────────────────────────────────────────
        // TICKET-P2-2. ALTER TABLE ADD COLUMN preserves existing rows, which is
        // the entire point: chat history is the one thing in this database that
        // cannot be re-fetched. Nullable with no DEFAULT, matching the entity —
        // a DEFAULT clause the entity doesn't declare is exactly what broke the
        // v2→v3 migration's identity hash and forced the v4 rebuild.
        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `charts` TEXT")
            }
        }

        // ── Singleton factory ─────────────────────────────────────────────────────

        @JvmStatic
        fun getInstance(context: Context): YieldDatabase = instance ?: synchronized(lock) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                YieldDatabase::class.java,
                "yield_database"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5, MIGRATION_5_6)
                // Safety net: if migration path is still missing, wipe and rebuild.
                // All data is re-fetched from APIs so no data loss in practice.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
