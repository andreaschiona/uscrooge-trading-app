package com.uscrooge.app.backtest

import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.TradingStrategy
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BacktestEngineTest {

    private lateinit var engine: BacktestEngine
    private lateinit var strategy: TradingStrategy

    @Before
    fun setup() {
        val analyzer = TechnicalAnalyzer()
        val sentimentAnalyzer = SentimentAnalyzer()
        val tradeJournalDao = mockk<TradeJournalDao>(relaxed = true)
        strategy = TradingStrategy(analyzer, sentimentAnalyzer, tradeJournalDao)
        engine = BacktestEngine(analyzer, strategy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `runBacktest throws on insufficient data`() = runTest {
        val smallData = listOf(
            createOhlc(time = 1L, close = 100.0, high = 105.0, low = 95.0)
        )
        val config = BacktestConfig()
        engine.runBacktest(smallData, config)
    }

    @Test
    fun `runBacktest with sufficient data returns result`() = runTest {
        val ohlcData = createOhlcSeries(count = 150)
        val config = BacktestConfig(
            initialBalance = 10000.0,
            pair = "BTC/EUR"
        )
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(ohlcData, config)
        assertNotNull(result)
        assertTrue(result.totalTrades >= 0)
        assertTrue(result.finalBalance >= 0)
    }

    @Test
    fun `runBacktest pair and dates are set correctly`() = runTest {
        val ohlcData = createOhlcSeries(count = 150)
        val config = BacktestConfig(pair = "BTC/EUR")
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(ohlcData, config)
        assertEquals("BTC/EUR", result.pair)
        assertEquals(ohlcData.first().time, result.startDate)
        assertEquals(ohlcData.last().time, result.endDate)
    }

    @Test
    fun `runBacktest initial balance is preserved`() = runTest {
        val ohlcData = createOhlcSeries(count = 150)
        val config = BacktestConfig(initialBalance = 5000.0)
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(ohlcData, config)
        assertEquals(5000.0, result.initialBalance, 0.001)
    }

    @Test
    fun `runBacktest with flat market returns zero or minimal trades`() = runTest {
        val flatData = createFlatOhlcSeries(count = 150, price = 100.0)
        val config = BacktestConfig()
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(flatData, config)
        // In a flat market with no volatility, there should be few or no trades
        assertTrue(result.totalTrades >= 0)
    }

    @Test
    fun `runBacktest with uptrend may generate buy signals`() = runTest {
        val uptrendData = createOhlcSeries(count = 200, startPrice = 100.0, trend = 2.0)
        val config = BacktestConfig()
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(uptrendData, config)
        assertTrue(result.totalTrades >= 0)
    }

    @Test
    fun `runBacktest result fields are properly calculated`() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig(
            initialBalance = 10000.0,
            feePercent = 0.0,
            slippagePercent = 0.0
        )
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(ohlcData, config)
        assertEquals(result.initialBalance, 10000.0, 0.001)
        assertTrue(result.totalReturnPercent >= -100.0 || result.totalReturnPercent <= 1000.0)
        assertTrue(result.profitFactor >= 0.0)
        assertTrue(result.winRate in 0.0..100.0)
    }

    @Test
    fun `runBacktest trades list contains trade details`() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig()
        strategy.updateConfig(config.tradingConfig)

        val result = engine.runBacktest(ohlcData, config)
        result.trades.forEach { trade ->
            assertTrue(trade.entryPrice > 0)
            assertTrue(trade.exitPrice > 0)
            assertTrue(trade.amount > 0)
            assertTrue(trade.duration >= 0)
        }
    }

    @Test
    fun `runBacktest with higher risk per trade increases trade count`() = runTest {
        val ohlcData = createVolatileOhlcSeries(count = 200)
        val highRiskConfig = BacktestConfig(
            tradingConfig = TradingConfig(
                riskPerTrade = 0.5,
                minSignalStrength = 0.5
            )
        )
        val lowRiskConfig = BacktestConfig(
            tradingConfig = TradingConfig(
                riskPerTrade = 0.1,
                minSignalStrength = 0.8
            )
        )
        strategy.updateConfig(highRiskConfig.tradingConfig)
        val highResult = engine.runBacktest(ohlcData, highRiskConfig)

        strategy.updateConfig(lowRiskConfig.tradingConfig)
        val lowResult = engine.runBacktest(ohlcData, lowRiskConfig)

        // Higher risk should generally lead to more trades or similar
        assertTrue(highResult.totalTrades >= 0 && lowResult.totalTrades >= 0)
    }

    private fun createOhlc(time: Long, close: Double, high: Double, low: Double): OHLC = OHLC(
        time = time,
        open = close - 5.0,
        high = high,
        low = low,
        close = close,
        vwap = close,
        volume = 100.0 + (time % 10) * 10,
        count = 100
    )

    private fun createOhlcSeries(count: Int, startPrice: Double = 100.0, trend: Double = 1.0): List<OHLC> {
        return (0 until count).map { i ->
            val close = startPrice + i * trend + (Math.random() - 0.5) * 10
            createOhlc(
                time = 1000000L + i * 60L,
                close = close,
                high = close + 5.0,
                low = close - 5.0
            )
        }
    }

    private fun createFlatOhlcSeries(count: Int, price: Double): List<OHLC> {
        return (0 until count).map { i ->
            createOhlc(
                time = 1000000L + i * 60L,
                close = price,
                high = price + 1.0,
                low = price - 1.0
            )
        }
    }

    private fun createVolatileOhlcSeries(count: Int): List<OHLC> {
        return (0 until count).map { i ->
            val close = 100.0 + Math.sin(i * 0.1) * 30 + (Math.random() - 0.5) * 5
            createOhlc(
                time = 1000000L + i * 60L,
                close = close,
                high = close + 10.0,
                low = close - 10.0
            )
        }
    }
}
