package com.uscrooge.app

import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class TechnicalAnalyzerParametricTest {

    private lateinit var analyzer: TechnicalAnalyzer

    @Before
    fun setup() {
        analyzer = TechnicalAnalyzer()
    }

    @Test
    fun `RSI with known values`() {
        val prices = listOf(
            44.34, 44.09, 44.15, 43.61, 44.33,
            44.83, 45.10, 45.42, 45.84, 46.08,
            45.89, 46.03, 45.61, 46.28, 46.28,
            46.00, 46.03, 46.41, 46.22, 43.50
        )
        val rsi = analyzer.calculateRSI(prices, 14)
        assertTrue("RSI value ${rsi.value} should be between 0 and 100", rsi.value in 0.0..100.0)
    }

    @Test
    fun `RSI 14-period with all identical prices is neutral`() {
        val prices = List(20) { 100.0 }
        val rsi = analyzer.calculateRSI(prices, 14)
        assertEquals("RSI should be 50 for flat prices", 50.0, rsi.value, 1.0)
        assertEquals(RSI.Signal.NEUTRAL, rsi.signal)
    }

    @Test
    fun `MACD calculation with diverse data`() {
        val prices = (1..100).map { 100.0 + Math.sin(it * 0.3) * 10.0 + it * 0.2 }
        val macd = analyzer.calculateMACD(prices, 12, 26, 9)
        assertNotNull(macd)
        assertTrue(macd.macdLine.isFinite())
        assertTrue(macd.signalLine.isFinite())
        assertTrue(macd.histogram.isFinite())
    }

    @Test
    fun `Bollinger Bands with volatile data`() {
        val prices = List(30) { 100.0 + (it % 7 - 3) * 15.0 }
        val bb = analyzer.calculateBollingerBands(prices, 20, 2.0)
        assertNotNull(bb)
        assertTrue("Upper band should be > middle band", bb.upper > bb.middle)
        assertTrue("Lower band should be < middle band", bb.lower < bb.middle)
        assertTrue("Bandwidth should be positive", bb.bandwidth > 0)
    }

    @Test
    fun `ADX calculation with OHLC data`() {
        val ohlc = List(30) { i ->
            OHLC(
                time = i.toLong(),
                open = 100.0 + i * 1.5,
                high = 105.0 + i * 1.5 + (i % 3) * 2.0,
                low = 95.0 + i * 1.5 - (i % 3) * 2.0,
                close = 100.0 + i * 1.5 + (i % 5) * 1.0,
                vwap = 100.0 + i * 1.5,
                volume = 1000.0,
                count = 100
            )
        }
        val adx = analyzer.calculateADX(ohlc, 14)
        assertNotNull(adx)
        assertTrue("ADX should be between 0 and 100", adx.value in 0.0..100.0)
    }

    @Test
    fun `Stochastic RSI calculation`() {
        val closePrices = (1..30).map { 100.0 + sin(it * 0.5) * 15.0 }
        val stochRsi = analyzer.calculateStochasticRSI(closePrices, 14, 14, 3)
        assertNotNull(stochRsi)
        assertTrue("StochRSI K should be between 0 and 100", stochRsi.k in 0.0..100.0)
        assertTrue("StochRSI D should be between 0 and 100", stochRsi.d in 0.0..100.0)
    }

    @Test
    fun `Support and resistance levels are ordered`() {
        val ohlc = List(50) { i ->
            OHLC(
                time = i.toLong(),
                open = 100.0 + (i % 10) * 5.0,
                high = 105.0 + (i % 10) * 5.0,
                low = 95.0 + (i % 10) * 5.0,
                close = 100.0 + (i % 10) * 5.0,
                vwap = 100.0 + (i % 10) * 5.0,
                volume = 1000.0,
                count = 100
            )
        }
        val (support, resistance) = analyzer.findSupportResistance(ohlc)
        if (support != null && resistance != null) {
            assertTrue("Support should be below resistance", support < resistance)
        }
    }

    @Test
    fun `Candlestick pattern detection`() {
        val ohlc = listOf(
            OHLC(0, 102.0, 105.0, 95.0, 101.5, 100.0, 1000.0, 100),
            OHLC(1, 101.5, 102.0, 101.0, 101.8, 101.5, 800.0, 80),
            OHLC(2, 101.8, 102.5, 101.2, 102.3, 102.0, 1200.0, 150)
        )
        val pattern = analyzer.detectCandlestickPattern(ohlc)
        if (pattern != null) {
            assertTrue(pattern.strength >= 0.0)
        }
    }

    @Test
    fun `Trend detection with strong trend data`() {
        val uptrend = List(30) { 100.0 + it * 3.0 }
        assertEquals(Trend.STRONG_UPTREND, analyzer.detectTrend(uptrend))

        val downtrend = List(30) { 200.0 - it * 3.0 }
        assertEquals(Trend.STRONG_DOWNTREND, analyzer.detectTrend(downtrend))
    }
}
