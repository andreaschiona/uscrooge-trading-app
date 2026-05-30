package com.uscrooge.app.backtest

import org.junit.Assert.*
import org.junit.Test

class BacktestResultTest {

    @Test
    fun `summary contains pair name`() {
        val result = createTestResult(pair = "BTC/EUR")
        val summary = result.summary()
        assertTrue(summary.contains("BTC/EUR"))
    }

    @Test
    fun `summary contains total return`() {
        val result = createTestResult(totalReturnPercent = 15.5)
        val summary = result.summary()
        assertTrue(summary.contains("15.50%"))
    }

    @Test
    fun `summary contains total trades`() {
        val result = createTestResult(totalTrades = 5)
        val summary = result.summary()
        assertTrue(summary.contains("5"))
    }

    @Test
    fun `summary contains win rate`() {
        val result = createTestResult(winRate = 60.0)
        val summary = result.summary()
        assertTrue(summary.contains("60.0%"))
    }

    @Test
    fun `summary contains profit factor`() {
        val result = createTestResult(profitFactor = 2.5)
        val summary = result.summary()
        assertTrue(summary.contains("2.50"))
    }

    @Test
    fun `summary contains sharpe ratio`() {
        val result = createTestResult(sharpeRatio = 1.5)
        val summary = result.summary()
        assertTrue(summary.contains("1.50"))
    }

    @Test
    fun `summary contains max drawdown`() {
        val result = createTestResult(maxDrawdownPercent = 10.0)
        val summary = result.summary()
        assertTrue(summary.contains("10.00%"))
    }

    @Test
    fun `trades are listed in result`() {
        val trades = listOf(
            BacktestTrade(
                entryTime = 1000L, exitTime = 2000L,
                entryPrice = 100.0, exitPrice = 110.0,
                amount = 1.0, profitLoss = 10.0,
                profitLossPercent = 10.0, duration = 1000,
                exitReason = "Take profit"
            )
        )
        val result = createTestResult(trades = trades)
        assertEquals(1, result.trades.size)
        assertEquals("Take profit", result.trades[0].exitReason)
    }

    @Test
    fun `zero trades summary still works`() {
        val result = createTestResult(totalTrades = 0)
        val summary = result.summary()
        assertTrue(summary.isNotBlank())
    }

    private fun createTestResult(
        pair: String = "BTC/EUR",
        totalReturnPercent: Double = 10.0,
        totalTrades: Int = 5,
        winRate: Double = 60.0,
        profitFactor: Double = 2.0,
        sharpeRatio: Double = 1.5,
        maxDrawdownPercent: Double = 5.0,
        trades: List<BacktestTrade> = emptyList()
    ): BacktestResult = BacktestResult(
        pair = pair,
        startDate = 1000000L,
        endDate = 2000000L,
        initialBalance = 10000.0,
        finalBalance = 11000.0,
        totalReturn = 1000.0,
        totalReturnPercent = totalReturnPercent,
        totalTrades = totalTrades,
        winningTrades = (totalTrades * winRate / 100).toInt(),
        losingTrades = totalTrades - (totalTrades * winRate / 100).toInt(),
        winRate = winRate,
        averageWin = 200.0,
        averageLoss = -100.0,
        profitFactor = profitFactor,
        sharpeRatio = sharpeRatio,
        maxDrawdown = 500.0,
        maxDrawdownPercent = maxDrawdownPercent,
        averageTradeDuration = 3600000,
        longestTradeDuration = 7200000,
        trades = trades
    )
}
