package com.uscrooge.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.uscrooge.app.data.model.*

@Database(
    entities = [
        TradingSignal::class,
        Order::class,
        Position::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {

    abstract fun signalDao(): TradingSignalDao
    abstract fun orderDao(): OrderDao
    abstract fun positionDao(): PositionDao

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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
