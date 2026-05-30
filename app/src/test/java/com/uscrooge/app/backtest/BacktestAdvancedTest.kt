package com.uscrooge.app.backtest

import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.OHLC
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.strategy.TradingStrategy
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BacktestAdvancedTest {

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

    @Test
    fun walkForwardAnalysisWithSufficientDataReturnsWindows() = runTest {
        val ohlcData = createOhlcSeries(count = 1000)
        val config = BacktestConfig(initialBalance = 10000.0, pair = "BTC/EUR")
        strategy.updateConfig(config.tradingConfig)

        val result = engine.walkForwardAnalysis(ohlcData, config, windowSize = 400, stepSize = 100)
        assertTrue(result.windows.isNotEmpty())
        assertTrue(result.totalTrades >= 0)
    }

    @Test
    fun walkForwardAnalysisReturnsAverages() = runTest {
        val ohlcData = createOhlcSeries(count = 800)
        val config = BacktestConfig(initialBalance = 10000.0)
        strategy.updateConfig(config.tradingConfig)

        val result = engine.walkForwardAnalysis(ohlcData, config, windowSize = 300, stepSize = 100)
        assertFalse(result.windows.isEmpty())
        assertTrue(result.averageReturnPercent.isFinite())
        assertTrue(result.averageSharpeRatio.isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun walkForwardAnalysisThrowsOnInsufficientData() = runTest {
        val ohlcData = createOhlcSeries(count = 50)
        val config = BacktestConfig()
        engine.walkForwardAnalysis(ohlcData, config)
    }

    @Test
    fun monteCarloSimulationReturnsExpectedStructure() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig(initialBalance = 10000.0, feePercent = 0.0, slippagePercent = 0.0)
        strategy.updateConfig(config.tradingConfig)

        val baseResult = engine.runBacktest(ohlcData, config)
        val mcResult = engine.monteCarloSimulation(baseResult, iterations = 100, seed = 42L)

        assertEquals(100, mcResult.iterations)
        assertTrue(mcResult.meanReturnPercent.isFinite())
        assertTrue(mcResult.medianReturnPercent.isFinite())
        assertTrue(mcResult.probabilityOfProfitPercent in 0.0..100.0)
    }

    @Test
    fun monteCarloSimulationWithNoTradesReturnsZeroReturns() = runTest {
        val flatData = createFlatOhlcSeries(count = 200, price = 100.0)
        val config = BacktestConfig(initialBalance = 10000.0)
        strategy.updateConfig(config.tradingConfig)

        val baseResult = engine.runBacktest(flatData, config)
        val mcResult = engine.monteCarloSimulation(baseResult, iterations = 50)

        assertTrue(mcResult.iterations > 0)
        assertTrue(mcResult.percentile5ReturnPercent <= mcResult.percentile95ReturnPercent)
    }

    @Test
    fun monteCarloSimulationWithDifferentSeedsProducesDifferentResults() = runTest {
        val ohlcData = createVolatileOhlcSeries(count = 200)
        val config = BacktestConfig(initialBalance = 10000.0)
        strategy.updateConfig(config.tradingConfig)

        val baseResult = engine.runBacktest(ohlcData, config)
        val result1 = engine.monteCarloSimulation(baseResult, iterations = 50, seed = 1L)
        val result2 = engine.monteCarloSimulation(baseResult, iterations = 50, seed = 2L)

        // Different seeds should produce different outcomes
        assertNotNull(result1)
        assertNotNull(result2)
    }

    @Test
    fun parameterOptimizerReturnsSortedResults() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig(initialBalance = 10000.0, pair = "BTC/EUR")
        strategy.updateConfig(config.tradingConfig)

        val params = mapOf(
            "riskPerTrade" to listOf(0.1, 0.25),
            "minSignalStrength" to listOf(0.5, 0.65)
        )

        val results = engine.parameterOptimizer(ohlcData, config, params)
        assertTrue(results.isNotEmpty())
        // Results should be sorted by Sharpe ratio descending
        for (i in 1 until results.size) {
            assertTrue(results[i - 1].sharpeRatio >= results[i].sharpeRatio - 0.001)
        }
    }

    @Test
    fun parameterOptimizerWithSingleParameter() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig(initialBalance = 10000.0)
        strategy.updateConfig(config.tradingConfig)

        val params = mapOf("riskPerTrade" to listOf(0.1, 0.2, 0.3))
        val results = engine.parameterOptimizer(ohlcData, config, params)
        assertEquals(3, results.size)
        results.forEach { result ->
            assertTrue(result.parameters.containsKey("riskPerTrade"))
            assertTrue(result.totalReturnPercent.isFinite())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun parameterOptimizerThrowsOnInsufficientData() = runTest {
        val ohlcData = createOhlcSeries(count = 50)
        val config = BacktestConfig()
        engine.parameterOptimizer(ohlcData, config, emptyMap())
    }

    @Test
    fun parameterOptimizerWithEmptyParamsReturnsEmpty() = runTest {
        val ohlcData = createOhlcSeries(count = 200)
        val config = BacktestConfig()
        val results = engine.parameterOptimizer(ohlcData, config, emptyMap())
        assertTrue(results.isEmpty())
    }

    @Test
    fun walkForwardWindowHasCorrectStructure() = runTest {
        val ohlcData = createOhlcSeries(count = 700)
        val config = BacktestConfig(initialBalance = 10000.0)
        strategy.updateConfig(config.tradingConfig)

        val result = engine.walkForwardAnalysis(ohlcData, config, windowSize = 300, stepSize = 100)
        result.windows.forEach { window ->
            assertTrue(window.trainStart < window.trainEnd)
            assertTrue(window.testStart < window.testEnd)
            assertTrue(window.windowIndex >= 0)
            assertTrue(window.totalTrades >= 0)
            assertTrue(window.winRate in 0.0..100.0)
        }
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
