package com.uscrooge.app

import com.uscrooge.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class SignalStrengthTest {

    @Test
    fun `calculate with oversold RSI returns high overall score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 25.0),
            macd = MACD(macdLine = 1.0, signalLine = 0.5, histogram = 0.5),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.STRONG_UPTREND
        )
        val strength = SignalStrength.calculate(analysis)
        assertTrue(strength.rsiScore > 0.5)
        assertTrue(strength.overall > 0.5)
    }

    @Test
    fun `calculate with overbought RSI returns low RSI score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 75.0),
            macd = MACD(macdLine = -1.0, signalLine = -0.5, histogram = -0.5),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.STRONG_DOWNTREND
        )
        val strength = SignalStrength.calculate(analysis)
        assertEquals(0.0, strength.rsiScore, 0.001)
    }

    @Test
    fun `calculate with neutral indicators returns moderate score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 50.0),
            macd = MACD(macdLine = 0.0, signalLine = 0.0, histogram = 0.0),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.SIDEWAYS
        )
        val strength = SignalStrength.calculate(analysis)
        assertEquals(0.5, strength.rsiScore, 0.001)
        assertEquals(0.5, strength.volumeScore, 0.001)
        assertEquals(0.5, strength.trendScore, 0.001)
    }

    @Test
    fun `calculate with bullish MACD crossover returns high MACD score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 30.0),
            macd = MACD(macdLine = 2.0, signalLine = 1.0, histogram = 1.0),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.UPTREND
        )
        val strength = SignalStrength.calculate(analysis)
        assertTrue(strength.macdScore > 0.5)
    }

    @Test
    fun `calculate with bearish MACD crossover returns low MACD score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 50.0),
            macd = MACD(macdLine = -2.0, signalLine = -1.0, histogram = -1.0),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.DOWNTREND
        )
        val strength = SignalStrength.calculate(analysis)
        assertTrue(strength.macdScore < 0.5)
    }

    @Test
    fun `calculate with Bollinger Bands uses new weights`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 30.0),
            macd = MACD(macdLine = 1.0, signalLine = 0.5, histogram = 0.5),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.UPTREND,
            bollingerBands = BollingerBands(110.0, 100.0, 90.0, 0.2, 0.1),
            adx = ADX(30.0, 25.0, 15.0),
            stochasticRSI = StochasticRSI(k = 20.0, d = 15.0)
        )
        val strength = SignalStrength.calculate(analysis)
        assertTrue(strength.overall > 0)
    }

    @Test
    fun `calculate without new indicators uses original weights`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 30.0),
            macd = MACD(macdLine = 1.0, signalLine = 0.5, histogram = 0.5),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.UPTREND,
            bollingerBands = null,
            adx = null,
            stochasticRSI = null
        )
        val strength = SignalStrength.calculate(analysis)
        assertTrue(strength.overall > 0)
    }

    @Test
    fun `calculate with high volume returns high volume score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 50.0),
            macd = MACD(macdLine = 0.0, signalLine = 0.0, histogram = 0.0),
            volume = VolumeAnalysis(200.0, 100.0, 2.0),
            trend = Trend.SIDEWAYS
        )
        val strength = SignalStrength.calculate(analysis)
        assertEquals(1.0, strength.volumeScore, 0.001)
    }

    @Test
    fun `calculate with low volume returns low volume score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 50.0),
            macd = MACD(macdLine = 0.0, signalLine = 0.0, histogram = 0.0),
            volume = VolumeAnalysis(20.0, 100.0, 0.2),
            trend = Trend.SIDEWAYS
        )
        val strength = SignalStrength.calculate(analysis)
        assertEquals(0.1, strength.volumeScore, 0.001)
    }

    @Test
    fun `calculate with strong uptrend returns high trend score`() {
        val analysis = createAnalysis(
            rsi = RSI(value = 50.0),
            macd = MACD(macdLine = 0.0, signalLine = 0.0, histogram = 0.0),
            volume = VolumeAnalysis(100.0, 100.0, 1.0),
            trend = Trend.STRONG_UPTREND
        )
        val strength = SignalStrength.calculate(analysis)
        assertEquals(1.0, strength.trendScore, 0.001)
    }

    private fun createAnalysis(
        rsi: RSI,
        macd: MACD,
        volume: VolumeAnalysis,
        trend: Trend,
        bollingerBands: BollingerBands? = null,
        adx: ADX? = null,
        stochasticRSI: StochasticRSI? = null
    ): TechnicalAnalysis = TechnicalAnalysis(
        pair = "BTC/EUR",
        timestamp = System.currentTimeMillis(),
        currentPrice = 50000.0,
        rsi = rsi,
        macd = macd,
        volume = volume,
        candlestickPattern = null,
        trend = trend,
        support = null,
        resistance = null,
        bollingerBands = bollingerBands,
        adx = adx,
        stochasticRSI = stochasticRSI
    )
}
