package com.uscrooge.app.backtest

import android.util.Log
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.TradingStrategy
import kotlin.math.*
import kotlin.random.Random

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

        val minHistory = 28

        for (i in tradingData.indices) {
            val currentCandle = tradingData[i]
            val historicalData = tradingData.take(i + 1)
            val currentPrice = currentCandle.close

            if (historicalData.size < minHistory) continue

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

    suspend fun walkForwardAnalysis(
        ohlcData: List<OHLC>,
        config: BacktestConfig,
        windowSize: Int = 500,
        stepSize: Int = 100
    ): WalkForwardResult {
        if (ohlcData.size < windowSize + 100) {
            throw IllegalArgumentException("Not enough data for walk-forward analysis")
        }

        val windows = mutableListOf<WalkForwardWindow>()
        var pos = 0

        while (pos + windowSize + stepSize <= ohlcData.size) {
            val trainData = ohlcData.subList(pos, pos + windowSize)
            val testData = ohlcData.subList(pos + windowSize, minOf(pos + windowSize + stepSize, ohlcData.size))

            pos += stepSize

            // Run backtest on test window
            val windowConfig = config.copy(candleCount = testData.size)
            val result = runBacktest(testData, windowConfig)

            windows.add(
                WalkForwardWindow(
                    windowIndex = windows.size,
                    trainStart = trainData.first().time,
                    trainEnd = trainData.last().time,
                    testStart = testData.first().time,
                    testEnd = testData.last().time,
                    totalReturnPercent = result.totalReturnPercent,
                    sharpeRatio = result.sharpeRatio,
                    maxDrawdownPercent = result.maxDrawdownPercent,
                    totalTrades = result.totalTrades,
                    winRate = result.winRate
                )
            )
        }

        val avgReturn = windows.map { it.totalReturnPercent }.average()
        val avgSharpe = windows.map { it.sharpeRatio }.average()
        val avgDrawdown = windows.map { it.maxDrawdownPercent }.average()
        val totalTrades = windows.sumOf { it.totalTrades }

        return WalkForwardResult(
            windows = windows,
            averageReturnPercent = avgReturn,
            averageSharpeRatio = avgSharpe,
            averageMaxDrawdownPercent = avgDrawdown,
            totalTrades = totalTrades
        )
    }

    fun monteCarloSimulation(
        baseResult: BacktestResult,
        iterations: Int = 1000,
        seed: Long = 42L
    ): MonteCarloResult {
        val rng = Random(seed)
        val outcomes = mutableListOf<Double>()

        for (i in 0 until iterations) {
            var simulatedBalance = baseResult.initialBalance
            for (trade in baseResult.trades) {
                val jitteredReturn = trade.profitLossPercent * (0.5 + rng.nextDouble())
                simulatedBalance += simulatedBalance * (jitteredReturn / 100.0)
            }
            val totalReturn = ((simulatedBalance - baseResult.initialBalance) / baseResult.initialBalance) * 100.0
            outcomes.add(totalReturn)
        }

        val sorted = outcomes.sorted()
        val mean = outcomes.average()
        val median = sorted[outcomes.size / 2]
        val stdDev = sqrt(outcomes.map { (it - mean) * (it - mean) }.average())
        val percentile5 = sorted[(outcomes.size * 0.05).toInt()]
        val percentile95 = sorted[(outcomes.size * 0.95).toInt()]
        val positiveOutcomes = outcomes.count { it > 0 }
        val probabilityOfProfit = positiveOutcomes.toDouble() / outcomes.size * 100.0

        return MonteCarloResult(
            iterations = iterations,
            meanReturnPercent = mean,
            medianReturnPercent = median,
            stdDevReturnPercent = stdDev,
            percentile5ReturnPercent = percentile5,
            percentile95ReturnPercent = percentile95,
            probabilityOfProfitPercent = probabilityOfProfit
        )
    }

    fun parameterOptimizer(
        ohlcData: List<OHLC>,
        config: BacktestConfig,
        parameters: Map<String, List<Double>>
    ): List<OptimizationResult> {
        if (ohlcData.size < 100) {
            throw IllegalArgumentException("Need at least 100 candles for optimization")
        }

        val configKey = config.tradingConfig
        val results = mutableListOf<OptimizationResult>()

        val paramKeys = parameters.keys.toList()
        if (paramKeys.isEmpty()) return results

        fun evaluate(params: Map<String, Double>): BacktestResult {
            var modifiedConfig = configKey
            for ((key, value) in params) {
                modifiedConfig = when (key) {
                    "riskPerTrade" -> modifiedConfig.copy(riskPerTrade = value)
                    "minSignalStrength" -> modifiedConfig.copy(minSignalStrength = value)
                    "stopLossPercent" -> modifiedConfig.copy(stopLossPercent = value)
                    "takeProfitPercent" -> modifiedConfig.copy(takeProfitPercent = value)
                    "rsiPeriod" -> modifiedConfig.copy(rsiPeriod = value.toInt())
                    "macdFastPeriod" -> modifiedConfig.copy(macdFastPeriod = value.toInt())
                    "macdSlowPeriod" -> modifiedConfig.copy(macdSlowPeriod = value.toInt())
                    else -> modifiedConfig
                }
            }
            tradingStrategy.updateConfig(modifiedConfig)
            return runBlocking { runBacktest(ohlcData, config.copy(tradingConfig = modifiedConfig)) }
        }

        fun recurse(currentParams: MutableMap<String, Double>, depth: Int) {
            if (depth == paramKeys.size) {
                val result = evaluate(currentParams)
                results.add(
                    OptimizationResult(
                        parameters = currentParams.toMap(),
                        totalReturnPercent = result.totalReturnPercent,
                        sharpeRatio = result.sharpeRatio,
                        maxDrawdownPercent = result.maxDrawdownPercent,
                        totalTrades = result.totalTrades,
                        winRate = result.winRate,
                        profitFactor = result.profitFactor
                    )
                )
                return
            }

            val key = paramKeys[depth]
            for (value in parameters[key] ?: emptyList()) {
                currentParams[key] = value
                recurse(currentParams, depth + 1)
            }
        }

        recurse(mutableMapOf(), 0)
        return results.sortedByDescending { it.sharpeRatio }
    }

    private fun runBlocking(block: suspend () -> BacktestResult): BacktestResult {
        return kotlinx.coroutines.runBlocking { block() }
    }

    fun getTradingStrategy(): TradingStrategy = tradingStrategy

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
