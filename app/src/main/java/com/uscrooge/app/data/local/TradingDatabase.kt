package com.uscrooge.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.uscrooge.app.data.model.*

private const val TAG = "TradingDatabase"

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trade_journal (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    pair TEXT NOT NULL,
                    side TEXT NOT NULL,
                    entryPrice REAL NOT NULL,
                    exitPrice REAL NOT NULL,
                    amount REAL NOT NULL,
                    entryTime INTEGER NOT NULL,
                    exitTime INTEGER NOT NULL,
                    profitLoss REAL NOT NULL,
                    profitLossPercent REAL NOT NULL,
                    fee REAL NOT NULL,
                    exitReason TEXT NOT NULL,
                    duration INTEGER NOT NULL,
                    signalStrength REAL NOT NULL,
                    signalReasons TEXT NOT NULL,
                    notes TEXT,
                    tags TEXT
                )
                """.trimIndent()
            )
            Log.i(TAG, "Migration 2->3 completed: trade_journal table created")
        } catch (e: Exception) {
            Log.e(TAG, "Migration 2->3 failed", e)
            throw e
        }
    }
}

@Database(
    entities = [
        TradingSignal::class,
        Order::class,
        Position::class,
        TradeJournalEntry::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {

    abstract fun signalDao(): TradingSignalDao
    abstract fun orderDao(): OrderDao
    abstract fun positionDao(): PositionDao
    abstract fun tradeJournalDao(): TradeJournalDao

    companion object {
        @Volatile
        private var INSTANCE: TradingDatabase? = null

        fun getDatabase(context: Context): TradingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    "uscrooge_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
