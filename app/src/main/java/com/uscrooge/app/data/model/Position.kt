package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "positions")
data class Position(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pair: String,
    val amount: Double,                  // Amount of base currency
    val averageEntryPrice: Double,
    val currentPrice: Double,
    val totalInvested: Double,           // In quote currency (EUR)
    val currentValue: Double,            // Current value in quote currency
    val unrealizedPnL: Double,           // Profit/Loss
    val unrealizedPnLPercent: Double,
    val openedAt: Long,
    val updatedAt: Long,
    val isOpen: Boolean = true,
    val closedAt: Long? = null,
    val realizedPnL: Double? = null
) {
    fun calculateCurrentValue(currentPrice: Double): Position {
        val newCurrentValue = amount * currentPrice
        val newUnrealizedPnL = newCurrentValue - totalInvested
        val newUnrealizedPnLPercent = (newUnrealizedPnL / totalInvested) * 100

        return copy(
            currentPrice = currentPrice,
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
