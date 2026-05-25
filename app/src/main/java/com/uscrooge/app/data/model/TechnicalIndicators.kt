package com.uscrooge.app.data.model

data class TechnicalAnalysis(
    val pair: String,
    val timestamp: Long,
    val currentPrice: Double,
    val rsi: RSI,
    val macd: MACD,
    val volume: VolumeAnalysis,
    val candlestickPattern: CandlestickPattern?,
    val trend: Trend,
    val support: Double?,
    val resistance: Double?,
    val bollingerBands: BollingerBands? = null,
    val adx: ADX? = null,
    val stochasticRSI: StochasticRSI? = null
)

data class RSI(
    val value: Double,
    val period: Int = 14
) {
    val signal: Signal
        get() = when {
            value <= 30.0 -> Signal.OVERSOLD
            value >= 70.0 -> Signal.OVERBOUGHT
            value <= 40.0 -> Signal.BULLISH
            value >= 60.0 -> Signal.BEARISH
            else -> Signal.NEUTRAL
        }

    enum class Signal {
        OVERSOLD,      // Strong buy signal
        BULLISH,       // Moderate buy signal
        NEUTRAL,       // No clear signal
        BEARISH,       // Moderate sell signal
        OVERBOUGHT     // Strong sell signal
    }
}

data class MACD(
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double,
    val fastPeriod: Int = 12,
    val slowPeriod: Int = 26,
    val signalPeriod: Int = 9
) {
    val signal: Signal
        get() = when {
            histogram > 0.0 -> Signal.BULLISH
            histogram < 0.0 -> Signal.BEARISH
            else -> Signal.NEUTRAL
        }

    enum class Signal {
        BULLISH_CROSSOVER,   // Strong buy
        BULLISH,             // Moderate buy
        NEUTRAL,
        BEARISH,             // Moderate sell
        BEARISH_CROSSOVER    // Strong sell
    }
}

data class VolumeAnalysis(
    val currentVolume: Double,
    val averageVolume: Double,
    val volumeRatio: Double
) {
    val signal: Signal
        get() = when {
            volumeRatio > 1.5 -> Signal.HIGH_VOLUME
            volumeRatio > 1.2 -> Signal.ABOVE_AVERAGE
            volumeRatio < 0.5 -> Signal.LOW_VOLUME
            volumeRatio < 0.8 -> Signal.BELOW_AVERAGE
            else -> Signal.AVERAGE
        }

    enum class Signal {
        HIGH_VOLUME,       // Strong conviction
        ABOVE_AVERAGE,     // Good conviction
        AVERAGE,           // Normal
        BELOW_AVERAGE,     // Weak conviction
        LOW_VOLUME         // Very weak
    }
}

enum class CandlestickPattern(val bullish: Boolean, val strength: Double) {
    // Bullish patterns
    HAMMER(true, 0.7),
    INVERTED_HAMMER(true, 0.6),
    BULLISH_ENGULFING(true, 0.8),
    MORNING_STAR(true, 0.9),
    THREE_WHITE_SOLDIERS(true, 0.85),

    // Bearish patterns
    HANGING_MAN(false, 0.7),
    SHOOTING_STAR(false, 0.6),
    BEARISH_ENGULFING(false, 0.8),
    EVENING_STAR(false, 0.9),
    THREE_BLACK_CROWS(false, 0.85),

    // Neutral/Continuation
    DOJI(false, 0.0),
    SPINNING_TOP(false, 0.0)
}

enum class Trend {
    STRONG_UPTREND,
    UPTREND,
    SIDEWAYS,
    DOWNTREND,
    STRONG_DOWNTREND
}

data class BollingerBands(
    val upper: Double,
    val middle: Double,
    val lower: Double,
    val bandwidth: Double,
    val percentB: Double
) {
    val signal: Signal
        get() = when {
            percentB <= 0.0 -> Signal.BELOW_LOWER
            percentB <= 0.2 -> Signal.NEAR_LOWER
            percentB >= 1.0 -> Signal.ABOVE_UPPER
            percentB >= 0.8 -> Signal.NEAR_UPPER
            else -> Signal.MIDDLE
        }

    enum class Signal {
        BELOW_LOWER,   // Strong buy
        NEAR_LOWER,    // Buy signal
        MIDDLE,        // Neutral
        NEAR_UPPER,    // Sell signal
        ABOVE_UPPER    // Strong sell
    }
}

data class ADX(
    val value: Double,
    val plusDI: Double,
    val minusDI: Double
) {
    val signal: Signal
        get() = when {
            value >= 25.0 -> Signal.STRONG_TREND
            value < 20.0 -> Signal.WEAK_TREND
            else -> Signal.MODERATE_TREND
        }

    enum class Signal {
        STRONG_TREND,    // ADX > 25, trend is strong
        MODERATE_TREND,  // ADX 20-25
        WEAK_TREND       // ADX < 20, ranging market
    }
}

data class StochasticRSI(
    val k: Double,
    val d: Double
) {
    val signal: Signal
        get() = when {
            k <= 20.0 && d <= 20.0 -> Signal.OVERSOLD
            k <= 30.0 -> Signal.BULLISH
            k >= 80.0 && d >= 80.0 -> Signal.OVERBOUGHT
            k >= 70.0 -> Signal.BEARISH
            else -> Signal.NEUTRAL
        }

    enum class Signal {
        OVERSOLD,      // Strong buy
        BULLISH,       // Moderate buy
        NEUTRAL,
        BEARISH,       // Moderate sell
        OVERBOUGHT     // Strong sell
    }
}
