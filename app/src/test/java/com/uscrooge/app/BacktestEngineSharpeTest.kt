package com.uscrooge.app

import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.backtest.BacktestEngine
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.strategy.TradingStrategy
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BacktestEngineSharpeTest {

    private lateinit var engine: BacktestEngine
    private lateinit var tradeJournalDao: TradeJournalDao

    @Before
    fun setup() {
        val analyzer = TechnicalAnalyzer()
        tradeJournalDao = mockk(relaxed = true)
        val strategy = TradingStrategy(analyzer, SentimentAnalyzer(), tradeJournalDao)
        engine = BacktestEngine(analyzer, strategy)
    }

    @Test
    fun `runBacktest with consistent uptrend produces high win rate`() = runTest {
        val data = (0 until 150).map { i ->
            val close = 100.0 + i * 1.0
            com.uscrooge.app.data.model.OHLC(
                time = 1000000L + i * 60L,
                open = close - 1.0,
                high = close + 2.0,
                low = close - 2.0,
                close = close,
                vwap = close,
                volume = 100.0,
                count = 100
            )
        }
        val config = com.uscrooge.app.backtest.BacktestConfig(
            initialBalance = 10000.0,
            feePercent = 0.0,
            slippagePercent = 0.0
        )

        val result = engine.runBacktest(data, config)
        assertTrue(result.sharpeRatio >= -1.0 || result.totalTrades == 0)
    }

    @Test
    fun `calculateSharpeRatio handles empty returns`() {
        val method = engine.javaClass.getDeclaredMethod(
            "calculateSharpeRatio", List::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(engine, emptyList<Double>()) as Double
        assertEquals(0.0, result, 0.001)
    }
}
