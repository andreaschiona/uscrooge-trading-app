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
    val overall: Double
) {
    companion object {
        fun calculate(analysis: TechnicalAnalysis): SignalStrength {
            val rsiScore = calculateRSIScore(analysis.rsi)
            val macdScore = calculateMACDScore(analysis.macd)
            val volumeScore = calculateVolumeScore(analysis.volume)
            val patternScore = analysis.candlestickPattern?.strength ?: 0.5
            val trendScore = calculateTrendScore(analysis.trend)

            // Weighted average
            val overall = (
                rsiScore * 0.25 +
                macdScore * 0.25 +
                volumeScore * 0.15 +
                patternScore * 0.15 +
                trendScore * 0.20
            )

            return SignalStrength(
                rsiScore = rsiScore,
                macdScore = macdScore,
                volumeScore = volumeScore,
                patternScore = patternScore,
                trendScore = trendScore,
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
    }
}
