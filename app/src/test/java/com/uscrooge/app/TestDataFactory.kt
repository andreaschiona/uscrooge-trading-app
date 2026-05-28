package com.uscrooge.app

import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.SignalResult

object TestDataFactory {

    fun createPosition(
        pair: String = "BTC/EUR",
        amount: Double = 0.01,
        entryPrice: Double = 50000.0,
        currentPrice: Double = 51000.0,
        isOpen: Boolean = true,
        broker: String = "Kraken"
    ): Position {
        val totalInvested = amount * entryPrice
        val currentValue = amount * currentPrice
        val pnl = currentValue - totalInvested
        val pnlPercent = if (totalInvested > 0) (pnl / totalInvested) * 100 else 0.0
        return Position(
            pair = pair,
            amount = amount,
            averageEntryPrice = entryPrice,
            currentPrice = currentPrice,
            peakPrice = maxOf(currentPrice, entryPrice),
            totalInvested = totalInvested,
            currentValue = currentValue,
            unrealizedPnL = pnl,
            unrealizedPnLPercent = pnlPercent,
            openedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isOpen = isOpen,
            broker = broker
        )
    }

    fun createTradingSignal(
        id: Long = 1,
        pair: String = "BTC/EUR",
        type: SignalType = SignalType.BUY,
        strength: Double = 0.85,
        status: SignalStatus = SignalStatus.PENDING
    ): TradingSignal {
        return TradingSignal(
            id = id,
            pair = pair,
            type = type,
            strength = strength,
            currentPrice = 50000.0,
            suggestedPrice = 50100.0,
            stopLoss = 48500.0,
            takeProfit = 53000.0,
            suggestedAmount = 250.0,
            riskRewardRatio = 2.5,
            timestamp = System.currentTimeMillis(),
            reasons = """["RSI oversold","MACD bullish crossover"]""",
            status = status
        )
    }


}
