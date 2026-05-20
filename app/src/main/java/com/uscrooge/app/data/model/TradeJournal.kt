package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trade_journal")
data class TradeJournalEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pair: String,
    val side: OrderSide,
    val entryPrice: Double,
    val exitPrice: Double,
    val amount: Double,
    val entryTime: Long,
    val exitTime: Long,
    val profitLoss: Double,
    val profitLossPercent: Double,
    val fee: Double,
    val exitReason: String,
    val duration: Long,
    val signalStrength: Double,
    val signalReasons: String,
    val notes: String? = null,
    val tags: String? = null
)

enum class ExitReason {
    STOP_LOSS,
    TAKE_PROFIT,
    TRAILING_STOP,
    SIGNAL_REVERSAL,
    MANUAL_CLOSE,
    END_OF_BACKTEST,
    TIMEOUT
}
