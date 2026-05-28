package com.uscrooge.app

import com.uscrooge.app.data.model.*

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

    fun createSignalResult(
        pair: String = "BTC/EUR",
        signalType: SignalType = SignalType.BUY,
        strength: Double = 0.85
    ): SignalResult {
        val signal = TradingSignal(
            pair = pair,
            type = signalType,
            strength = strength,
            currentPrice = 50000.0,
            suggestedPrice = 50100.0,
            stopLoss = 48500.0,
            takeProfit = 53000.0,
            suggestedAmount = 250.0,
            riskRewardRatio = 2.5,
            timestamp = System.currentTimeMillis(),
            reasons = """["Test reason"]""",
            status = SignalStatus.PENDING
        )
        val analysis = TechnicalAnalysis(
            rsi = RSI(value = 28.0, signal = RSI.Signal.OVERSOLD),
            macd = MACD(macdLine = 100.0, signalLine = 80.0, histogram = 20.0, signal = MACD.Signal.BULLISH_CROSSOVER),
            volume = VolumeAnalysis(volumeRatio = 2.0, averageVolume = 1000.0, signal = VolumeAnalysis.Signal.HIGH_VOLUME),
            trend = Trend.UPTREND,
            currentPrice = 50000.0,
            candlestickPattern = CandlestickPattern.HAMMER,
            bollingerBands = BollingerBands(
                upper = 52000.0, middle = 50000.0, lower = 48000.0,
                percentB = 0.5, bandwidth = 0.08, signal = BollingerBands.Signal.MIDDLE
            ),
            adx = ADX(value = 35.0, signal = ADX.Signal.MODERATE_TREND),
            stochasticRSI = StochasticRSI(k = 20.0, d = 25.0, signal = StochasticRSI.Signal.OVERSOLD)
        )
        return SignalResult(signal = signal, analysis = analysis)
    }
}
