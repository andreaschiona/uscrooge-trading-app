package com.uscrooge.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.data.model.AuditLogEntry
import com.uscrooge.app.data.model.*

@Database(
    entities = [
        TradingSignal::class,
        Order::class,
        Position::class,
        TradeJournalEntry::class,
        AuditLogEntry::class
    ],
    version = 9,
    exportSchema = true
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
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    "uscrooge_database"
                )
                    .addMigrations(
                        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
