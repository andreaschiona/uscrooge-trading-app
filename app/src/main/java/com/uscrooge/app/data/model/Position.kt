package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "positions",
    indices = [
        Index("pair"),
        Index("isOpen"),
        Index("broker"),
        Index("pair", "isOpen")
    ]
)
data class Position(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pair: String,
    val amount: Double,                  // Amount of base currency
    val averageEntryPrice: Double,
    val currentPrice: Double,
    val peakPrice: Double = 0.0,         // Highest price since entry (for trailing stop)
    val totalInvested: Double,           // In quote currency (EUR/USD)
    val currentValue: Double,            // Current value in quote currency
    val unrealizedPnL: Double,           // Profit/Loss
    val unrealizedPnLPercent: Double,
    val openedAt: Long,
    val updatedAt: Long,
    val isOpen: Boolean = true,
    val closedAt: Long? = null,
    val realizedPnL: Double? = null,
    val exchangeStopOrderId: String? = null,    // Stop-loss order ID on exchange
    val exchangeTakeProfitOrderId: String? = null, // Take-profit order ID on exchange
    val broker: String = "Kraken"         // "Kraken" or "Alpaca"
) {
    fun calculateCurrentValue(currentPrice: Double): Position {
        val newCurrentValue = amount * currentPrice
        val newUnrealizedPnL = newCurrentValue - totalInvested
        val newUnrealizedPnLPercent = if (totalInvested > 0) (newUnrealizedPnL / totalInvested) * 100 else 0.0
        val newPeakPrice = maxOf(peakPrice, currentPrice)

        return copy(
            currentPrice = currentPrice,
            peakPrice = newPeakPrice,
            currentValue = newCurrentValue,
            unrealizedPnL = newUnrealizedPnL,
            unrealizedPnLPercent = newUnrealizedPnLPercent,
            updatedAt = System.currentTimeMillis()
        )
    }
}

data class Portfolio(
    val totalInvested: Double,
    val currentValue: Double,
    val totalPnL: Double,
    val totalPnLPercent: Double,
    val positions: List<Position>,
    val availableBalance: Double,
    val availableBalanceSource: String,
    val timestamp: Long = System.currentTimeMillis()
)
