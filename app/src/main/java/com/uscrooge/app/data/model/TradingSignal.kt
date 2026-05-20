package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trading_signals")
data class TradingSignal(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pair: String,
    val type: SignalType,
    val strength: Double,           // 0.0 to 1.0
    val currentPrice: Double,
    val suggestedPrice: Double,     // Entry price
    val stopLoss: Double,
    val takeProfit: Double,
    val suggestedAmount: Double,    // In quote currency (EUR)
    val riskRewardRatio: Double,
    val timestamp: Long,
    val reasons: String,            // JSON array of reasons
    val status: SignalStatus,
    val executedAt: Long? = null,
    val executedPrice: Double? = null,
    val orderId: String? = null
) {
    fun getReasonsList(): List<String> {
        return try {
            com.google.gson.Gson().fromJson(reasons, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

enum class SignalType {
    BUY,
    SELL,
    HOLD
}

enum class SignalStatus {
    PENDING,        // Waiting for user action
    EXECUTING,      // Order being placed
    EXECUTED,       // Order successfully executed
    IGNORED,        // User ignored the signal
    FAILED,         // Order execution failed
    EXPIRED         // Signal too old
}

data class SignalStrength(
    val rsiScore: Double,
    val macdScore: Double,
    val volumeScore: Double,
    val patternScore: Double,
    val trendScore: Double,
    val bollingerScore: Double = 0.5,
    val adxScore: Double = 0.5,
    val stochRsiScore: Double = 0.5,
    val overall: Double
) {
    companion object {
        fun calculate(analysis: TechnicalAnalysis): SignalStrength {
            val rsiScore = calculateRSIScore(analysis.rsi)
            val macdScore = calculateMACDScore(analysis.macd)
            val volumeScore = calculateVolumeScore(analysis.volume)
            val patternScore = analysis.candlestickPattern?.strength ?: 0.5
            val trendScore = calculateTrendScore(analysis.trend)
            val bollingerScore = calculateBollingerScore(analysis.bollingerBands)
            val adxScore = calculateADXScore(analysis.adx)
            val stochRsiScore = calculateStochRSIScore(analysis.stochasticRSI)

            val hasNewIndicators = analysis.bollingerBands != null ||
                    analysis.adx != null || analysis.stochasticRSI != null

            val overall = if (hasNewIndicators) {
                // Adjusted weights when new indicators are available
                rsiScore * 0.20 +
                macdScore * 0.20 +
                volumeScore * 0.10 +
                patternScore * 0.10 +
                trendScore * 0.15 +
                bollingerScore * 0.10 +
                adxScore * 0.08 +
                stochRsiScore * 0.07
            } else {
                // Original weights for backward compatibility
                rsiScore * 0.25 +
                macdScore * 0.25 +
                volumeScore * 0.15 +
                patternScore * 0.15 +
                trendScore * 0.20
            }

            return SignalStrength(
                rsiScore = rsiScore,
                macdScore = macdScore,
                volumeScore = volumeScore,
                patternScore = patternScore,
                trendScore = trendScore,
                bollingerScore = bollingerScore,
                adxScore = adxScore,
                stochRsiScore = stochRsiScore,
                overall = overall
            )
        }

        private fun calculateRSIScore(rsi: RSI): Double {
            return when (rsi.signal) {
                RSI.Signal.OVERSOLD -> 1.0
                RSI.Signal.BULLISH -> 0.7
                RSI.Signal.NEUTRAL -> 0.5
                RSI.Signal.BEARISH -> 0.3
                RSI.Signal.OVERBOUGHT -> 0.0
            }
        }

        private fun calculateMACDScore(macd: MACD): Double {
            return when (macd.signal) {
                MACD.Signal.BULLISH_CROSSOVER -> 1.0
                MACD.Signal.BULLISH -> 0.7
                MACD.Signal.NEUTRAL -> 0.5
                MACD.Signal.BEARISH -> 0.3
                MACD.Signal.BEARISH_CROSSOVER -> 0.0
            }
        }

        private fun calculateVolumeScore(volume: VolumeAnalysis): Double {
            return when (volume.signal) {
                VolumeAnalysis.Signal.HIGH_VOLUME -> 1.0
                VolumeAnalysis.Signal.ABOVE_AVERAGE -> 0.8
                VolumeAnalysis.Signal.AVERAGE -> 0.5
                VolumeAnalysis.Signal.BELOW_AVERAGE -> 0.3
                VolumeAnalysis.Signal.LOW_VOLUME -> 0.1
            }
        }

        private fun calculateTrendScore(trend: Trend): Double {
            return when (trend) {
                Trend.STRONG_UPTREND -> 1.0
                Trend.UPTREND -> 0.75
                Trend.SIDEWAYS -> 0.5
                Trend.DOWNTREND -> 0.25
                Trend.STRONG_DOWNTREND -> 0.0
            }
        }

        private fun calculateBollingerScore(bb: BollingerBands?): Double {
            if (bb == null) return 0.5
            return when (bb.signal) {
                BollingerBands.Signal.BELOW_LOWER -> 1.0
                BollingerBands.Signal.NEAR_LOWER -> 0.8
                BollingerBands.Signal.MIDDLE -> 0.5
                BollingerBands.Signal.NEAR_UPPER -> 0.2
                BollingerBands.Signal.ABOVE_UPPER -> 0.0
            }
        }

        private fun calculateADXScore(adx: ADX?): Double {
            if (adx == null) return 0.5
            return when (adx.signal) {
                ADX.Signal.STRONG_TREND -> 0.9
                ADX.Signal.MODERATE_TREND -> 0.5
                ADX.Signal.WEAK_TREND -> 0.2
            }
        }

        private fun calculateStochRSIScore(stochRsi: StochasticRSI?): Double {
            if (stochRsi == null) return 0.5
            return when (stochRsi.signal) {
                StochasticRSI.Signal.OVERSOLD -> 1.0
                StochasticRSI.Signal.BULLISH -> 0.7
                StochasticRSI.Signal.NEUTRAL -> 0.5
                StochasticRSI.Signal.BEARISH -> 0.3
                StochasticRSI.Signal.OVERBOUGHT -> 0.0
            }
        }
    }
}
