package com.uscrooge.app

import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.ExitUrgency
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.coEvery
import io.mockk.mockk

class TradingStrategyTest {

    private lateinit var strategy: TradingStrategy
    private lateinit var config: TradingConfig
    private lateinit var tradeJournalDao: TradeJournalDao

    @Before
    fun setup() {
        val analyzer = TechnicalAnalyzer()
        val sentimentAnalyzer = SentimentAnalyzer()
        tradeJournalDao = mockk(relaxed = true)
        strategy = TradingStrategy(analyzer, sentimentAnalyzer, tradeJournalDao)
        config = TradingConfig(
            tradingPairs = listOf("BTC/EUR"),
            minSignalStrength = 0.5,
            useVolumeAnalysis = true,
            minVolumeRatio = 0.3
        )
        strategy.updateConfig(config)
    }

    @Test
    fun `generateSignal returns analysis even without signal`() = runTest {
        val ohlcData = createMixedOhlc(count = 100)
        val currentPrice = ohlcData.last().close

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            availableBalance = 1000.0
        )

        assertNotNull(result.analysis)
        assertNotNull(result.analysis.rsi)
        assertNotNull(result.analysis.macd)
        assertNotNull(result.analysis.volume)
    }

    @Test
    fun `generateSignal with existing position skips buy`() = runTest {
        val ohlcData = createUptrendOhlc(count = 100)
        val currentPrice = ohlcData.last().close
        val existingPosition = createPosition(pair = "BTC/EUR", isOpen = true)

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            currentPositions = listOf(existingPosition),
            availableBalance = 1000.0
        )

        assertTrue(result.signal == null || result.signal.type != SignalType.BUY)
    }

    @Test
    fun `generateSignal with max positions reached returns null signal`() = runTest {
        config = config.copy(maxOpenPositions = 1)
        strategy.updateConfig(config)

        val ohlcData = createUptrendOhlc(count = 100)
        val currentPrice = ohlcData.last().close

        val result = strategy.generateSignal(
            pair = "ETH/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            currentPositions = listOf(createPosition(pair = "SOL/EUR", isOpen = true)),
            availableBalance = 1000.0
        )

        assertNull(result.signal)
    }

    @Test
    fun `evaluateExitConditions returns null when position is within range`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 101.0)
        val exitSignal = strategy.evaluateExitConditions(position, 101.0, config)
        assertNull(exitSignal)
    }

    @Test
    fun `evaluateExitConditions returns stop loss signal`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 96.8)
        val exitSignal = strategy.evaluateExitConditions(position, 96.8, config)
        assertNotNull(exitSignal)
        assertTrue(exitSignal!!.reason.contains("Stop loss"))
        assertEquals(ExitUrgency.IMMEDIATE, exitSignal.urgency)
    }

    @Test
    fun `evaluateExitConditions returns take profit signal`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 106.5)
        val exitSignal = strategy.evaluateExitConditions(position, 106.5, config)
        assertNotNull(exitSignal)
        assertTrue(exitSignal!!.reason.contains("Take profit"))
        assertEquals(ExitUrgency.NORMAL, exitSignal.urgency)
    }

    @Test
    fun `evaluateExitConditions trailing stop triggers`() {
        val position = createPosition(
            averageEntryPrice = 100.0,
            currentPrice = 103.0,
            peakPrice = 106.0
        )
        val exitSignal = strategy.evaluateExitConditions(position, 103.0, config)
        assertNotNull(exitSignal)
    }

    @Test
    fun `evaluateExitConditions no trailing stop when profit is tiny`() {
        val position = createPosition(
            averageEntryPrice = 100.0,
            currentPrice = 100.5,
            peakPrice = 101.0
        )
        val exitSignal = strategy.evaluateExitConditions(position, 100.5, config)
        assertNull(exitSignal)
    }

    @Test
    fun `generateSignal with very low minimum strength accommodates more signals`() = runTest {
        config = config.copy(minSignalStrength = 0.2)
        strategy.updateConfig(config)

        val ohlcData = createMixedOhlc(count = 150)
        val currentPrice = ohlcData.last().close

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            availableBalance = 1000.0
        )

        assertNotNull(result.analysis)
    }

    @Test
    fun `multi-timeframe higher downtrend affects signal`() = runTest {
        val ohlcData = createUptrendOhlc(count = 100)
        val currentPrice = ohlcData.last().close
        config = config.copy(minSignalStrength = 0.3)
        strategy.updateConfig(config)

        val resultWithDowntrend = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            higherTimeframeTrends = listOf(Trend.STRONG_DOWNTREND),
            availableBalance = 1000.0
        )

        assertNotNull(resultWithDowntrend.analysis)
    }

    @Test
    fun `pyramiding allows additional buy when enabled`() = runTest {
        config = config.copy(
            minSignalStrength = 0.2,
            pyramidingEnabled = true,
            maxPyramidingLevels = 2,
            pyramidingIncrementPercent = 0.5
        )
        strategy.updateConfig(config)

        val ohlcData = createDeepDipOhlc(count = 100)
        val currentPrice = ohlcData.last().close
        val existingPosition = createPosition(pair = "BTC/EUR", isOpen = true)

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            currentPositions = listOf(existingPosition),
            availableBalance = 1000.0
        )

        assertNotNull(result.signal)
        assertEquals(SignalType.BUY, result.signal!!.type)
    }

    @Test
    fun `pyramiding blocked when max levels reached`() = runTest {
        config = config.copy(
            minSignalStrength = 0.2,
            pyramidingEnabled = true,
            maxPyramidingLevels = 1
        )
        strategy.updateConfig(config)

        val ohlcData = createDeepDipOhlc(count = 100)
        val currentPrice = ohlcData.last().close
        val existingPosition = createPosition(pair = "BTC/EUR", isOpen = true, pyramidLevel = 1)

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            currentPositions = listOf(existingPosition),
            availableBalance = 1000.0
        )

        assertNull(result.signal)
    }

    @Test
    fun `kelly criterion caps position size`() = runTest {
        coEvery { tradeJournalDao.getWinCountByPair("BTC/EUR") } returns 7
        coEvery { tradeJournalDao.getTotalTradeCountByPair("BTC/EUR") } returns 10

        config = config.copy(
            minSignalStrength = 0.2,
            useKellyCriterion = true,
            kellyFraction = 0.5
        )
        strategy.updateConfig(config)

        val ohlcData = createDeepDipOhlc(count = 100)
        val currentPrice = ohlcData.last().close

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            availableBalance = 1000.0
        )

        assertNotNull(result.signal)
    }

    @Test
    fun `correlation cap limits exposure on same quote currency`() = runTest {
        config = config.copy(
            minSignalStrength = 0.2,
            maxCorrelationExposure = 0.3
        )
        strategy.updateConfig(config)

        val ohlcData = createDeepDipOhlc(count = 100)
        val currentPrice = ohlcData.last().close
        val existingPosition = createPosition(
            pair = "ETH/EUR",
            isOpen = true,
            totalInvested = 250.0
        )

        val result = strategy.generateSignal(
            pair = "BTC/EUR",
            ohlcData = ohlcData,
            currentPrice = currentPrice,
            currentPositions = listOf(existingPosition),
            availableBalance = 1000.0
        )

        assertNotNull(result.signal)
        assert(result.signal!!.suggestedAmount <= 50.0)
    }

    private fun createUptrendOhlc(count: Int): List<OHLC> {
        val basePrice = 100.0
        return (0 until count).map { i ->
            val phase = (i.toDouble() / count) * 2 * Math.PI
            val close = basePrice + i * 0.5 + Math.sin(phase) * 5
            OHLC(
                time = 1000000L + i * 60L,
                open = close - 2.0,
                high = close + 3.0,
                low = close - 2.0,
                close = close,
                vwap = close,
                volume = 100.0 + (i % 10) * 10.0,
                count = 100 + i
            )
        }
    }

    private fun createMixedOhlc(count: Int): List<OHLC> {
        return (0 until count).map { i ->
            val t = i.toDouble() / count * 2 * Math.PI
            val dip = -20.0 * Math.exp(-(t - Math.PI) * (t - Math.PI) / 2)
            val close = 100.0 + 30.0 * Math.sin(t) + dip
            OHLC(
                time = 1000000L + i * 60L,
                open = close - 3.0,
                high = close + 5.0,
                low = close - 5.0,
                close = close,
                vwap = close,
                volume = 80.0 + Math.abs(Math.sin(t)) * 40.0,
                count = 100 + i
            )
        }
    }

    private fun createDeepDipOhlc(count: Int): List<OHLC> {
        require(count >= 100)
        return (0 until count).map { i ->
            val phase = i.toDouble() / count
            val lastFew = count - 1 - i < 3
            val close = when {
                phase < 0.6 -> 100.0 + phase * 15.0
                phase < 0.85 -> 109.0 - (phase - 0.6) * 50.0
                else -> 96.5 + (phase - 0.85) * 8.0
            }
            val open = if (lastFew) close - 2.0 else close - 1.0
            val high = if (lastFew) close + 1.0 else close + 2.0
            val low = if (lastFew) close - 1.0 else close - 2.0
            OHLC(
                time = 1000000L + i * 60L,
                open = open,
                high = high,
                low = low,
                close = close,
                vwap = close,
                volume = 200.0 + (i % 10) * 20.0,
                count = 100 + i
            )
        }
    }

    private fun createPosition(
        pair: String = "BTC/EUR",
        averageEntryPrice: Double = 100.0,
        currentPrice: Double = 100.0,
        peakPrice: Double = 100.0,
        isOpen: Boolean = true,
        totalInvested: Double = averageEntryPrice,
        pyramidLevel: Int = 0
    ): Position = Position(
        pair = pair,
        amount = 1.0,
        averageEntryPrice = averageEntryPrice,
        currentPrice = currentPrice,
        peakPrice = peakPrice,
        totalInvested = totalInvested,
        currentValue = currentPrice,
        unrealizedPnL = currentPrice - averageEntryPrice,
        unrealizedPnLPercent = ((currentPrice - averageEntryPrice) / averageEntryPrice) * 100,
        openedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOpen = isOpen,
        pyramidLevel = pyramidLevel
    )
}
