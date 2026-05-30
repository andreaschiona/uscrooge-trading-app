package com.uscrooge.app.backtest

import com.uscrooge.app.data.model.OHLC
import com.uscrooge.app.data.model.TradingConfig

data class WalkForwardResult(
    val windows: List<WalkForwardWindow>,
    val averageReturnPercent: Double,
    val averageSharpeRatio: Double,
    val averageMaxDrawdownPercent: Double,
    val totalTrades: Int
)

data class WalkForwardWindow(
    val windowIndex: Int,
    val trainStart: Long,
    val trainEnd: Long,
    val testStart: Long,
    val testEnd: Long,
    val totalReturnPercent: Double,
    val sharpeRatio: Double,
    val maxDrawdownPercent: Double,
    val totalTrades: Int,
    val winRate: Double
)

data class MonteCarloResult(
    val iterations: Int,
    val meanReturnPercent: Double,
    val medianReturnPercent: Double,
    val stdDevReturnPercent: Double,
    val percentile5ReturnPercent: Double,
    val percentile95ReturnPercent: Double,
    val probabilityOfProfitPercent: Double
)

data class OptimizationResult(
    val parameters: Map<String, Double>,
    val totalReturnPercent: Double,
    val sharpeRatio: Double,
    val maxDrawdownPercent: Double,
    val totalTrades: Int,
    val winRate: Double,
    val profitFactor: Double
)

data class BacktestResult(
    val pair: String,
    val startDate: Long,
    val endDate: Long,
    val initialBalance: Double,
    val finalBalance: Double,
    val totalReturn: Double,
    val totalReturnPercent: Double,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val profitFactor: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val maxDrawdownPercent: Double,
    val averageTradeDuration: Long,
    val longestTradeDuration: Long,
    val trades: List<BacktestTrade>
) {
    fun summary(): String {
        return """
            Backtest Result for $pair:
            Period: ${java.text.SimpleDateFormat("yyyy-MM-dd").format(startDate)} to ${java.text.SimpleDateFormat("yyyy-MM-dd").format(endDate)}
            Initial Balance: ${String.format("%.2f", initialBalance)}
            Final Balance: ${String.format("%.2f", finalBalance)}
            Total Return: ${String.format("%.2f", totalReturnPercent)}%
            Total Trades: $totalTrades
            Win Rate: ${String.format("%.1f", winRate)}%
            Profit Factor: ${String.format("%.2f", profitFactor)}
            Sharpe Ratio: ${String.format("%.2f", sharpeRatio)}
            Max Drawdown: ${String.format("%.2f", maxDrawdownPercent)}%
        """.trimIndent()
    }
}

data class BacktestTrade(
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val amount: Double,
    val profitLoss: Double,
    val profitLossPercent: Double,
    val duration: Long,
    val exitReason: String
)

data class BacktestConfig(
    val tradingConfig: TradingConfig = TradingConfig(),
    val initialBalance: Double = 10000.0,
    val pair: String = "BTC/EUR",
    val interval: Int = 60,
    val candleCount: Int = 1000,
    val slippagePercent: Double = 0.1,
    val feePercent: Double = 0.26
)
