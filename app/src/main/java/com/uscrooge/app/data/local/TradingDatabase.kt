package com.uscrooge.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.uscrooge.app.data.model.AuditLogEntry
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE positions ADD COLUMN broker TEXT NOT NULL DEFAULT 'Kraken'")
            Log.i(TAG, "Migration 3->4 completed: added broker column to positions")
        } catch (e: Exception) {
            Log.e(TAG, "Migration 3->4 failed", e)
            throw e
        }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trading_signals_pair ON trading_signals(pair)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trading_signals_status ON trading_signals(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trading_signals_timestamp ON trading_signals(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trading_signals_pair_status ON trading_signals(pair, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_positions_pair ON positions(pair)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_positions_isOpen ON positions(isOpen)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_positions_broker ON positions(broker)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_positions_pair_isOpen ON positions(pair, isOpen)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_pair ON orders(pair)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_createdAt ON orders(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_pair_status ON orders(pair, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trade_journal_pair ON trade_journal(pair)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trade_journal_exitTime ON trade_journal(exitTime)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trade_journal_pair_exitTime ON trade_journal(pair, exitTime)")
            Log.i(TAG, "Migration 4->5 completed: added database indices")
        } catch (e: Exception) {
            Log.e(TAG, "Migration 4->5 failed", e)
            throw e
        }
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    action TEXT NOT NULL,
                    details TEXT NOT NULL,
                    severity TEXT NOT NULL DEFAULT 'INFO'
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_category ON audit_log(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_category_timestamp ON audit_log(category, timestamp)")
            Log.i(TAG, "Migration 5->6 completed: created audit_log table")
        } catch (e: Exception) {
            Log.e(TAG, "Migration 5->6 failed", e)
            throw e
        }
    }
}

@Database(
    entities = [
        TradingSignal::class,
        Order::class,
        Position::class,
        TradeJournalEntry::class,
        AuditLogEntry::class
    ],
    version = 6,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {

    abstract fun signalDao(): TradingSignalDao
    abstract fun orderDao(): OrderDao
    abstract fun positionDao(): PositionDao
    abstract fun tradeJournalDao(): TradeJournalDao
    abstract fun auditLogDao(): AuditLogDao

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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
