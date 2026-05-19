package com.uscrooge.app

import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.model.MACD
import com.uscrooge.app.data.model.RSI
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TechnicalAnalyzerTest {

    private lateinit var analyzer: TechnicalAnalyzer

    @Before
    fun setup() {
        analyzer = TechnicalAnalyzer()
    }

    @Test
    fun `RSI calculation with oversold condition`() {
        val prices = listOf(
            100.0, 98.0, 96.0, 94.0, 92.0, 90.0, 88.0, 86.0,
            84.0, 82.0, 80.0, 78.0, 76.0, 74.0, 72.0
        )

        val rsi = analyzer.calculateRSI(prices, 14)

        assertTrue("RSI should indicate oversold", rsi.value <= 40.0)
        assertEquals(RSI.Signal.OVERSOLD, rsi.signal)
    }

    @Test
    fun `RSI calculation with overbought condition`() {
        val prices = listOf(
            100.0, 102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0,
            116.0, 118.0, 120.0, 122.0, 124.0, 126.0, 128.0
        )

        val rsi = analyzer.calculateRSI(prices, 14)

        assertTrue("RSI should indicate overbought", rsi.value >= 60.0)
    }

    @Test
    fun `MACD calculation returns valid values`() {
        val prices = List(50) { 100.0 + it * 0.5 }

        val macd = analyzer.calculateMACD(prices, 12, 26, 9)

        assertNotNull(macd)
        assertTrue(macd.macdLine.isFinite())
        assertTrue(macd.signalLine.isFinite())
        assertTrue(macd.histogram.isFinite())
    }

    @Test
    fun `Volume analysis detects high volume`() {
        val volumes = listOf(
            1000.0, 1100.0, 1050.0, 1200.0, 1150.0,
            1300.0, 1250.0, 1400.0, 2500.0  // Last one is high
        )

        val volumeAnalysis = analyzer.analyzeVolume(volumes)

        assertTrue("Should detect high volume", volumeAnalysis.volumeRatio > 1.5)
        assertEquals(
            com.uscrooge.app.data.model.VolumeAnalysis.Signal.HIGH_VOLUME,
            volumeAnalysis.signal
        )
    }

    @Test
    fun `Trend detection for uptrend`() {
        val prices = List(30) { 100.0 + it * 2.0 }  // Consistent uptrend

        val trend = analyzer.detectTrend(prices)

        assertTrue(
            "Should detect uptrend",
            trend == com.uscrooge.app.data.model.Trend.UPTREND ||
            trend == com.uscrooge.app.data.model.Trend.STRONG_UPTREND
        )
    }

    @Test
    fun `Trend detection for downtrend`() {
        val prices = List(30) { 200.0 - it * 2.0 }  // Consistent downtrend

        val trend = analyzer.detectTrend(prices)

        assertTrue(
            "Should detect downtrend",
            trend == com.uscrooge.app.data.model.Trend.DOWNTREND ||
            trend == com.uscrooge.app.data.model.Trend.STRONG_DOWNTREND
        )
    }

    @Test
    fun `Trend detection for sideways market`() {
        val prices = List(30) { 100.0 + (it % 2) * 0.5 }  // Oscillating

        val trend = analyzer.detectTrend(prices)

        assertEquals(
            com.uscrooge.app.data.model.Trend.SIDEWAYS,
            trend
        )
    }
}
