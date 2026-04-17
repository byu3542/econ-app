package com.economic.dashboard.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.economic.dashboard.models.TreasuryYield;

/**
 * Room Database for Treasury Yields caching.
 * Singleton pattern ensures only one database instance exists.
 *
 * This database stores cached Treasury yield data locally so the app
 * can serve data without making API calls on every open.
 */
@Database(entities = {TreasuryYield.class}, version = 1, exportSchema = false)
public abstract class YieldDatabase extends RoomDatabase {

    private static volatile YieldDatabase instance;
    private static final Object lock = new Object();

    /**
     * Get the DAO for querying/inserting Treasury yields.
     */
    public abstract TreasuryYieldDao treasuryYieldDao();

    /**
     * Get singleton instance of the database.
     * Uses double-checked locking pattern for thread safety.
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
                    ).build();
                }
            }
        }
        return instance;
    }
}
