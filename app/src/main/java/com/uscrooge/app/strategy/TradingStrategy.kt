package com.uscrooge.app.strategy

import com.google.gson.Gson
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

data class SignalResult(
    val signal: TradingSignal?,
    val analysis: TechnicalAnalysis
)

@Singleton
class TradingStrategy @Inject constructor(
    private val analyzer: TechnicalAnalyzer,
    private val sentimentAnalyzer: SentimentAnalyzer,
    private val tradeJournalDao: TradeJournalDao
) {

    @Volatile
    private var config: TradingConfig = TradingConfig()

    private companion object {
        const val MIN_TRADES_FOR_KELLY = 10
        const val ATR_PERIOD = 14
    }

    /**
     * Updates the active [TradingConfig]. Called by
     * [com.uscrooge.app.di.BrokerRegistry] whenever the user changes Settings.
     */
    fun updateConfig(newConfig: TradingConfig) {
        this.config = newConfig
    }

    suspend fun generateSignal(
        pair: String,
        ohlcData: List<OHLC>,
        currentPrice: Double,
        currentPositions: List<Position> = emptyList(),
        availableBalance: Double = 0.0,
        higherTimeframeTrends: List<Trend> = emptyList(),
        sentiment: FearGreedIndex? = null
    ): SignalResult {
        val analysis = analyzer.analyze(pair, ohlcData, currentPrice, config).copy(
            sentiment = sentiment
        )

        if (config.useVolumeAnalysis && analysis.volume.volumeRatio < config.minVolumeRatio) {
            return SignalResult(signal = null, analysis = analysis)
        }

        val strength = SignalStrength.calculate(analysis)
        val signalType = determineSignalType(strength, analysis, higherTimeframeTrends, sentiment)

        if (signalType == SignalType.HOLD || strength.overall < config.minSignalStrength) {
            return SignalResult(signal = null, analysis = analysis)
        }

        val existingPosition = currentPositions.find { it.pair == pair && it.isOpen }
        val isPyramiding = config.pyramidingEnabled &&
            signalType == SignalType.BUY &&
            existingPosition != null &&
            existingPosition.pyramidLevel < config.maxPyramidingLevels

        if (signalType == SignalType.BUY && existingPosition != null && !isPyramiding) {
            return SignalResult(signal = null, analysis = analysis)
        }

        if (signalType == SignalType.SELL && existingPosition == null) {
            return SignalResult(signal = null, analysis = analysis)
        }

        if (signalType == SignalType.BUY && !isPyramiding) {
            val openPositionsCount = currentPositions.count { it.isOpen }
            if (openPositionsCount >= config.maxOpenPositions) {
                return SignalResult(signal = null, analysis = analysis)
            }
        }

        val atr = if (config.volatilityAdjustment) {
            analyzer.calculateATR(ohlcData, ATR_PERIOD)
        } else null

        val (entryPrice, stopLoss, takeProfit) = calculatePriceTargets(
            currentPrice = currentPrice,
            signalType = signalType,
            analysis = analysis,
            atr = atr
        )

        val winRate = if (config.useKellyCriterion) {
            val wins = tradeJournalDao.getWinCountByPair(pair)
            val total = tradeJournalDao.getTotalTradeCountByPair(pair)
            if (total >= MIN_TRADES_FOR_KELLY) wins.toDouble() / total else null
        } else null

        val suggestedAmount = calculatePositionSize(
            signalType = signalType,
            currentPrice = currentPrice,
            strength = strength.overall,
            existingPosition = existingPosition,
            availableBalance = availableBalance,
            atr = atr,
            winRate = winRate,
            currentPositions = currentPositions,
            pair = pair,
            isPyramiding = isPyramiding
        )

        if (suggestedAmount <= 0) {
            return SignalResult(signal = null, analysis = analysis)
        }

        val riskRewardRatio = if (signalType == SignalType.BUY) {
            val risk = abs(entryPrice - stopLoss)
            val reward = abs(takeProfit - entryPrice)
            if (risk > 0) reward / risk else 0.0
        } else {
            val risk = abs(stopLoss - entryPrice)
            val reward = abs(entryPrice - takeProfit)
            if (risk > 0) reward / risk else 0.0
        }

        val reasons = buildReasonsList(analysis, strength)

        return SignalResult(
            signal = TradingSignal(
                pair = pair,
                type = signalType,
                strength = strength.overall,
                currentPrice = currentPrice,
                suggestedPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                suggestedAmount = suggestedAmount,
                riskRewardRatio = riskRewardRatio,
                timestamp = System.currentTimeMillis(),
                reasons = Gson().toJson(reasons),
                status = SignalStatus.PENDING
            ),
            analysis = analysis
        )
    }

    private fun determineSignalType(
        strength: SignalStrength,
        analysis: TechnicalAnalysis,
        higherTimeframeTrends: List<Trend> = emptyList(),
        sentiment: FearGreedIndex? = null
    ): SignalType {
        var buyScore = scoreRSI(analysis) + scoreMACD(analysis) + scoreTrend(analysis)
        var sellScore = 0.0

        val candleScore = scoreCandlestickPattern(analysis)
        if (candleScore > 0) buyScore += candleScore else sellScore += abs(candleScore)

        val volumeAdjusted = applyVolumeConfidence(analysis, buyScore, sellScore)
        buyScore = volumeAdjusted.first
        sellScore = volumeAdjusted.second

        val bbScore = scoreBollinger(analysis)
        if (bbScore > 0) buyScore += bbScore else sellScore += abs(bbScore)

        val adxAdjusted = applyADXConfidence(analysis, buyScore, sellScore)
        buyScore = adxAdjusted.first
        sellScore = adxAdjusted.second

        val stochScore = scoreStochRSI(analysis)
        if (stochScore > 0) buyScore += stochScore else sellScore += abs(stochScore)

        val timeframeAdjusted = applyMultiTimeframeFilter(higherTimeframeTrends, buyScore, sellScore)
        buyScore = timeframeAdjusted.first
        sellScore = timeframeAdjusted.second

        val sentimentAdjustment = calculateSentimentAdjustment(sentiment)
        if (sentimentAdjustment > 0) {
            buyScore += sentimentAdjustment
        } else if (sentimentAdjustment < 0) {
            sellScore += abs(sentimentAdjustment)
        }

        return when {
            buyScore > sellScore && buyScore >= 2.5 -> SignalType.BUY
            sellScore > buyScore && sellScore >= 2.5 -> SignalType.SELL
            else -> SignalType.HOLD
        }
    }

    private fun scoreRSI(analysis: TechnicalAnalysis): Double = when (analysis.rsi.signal) {
        RSI.Signal.OVERSOLD -> 2.0
        RSI.Signal.BULLISH -> 1.0
        RSI.Signal.BEARISH -> -1.0
        RSI.Signal.OVERBOUGHT -> -2.0
        RSI.Signal.NEUTRAL -> 0.0
    }

    private fun scoreMACD(analysis: TechnicalAnalysis): Double = when (analysis.macd.signal) {
        MACD.Signal.BULLISH_CROSSOVER -> 2.0
        MACD.Signal.BULLISH -> 1.0
        MACD.Signal.BEARISH -> -1.0
        MACD.Signal.BEARISH_CROSSOVER -> -2.0
        MACD.Signal.NEUTRAL -> 0.0
    }

    private fun scoreTrend(analysis: TechnicalAnalysis): Double = when (analysis.trend) {
        Trend.STRONG_UPTREND -> 1.5
        Trend.UPTREND -> 0.5
        Trend.STRONG_DOWNTREND -> -1.5
        Trend.DOWNTREND -> -0.5
        Trend.SIDEWAYS -> 0.0
    }

    private fun scoreCandlestickPattern(analysis: TechnicalAnalysis): Double {
        val pattern = analysis.candlestickPattern ?: return 0.0
        return if (pattern.bullish) pattern.strength else -pattern.strength
    }

    private fun applyVolumeConfidence(
        analysis: TechnicalAnalysis,
        buyScore: Double,
        sellScore: Double
    ): Pair<Double, Double> {
        return when (analysis.volume.signal) {
            VolumeAnalysis.Signal.HIGH_VOLUME,
            VolumeAnalysis.Signal.ABOVE_AVERAGE -> {
                if (buyScore > sellScore) Pair(buyScore + 0.5, sellScore)
                else if (sellScore > buyScore) Pair(buyScore, sellScore + 0.5)
                else Pair(buyScore, sellScore)
            }
            VolumeAnalysis.Signal.LOW_VOLUME,
            VolumeAnalysis.Signal.BELOW_AVERAGE -> Pair(buyScore * 0.8, sellScore * 0.8)
            else -> Pair(buyScore, sellScore)
        }
    }

    private fun scoreBollinger(analysis: TechnicalAnalysis): Double {
        val bb = analysis.bollingerBands ?: return 0.0
        return when (bb.signal) {
            BollingerBands.Signal.BELOW_LOWER -> 1.0
            BollingerBands.Signal.NEAR_LOWER -> 0.5
            BollingerBands.Signal.ABOVE_UPPER -> -1.0
            BollingerBands.Signal.NEAR_UPPER -> -0.5
            BollingerBands.Signal.MIDDLE -> 0.0
        }
    }

    private fun applyADXConfidence(
        analysis: TechnicalAnalysis,
        buyScore: Double,
        sellScore: Double
    ): Pair<Double, Double> {
        val adx = analysis.adx ?: return Pair(buyScore, sellScore)
        return when (adx.signal) {
            ADX.Signal.STRONG_TREND -> {
                if (buyScore > sellScore) Pair(buyScore + 0.5, sellScore)
                else Pair(buyScore, sellScore + 0.5)
            }
            ADX.Signal.WEAK_TREND -> Pair(buyScore * 0.85, sellScore * 0.85)
            ADX.Signal.MODERATE_TREND -> Pair(buyScore, sellScore)
        }
    }

    private fun scoreStochRSI(analysis: TechnicalAnalysis): Double {
        val stoch = analysis.stochasticRSI ?: return 0.0
        return when (stoch.signal) {
            StochasticRSI.Signal.OVERSOLD -> 0.75
            StochasticRSI.Signal.BULLISH -> 0.3
            StochasticRSI.Signal.BEARISH -> -0.3
            StochasticRSI.Signal.OVERBOUGHT -> -0.75
            StochasticRSI.Signal.NEUTRAL -> 0.0
        }
    }

    private fun applyMultiTimeframeFilter(
        higherTimeframeTrends: List<Trend>,
        buyScore: Double,
        sellScore: Double
    ): Pair<Double, Double> {
        if (higherTimeframeTrends.isEmpty()) return Pair(buyScore, sellScore)
        var adjustedBuy = buyScore
        var adjustedSell = sellScore
        var hasStrongOpposingTrend = false
        for (htfTrend in higherTimeframeTrends) {
            when (htfTrend) {
                Trend.STRONG_DOWNTREND -> {
                    adjustedBuy -= 2.0
                    hasStrongOpposingTrend = true
                }
                Trend.DOWNTREND -> adjustedBuy -= 1.0
                Trend.STRONG_UPTREND -> {
                    adjustedSell -= 2.0
                    hasStrongOpposingTrend = true
                }
                Trend.UPTREND -> adjustedSell -= 1.0
                Trend.SIDEWAYS -> {}
            }
        }
        // Require HTF alignment: if a strong opposing trend exists, heavily suppress
        // the conflicting signal by reducing the dominant score further.
        if (hasStrongOpposingTrend) {
            if (adjustedBuy > adjustedSell) adjustedBuy -= 1.0
            else if (adjustedSell > adjustedBuy) adjustedSell -= 1.0
        }
        return Pair(maxOf(adjustedBuy, 0.0), maxOf(adjustedSell, 0.0))
    }

    private fun calculateSentimentAdjustment(sentiment: FearGreedIndex?): Double {
        if (!config.sentimentEnabled || sentiment == null) return 0.0
        return sentimentAnalyzer.calculateSentimentModifier(sentiment) * config.sentimentWeight * 10
    }

    private fun calculatePriceTargets(
        currentPrice: Double,
        signalType: SignalType,
        analysis: TechnicalAnalysis,
        atr: Double? = null
    ): Triple<Double, Double, Double> {
        // Compute ATR-based dynamic SL/TP percentages when volatility data is available
        val slPercent = if (atr != null && currentPrice > 0) {
            val atrRatioPct = (atr / currentPrice) * 100.0
            maxOf(config.stopLossPercent, atrRatioPct * config.stopLossATRMultiplier)
        } else {
            config.stopLossPercent
        }
        val tpPercent = config.takeProfitPercent * (slPercent / config.stopLossPercent)

        return when (signalType) {
            SignalType.BUY -> {
                // Use support as additional stop loss consideration
                val calculatedStopLoss = currentPrice * (1 - slPercent / 100)
                val stopLoss = analysis.support?.let { support ->
                    if (support < calculatedStopLoss && support > calculatedStopLoss * 0.95) {
                        support * 0.99  // Place stop just below support
                    } else calculatedStopLoss
                } ?: calculatedStopLoss

                // Use resistance as take profit target
                val calculatedTakeProfit = currentPrice * (1 + tpPercent / 100)
                val takeProfit = analysis.resistance?.let { resistance ->
                    if (resistance > currentPrice && resistance < calculatedTakeProfit) {
                        resistance * 0.99  // Take profit just before resistance
                    } else calculatedTakeProfit
                } ?: calculatedTakeProfit

                Triple(currentPrice, stopLoss, takeProfit)
            }

            SignalType.SELL -> {
                // For sell signals (closing position)
                val stopLoss = currentPrice * (1 + slPercent / 100)
                val takeProfit = currentPrice * (1 - tpPercent / 100)

                Triple(currentPrice, stopLoss, takeProfit)
            }

            SignalType.HOLD -> Triple(currentPrice, currentPrice, currentPrice)
        }
    }

    private fun calculatePositionSize(
        signalType: SignalType,
        currentPrice: Double,
        strength: Double,
        existingPosition: Position?,
        availableBalance: Double,
        atr: Double? = null,
        winRate: Double? = null,
        currentPositions: List<Position> = emptyList(),
        pair: String = "",
        isPyramiding: Boolean = false
    ): Double {
        return when (signalType) {
            SignalType.BUY -> {
                var amount = config.getMaxAmountPerTrade(availableBalance)

                amount *= (0.5 + (strength * 0.5))
                if (strength >= config.strongSignalThreshold) {
                    amount *= 1.2
                }

                // --- Kelly Criterion adjustment ---
                if (winRate != null && config.useKellyCriterion) {
                    val b = config.takeProfitPercent / config.stopLossPercent
                    val p = winRate
                    val q = 1.0 - p
                    val kellyFrac = (b * p - q) / b
                    if (kellyFrac > 0) {
                        val kellyAmount = availableBalance * kellyFrac * config.kellyFraction
                        amount = min(amount, kellyAmount)
                    } else {
                        return 0.0
                    }
                }

                // --- Volatility adjustment ---
                if (atr != null && config.volatilityAdjustment && currentPrice > 0) {
                    val atrRatio = atr / currentPrice
                    val volFactor = 1.0 - min(atrRatio * 10.0, 0.5)
                    amount *= volFactor
                }

                // --- Correlation-based cap ---
                if (config.maxCorrelationExposure < 1.0) {
                    val correlatedExposure = currentPositions
                        .filter { it.isOpen && isCorrelated(pair, it.pair) }
                        .sumOf { it.totalInvested }
                    val remainingExposure = (availableBalance * config.maxCorrelationExposure) - correlatedExposure
                    amount = min(amount, maxOf(remainingExposure, 0.0))
                }

                // --- Pyramiding ---
                if (isPyramiding && existingPosition != null) {
                    val pyramidSize = config.getMaxAmountPerTrade(availableBalance) *
                        config.pyramidingIncrementPercent
                    amount = min(pyramidSize, amount)
                }

                min(maxOf(amount, 0.0), availableBalance)
            }

            SignalType.SELL -> {
                existingPosition?.let {
                    it.amount * currentPrice
                } ?: 0.0
            }

            SignalType.HOLD -> 0.0
        }
    }

    private fun isCorrelated(pair1: String, pair2: String): Boolean {
        if (pair1 == pair2) return false
        val quote1 = pair1.substringAfter("/").uppercase()
        val quote2 = pair2.substringAfter("/").uppercase()
        return quote1 == quote2
    }

    private fun buildReasonsList(
        analysis: TechnicalAnalysis,
        strength: SignalStrength
    ): List<String> {
        val reasons = mutableListOf<String>()

        // RSI reasons
        when (analysis.rsi.signal) {
            RSI.Signal.OVERSOLD ->
                reasons.add("RSI oversold (${String.format("%.1f", analysis.rsi.value)}) - Strong buy signal")
            RSI.Signal.BULLISH ->
                reasons.add("RSI bullish (${String.format("%.1f", analysis.rsi.value)}) - Buy signal")
            RSI.Signal.OVERBOUGHT ->
                reasons.add("RSI overbought (${String.format("%.1f", analysis.rsi.value)}) - Strong sell signal")
            RSI.Signal.BEARISH ->
                reasons.add("RSI bearish (${String.format("%.1f", analysis.rsi.value)}) - Sell signal")
            else -> {}
        }

        // MACD reasons
        when (analysis.macd.signal) {
            MACD.Signal.BULLISH_CROSSOVER ->
                reasons.add("MACD bullish crossover - Strong momentum upward")
            MACD.Signal.BULLISH ->
                reasons.add("MACD above signal line - Bullish momentum")
            MACD.Signal.BEARISH_CROSSOVER ->
                reasons.add("MACD bearish crossover - Strong momentum downward")
            MACD.Signal.BEARISH ->
                reasons.add("MACD below signal line - Bearish momentum")
            else -> {}
        }

        // Volume reasons
        when (analysis.volume.signal) {
            VolumeAnalysis.Signal.HIGH_VOLUME ->
                reasons.add("High volume (${String.format("%.1f", analysis.volume.volumeRatio)}x average) - Strong conviction")
            VolumeAnalysis.Signal.ABOVE_AVERAGE ->
                reasons.add("Above average volume - Good conviction")
            VolumeAnalysis.Signal.LOW_VOLUME ->
                reasons.add("Low volume - Weak signal")
            else -> {}
        }

        // Candlestick pattern
        analysis.candlestickPattern?.let { pattern ->
            val direction = if (pattern.bullish) "Bullish" else "Bearish"
            reasons.add("$direction pattern: ${pattern.name.replace("_", " ").lowercase()}")
        }

        // Trend
        when (analysis.trend) {
            Trend.STRONG_UPTREND -> reasons.add("Strong uptrend detected")
            Trend.UPTREND -> reasons.add("Uptrend detected")
            Trend.STRONG_DOWNTREND -> reasons.add("Strong downtrend detected")
            Trend.DOWNTREND -> reasons.add("Downtrend detected")
            Trend.SIDEWAYS -> reasons.add("Sideways market")
        }

        // Bollinger Bands
        analysis.bollingerBands?.let { bb ->
            when (bb.signal) {
                BollingerBands.Signal.BELOW_LOWER ->
                    reasons.add("Price below lower Bollinger Band - Potential reversal buy")
                BollingerBands.Signal.NEAR_LOWER ->
                    reasons.add("Price near lower Bollinger Band - Buy zone")
                BollingerBands.Signal.ABOVE_UPPER ->
                    reasons.add("Price above upper Bollinger Band - Potential reversal sell")
                BollingerBands.Signal.NEAR_UPPER ->
                    reasons.add("Price near upper Bollinger Band - Sell zone")
                BollingerBands.Signal.MIDDLE -> {}
            }
        }

        // ADX
        analysis.adx?.let { adx ->
            when (adx.signal) {
                ADX.Signal.STRONG_TREND ->
                    reasons.add("ADX ${String.format("%.1f", adx.value)} - Strong trend confirms signal")
                ADX.Signal.WEAK_TREND ->
                    reasons.add("ADX ${String.format("%.1f", adx.value)} - Weak trend, reduced confidence")
                ADX.Signal.MODERATE_TREND -> {}
            }
        }

        // Stochastic RSI
        analysis.stochasticRSI?.let { stoch ->
            when (stoch.signal) {
                StochasticRSI.Signal.OVERSOLD ->
                    reasons.add("Stochastic RSI oversold (K=${String.format("%.1f", stoch.k)}) - Buy confirmation")
                StochasticRSI.Signal.OVERBOUGHT ->
                    reasons.add("Stochastic RSI overbought (K=${String.format("%.1f", stoch.k)}) - Sell confirmation")
                else -> {}
            }
        }

        // Support/Resistance
        analysis.support?.let {
            reasons.add("Support level at ${String.format("%.2f", it)}")
        }
        analysis.resistance?.let {
            reasons.add("Resistance level at ${String.format("%.2f", it)}")
        }

        // Sentiment
        if (config.sentimentEnabled) {
            analysis.sentiment?.let { fg ->
                reasons.add(sentimentAnalyzer.describeSentiment(fg))
            }
        }

        // Overall strength
        val strengthPercent = (strength.overall * 100).toInt()
        reasons.add("Overall signal strength: $strengthPercent%")

        return reasons
    }

    fun evaluateExitConditions(
        position: Position,
        currentPrice: Double,
        config: TradingConfig
    ): ExitSignal? {
        val currentPnLPercent = ((currentPrice - position.averageEntryPrice) / position.averageEntryPrice) * 100

        // Check stop loss
        if (currentPnLPercent <= -config.stopLossPercent) {
            return ExitSignal(
                reason = "Stop loss triggered",
                urgency = ExitUrgency.IMMEDIATE,
                suggestedPrice = currentPrice
            )
        }

        // Check take profit
        if (currentPnLPercent >= config.takeProfitPercent) {
            return ExitSignal(
                reason = "Take profit target reached",
                urgency = ExitUrgency.NORMAL,
                suggestedPrice = currentPrice
            )
        }

        // Check trailing stop — only triggers when current profit exceeds the trailing
        // distance (e.g. 1.5 %), ensuring a meaningful gain is locked in and
        // preventing premature exit from tiny price fluctuations.
        val peakPrice = maxOf(position.peakPrice, position.currentPrice)
        val trailingStopPrice = peakPrice * (1 - config.trailingStopPercent / 100)
        if (currentPrice < trailingStopPrice && currentPnLPercent > config.trailingStopPercent) {
            return ExitSignal(
                reason = "Trailing stop triggered",
                urgency = ExitUrgency.IMMEDIATE,
                suggestedPrice = currentPrice
            )
        }

        return null
    }
}

data class ExitSignal(
    val reason: String,
    val urgency: ExitUrgency,
    val suggestedPrice: Double
)

enum class ExitUrgency {
    IMMEDIATE,    // Execute immediately (stop loss)
    NORMAL,       // Execute at next opportunity
    OPTIONAL      // Consider exiting
}
