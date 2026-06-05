package com.uscrooge.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
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
        """.trimIndent())
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE positions ADD COLUMN broker TEXT NOT NULL DEFAULT 'Kraken'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                category TEXT NOT NULL,
                action TEXT NOT NULL,
                details TEXT NOT NULL,
                severity TEXT NOT NULL DEFAULT 'INFO'
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_category ON audit_log(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_category_timestamp ON audit_log(category, timestamp)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No schema change between v6 and v7 — only a version bump
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE positions ADD COLUMN pyramidLevel INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trading_signals ADD COLUMN assetName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE trading_signals ADD COLUMN assetDescription TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE trading_signals ADD COLUMN assetType TEXT NOT NULL DEFAULT 'CRYPTO'")
    }
}
