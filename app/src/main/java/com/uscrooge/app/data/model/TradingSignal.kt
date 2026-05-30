package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trading_signals",
    indices = [
        Index("pair"),
        Index("status"),
        Index("timestamp"),
        Index("pair", "status")
    ]
)
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
    EXPIRED,        // Signal too old (time-based expiry)
    MISSED          // Signal no longer valid (missed opportunity)
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
    val ichimokuScore: Double = 0.5,
    val fibonacciScore: Double = 0.5,
    val obvScore: Double = 0.5,
    val mfiScore: Double = 0.5,
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
            val ichimokuScore = calculateIchimokuScore(analysis.ichimoku)
            val fibonacciScore = calculateFibonacciScore(analysis.fibonacci)
            val obvScore = calculateOBVScore(analysis.obv)
            val mfiScore = calculateMFIScore(analysis.mfi)

            val hasExtendedIndicators = analysis.ichimoku != null ||
                    analysis.fibonacci != null || analysis.obv != null || analysis.mfi != null

            val hasNewIndicators = analysis.bollingerBands != null ||
                    analysis.adx != null || analysis.stochasticRSI != null

            val overall = when {
                hasExtendedIndicators -> {
                    rsiScore * 0.15 +
                    macdScore * 0.15 +
                    volumeScore * 0.08 +
                    patternScore * 0.08 +
                    trendScore * 0.12 +
                    bollingerScore * 0.08 +
                    adxScore * 0.06 +
                    stochRsiScore * 0.05 +
                    ichimokuScore * 0.08 +
                    fibonacciScore * 0.05 +
                    obvScore * 0.05 +
                    mfiScore * 0.05
                }
                hasNewIndicators -> {
                    rsiScore * 0.20 +
                    macdScore * 0.20 +
                    volumeScore * 0.10 +
                    patternScore * 0.10 +
                    trendScore * 0.15 +
                    bollingerScore * 0.10 +
                    adxScore * 0.08 +
                    stochRsiScore * 0.07
                }
                else -> {
                    rsiScore * 0.25 +
                    macdScore * 0.25 +
                    volumeScore * 0.15 +
                    patternScore * 0.15 +
                    trendScore * 0.20
                }
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
                ichimokuScore = ichimokuScore,
                fibonacciScore = fibonacciScore,
                obvScore = obvScore,
                mfiScore = mfiScore,
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

        private fun calculateIchimokuScore(ichimoku: Ichimoku?): Double {
            if (ichimoku == null) return 0.5
            return when (ichimoku.signal) {
                Ichimoku.Signal.BULLISH -> 1.0
                Ichimoku.Signal.MILD_BULLISH -> 0.7
                Ichimoku.Signal.NEUTRAL -> 0.5
                Ichimoku.Signal.MILD_BEARISH -> 0.3
                Ichimoku.Signal.BEARISH -> 0.0
            }
        }

        private fun calculateFibonacciScore(fib: FibonacciLevels?): Double {
            if (fib == null) return 0.5
            return when (fib.signal) {
                FibonacciLevels.Signal.OVERSOLD -> 1.0
                FibonacciLevels.Signal.BULLISH -> 0.7
                FibonacciLevels.Signal.NEUTRAL -> 0.5
                FibonacciLevels.Signal.BEARISH -> 0.3
                FibonacciLevels.Signal.OVERBOUGHT -> 0.0
            }
        }

        private fun calculateOBVScore(obv: OBV?): Double {
            if (obv == null) return 0.5
            var score = when (obv.trend) {
                Trend.UPTREND -> 0.7
                Trend.SIDEWAYS -> 0.5
                Trend.DOWNTREND -> 0.3
                else -> 0.5
            }
            if (obv.divergence == OBV.DivergenceSignal.BULLISH_DIVERGENCE) {
                score = 1.0
            } else if (obv.divergence == OBV.DivergenceSignal.BEARISH_DIVERGENCE) {
                score = 0.0
            }
            return score
        }

        private fun calculateMFIScore(mfi: MFI?): Double {
            if (mfi == null) return 0.5
            return when (mfi.signal) {
                MFI.Signal.OVERSOLD -> 1.0
                MFI.Signal.BULLISH -> 0.7
                MFI.Signal.NEUTRAL -> 0.5
                MFI.Signal.BEARISH -> 0.3
                MFI.Signal.OVERBOUGHT -> 0.0
            }
        }
    }
}
