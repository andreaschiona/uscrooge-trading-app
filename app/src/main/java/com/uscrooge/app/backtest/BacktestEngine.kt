package com.uscrooge.app.backtest

import android.util.Log
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.TradingStrategy
import kotlin.math.*

class BacktestEngine(
    private val technicalAnalyzer: TechnicalAnalyzer,
    private val tradingStrategy: TradingStrategy
) {

    companion object {
        private const val TAG = "BacktestEngine"
    }

    suspend fun runBacktest(
        ohlcData: List<OHLC>,
        config: BacktestConfig
    ): BacktestResult {
        if (ohlcData.size < 100) {
            throw IllegalArgumentException("Need at least 100 candles for backtesting")
        }

        val trades = mutableListOf<BacktestTrade>()
        var balance = config.initialBalance
        var position: BacktestPosition? = null
        var peakBalance = config.initialBalance
        var maxDrawdown = 0.0
        var dailyReturns = mutableListOf<Double>()

        val warmupPeriod = 50
        val tradingData = ohlcData.drop(warmupPeriod)
        val feeRate = config.feePercent / 100.0

        for (i in tradingData.indices) {
            val currentCandle = tradingData[i]
            val historicalData = tradingData.take(i + 1)
            val currentPrice = currentCandle.close

            val signalResult = tradingStrategy.generateSignal(
                pair = config.pair,
                ohlcData = historicalData,
                currentPrice = currentPrice,
                currentPositions = position?.let { listOf(it.toPosition()) } ?: emptyList(),
                availableBalance = balance
            )

            val slippage = currentPrice * (config.slippagePercent / 100.0)

            if (position != null) {
                val exitSignal = tradingStrategy.evaluateExitConditions(
                    position.toPosition(),
                    currentPrice,
                    config.tradingConfig
                )

                if (exitSignal != null) {
                    val exitPrice = currentPrice - slippage
                    val exitValue = position.amount * exitPrice
                    val exitFee = exitValue * feeRate
                    val profitLoss = exitValue - position.totalInvested - exitFee
                    val profitLossPercent = (profitLoss / position.totalInvested) * 100.0

                    trades.add(
                        BacktestTrade(
                            entryTime = position.entryTime,
                            exitTime = currentCandle.time,
                            entryPrice = position.entryPrice,
                            exitPrice = exitPrice,
                            amount = position.amount,
                            profitLoss = profitLoss,
                            profitLossPercent = profitLossPercent,
                            duration = currentCandle.time - position.entryTime,
                            exitReason = exitSignal.reason
                        )
                    )

                    balance += exitValue - exitFee
                    position = null
                }
            } else if (signalResult.signal != null &&
                signalResult.signal.strength >= config.tradingConfig.minSignalStrength) {

                val entryPrice = currentPrice + slippage
                val riskAmount = balance * config.tradingConfig.riskPerTrade
                val fee = riskAmount * feeRate
                val investAmount = riskAmount - fee
                val amount = investAmount / entryPrice

                if (amount > 0 && balance > riskAmount) {
                    position = BacktestPosition(
                        entryTime = currentCandle.time,
                        entryPrice = entryPrice,
                        amount = amount,
                        totalInvested = riskAmount,
                        peakPrice = entryPrice
                    )
                    balance -= riskAmount
                }
            }

            val equity = balance + (position?.amount?.times(currentPrice) ?: 0.0)
            peakBalance = max(peakBalance, equity)
            val drawdown = (peakBalance - equity) / peakBalance
            maxDrawdown = max(maxDrawdown, drawdown)

            if (i > 0 && i % 1440 == 0) {
                val prevCandle = tradingData[maxOf(0, i - 1440)]
                val prevEquity = balance + (position?.amount?.times(prevCandle.close) ?: 0.0)
                if (prevEquity > 0) {
                    dailyReturns.add((equity - prevEquity) / prevEquity)
                }
            }
        }

        if (position != null) {
            val lastPrice = tradingData.last().close
            val exitValue = position.amount * lastPrice
            val exitFee = exitValue * feeRate
            val profitLoss = exitValue - position.totalInvested - exitFee

            trades.add(
                BacktestTrade(
                    entryTime = position.entryTime,
                    exitTime = tradingData.last().time,
                    entryPrice = position.entryPrice,
                    exitPrice = lastPrice,
                    amount = position.amount,
                    profitLoss = profitLoss,
                    profitLossPercent = (profitLoss / position.totalInvested) * 100.0,
                    duration = tradingData.last().time - position.entryTime,
                    exitReason = "End of backtest period"
                )
            )
            balance += exitValue - exitFee
        }

        val winningTrades = trades.filter { it.profitLoss > 0 }
        val losingTrades = trades.filter { it.profitLoss <= 0 }
        val totalReturn = balance - config.initialBalance
        val totalReturnPercent = (totalReturn / config.initialBalance) * 100.0

        val averageWin = if (winningTrades.isNotEmpty()) winningTrades.map { it.profitLoss }.average() else 0.0
        val averageLoss = if (losingTrades.isNotEmpty()) losingTrades.map { abs(it.profitLoss) }.average() else 0.0
        val grossProfit = winningTrades.sumOf { it.profitLoss }
        val grossLoss = abs(losingTrades.sumOf { it.profitLoss })
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0

        val sharpeRatio = calculateSharpeRatio(dailyReturns)
        val avgDuration = if (trades.isNotEmpty()) trades.map { it.duration }.average().toLong() else 0L
        val longestDuration = if (trades.isNotEmpty()) trades.maxOf { it.duration } else 0L

        return BacktestResult(
            pair = config.pair,
            startDate = ohlcData.first().time,
            endDate = ohlcData.last().time,
            initialBalance = config.initialBalance,
            finalBalance = balance,
            totalReturn = totalReturn,
            totalReturnPercent = totalReturnPercent,
            totalTrades = trades.size,
            winningTrades = winningTrades.size,
            losingTrades = losingTrades.size,
            winRate = if (trades.isNotEmpty()) (winningTrades.size.toDouble() / trades.size) * 100.0 else 0.0,
            averageWin = averageWin,
            averageLoss = averageLoss,
            profitFactor = profitFactor,
            sharpeRatio = sharpeRatio,
            maxDrawdown = maxDrawdown * config.initialBalance,
            maxDrawdownPercent = maxDrawdown * 100.0,
            averageTradeDuration = avgDuration,
            longestTradeDuration = longestDuration,
            trades = trades
        )
    }

    private fun calculateSharpeRatio(returns: List<Double>): Double {
        if (returns.isEmpty()) return 0.0

        val avgReturn = returns.average()
        val variance = returns.map { (it - avgReturn) * (it - avgReturn) }.average()
        val stdDev = sqrt(variance)

        if (stdDev == 0.0) return 0.0

        val riskFreeRate = 0.02 / 252
        return ((avgReturn - riskFreeRate) / stdDev) * sqrt(252.0)
    }

    private data class BacktestPosition(
        val entryTime: Long,
        val entryPrice: Double,
        val amount: Double,
        val totalInvested: Double,
        val peakPrice: Double
    ) {
        fun toPosition(): Position {
            return Position(
                id = 0,
                pair = "",
                amount = amount,
                averageEntryPrice = entryPrice,
                currentPrice = entryPrice,
                peakPrice = peakPrice,
                totalInvested = totalInvested,
                currentValue = amount * entryPrice,
                unrealizedPnL = 0.0,
                unrealizedPnLPercent = 0.0,
                openedAt = entryTime,
                updatedAt = entryTime,
                isOpen = true
            )
        }
    }
}
