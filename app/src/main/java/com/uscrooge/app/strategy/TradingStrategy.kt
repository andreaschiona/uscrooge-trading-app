package com.uscrooge.app.strategy

import com.google.gson.Gson
import com.uscrooge.app.analysis.TechnicalAnalyzer
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
    private val analyzer: TechnicalAnalyzer
) {

    @Volatile
    private var config: TradingConfig = TradingConfig()

    /**
     * Updates the active [TradingConfig]. Called by
     * [com.uscrooge.app.di.BrokerRegistry] whenever the user changes Settings.
     */
    fun updateConfig(newConfig: TradingConfig) {
        this.config = newConfig
    }

    fun generateSignal(
        pair: String,
        ohlcData: List<OHLC>,
        currentPrice: Double,
        currentPositions: List<Position> = emptyList(),
        availableBalance: Double = 0.0,
        higherTimeframeTrends: List<Trend> = emptyList()
    ): SignalResult {
        // Perform technical analysis
        val analysis = analyzer.analyze(pair, ohlcData, currentPrice, config)

        if (config.useVolumeAnalysis && analysis.volume.volumeRatio < config.minVolumeRatio) {
            return SignalResult(signal = null, analysis = analysis)
        }

        // Calculate signal strength
        val strength = SignalStrength.calculate(analysis)

        // Determine signal type
        val signalType = determineSignalType(strength, analysis, higherTimeframeTrends)

        // Check if we should generate a signal
        if (signalType == SignalType.HOLD || strength.overall < config.minSignalStrength) {
            return SignalResult(signal = null, analysis = analysis)
        }

        // Check if we already have a position for this pair
        val existingPosition = currentPositions.find { it.pair == pair && it.isOpen }
        if (signalType == SignalType.BUY && existingPosition != null) {
            // Already have a position, don't buy more
            return SignalResult(signal = null, analysis = analysis)
        }

        if (signalType == SignalType.SELL && existingPosition == null) {
            // No position to sell
            return SignalResult(signal = null, analysis = analysis)
        }

        // Check position limits
        if (signalType == SignalType.BUY) {
            val openPositionsCount = currentPositions.count { it.isOpen }
            if (openPositionsCount >= config.maxOpenPositions) {
                return SignalResult(signal = null, analysis = analysis)
            }
        }

        // Calculate entry, stop loss, and take profit
        val (entryPrice, stopLoss, takeProfit) = calculatePriceTargets(
            currentPrice = currentPrice,
            signalType = signalType,
            analysis = analysis
        )

        // Calculate suggested amount
        val suggestedAmount = calculatePositionSize(
            signalType = signalType,
            currentPrice = currentPrice,
            strength = strength.overall,
            existingPosition = existingPosition,
            availableBalance = availableBalance
        )

        if (suggestedAmount <= 0) {
            return SignalResult(signal = null, analysis = analysis)
        }

        // Calculate risk/reward ratio
        val riskRewardRatio = if (signalType == SignalType.BUY) {
            val risk = abs(entryPrice - stopLoss)
            val reward = abs(takeProfit - entryPrice)
            if (risk > 0) reward / risk else 0.0
        } else {
            val risk = abs(stopLoss - entryPrice)
            val reward = abs(entryPrice - takeProfit)
            if (risk > 0) reward / risk else 0.0
        }

        // Build reasons list
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
        higherTimeframeTrends: List<Trend> = emptyList()
    ): SignalType {
        var buyScore = 0.0
        var sellScore = 0.0

        // RSI signals
        when (analysis.rsi.signal) {
            RSI.Signal.OVERSOLD -> buyScore += 2.0
            RSI.Signal.BULLISH -> buyScore += 1.0
            RSI.Signal.BEARISH -> sellScore += 1.0
            RSI.Signal.OVERBOUGHT -> sellScore += 2.0
            RSI.Signal.NEUTRAL -> {}
        }

        // MACD signals
        when (analysis.macd.signal) {
            MACD.Signal.BULLISH_CROSSOVER -> buyScore += 2.0
            MACD.Signal.BULLISH -> buyScore += 1.0
            MACD.Signal.BEARISH -> sellScore += 1.0
            MACD.Signal.BEARISH_CROSSOVER -> sellScore += 2.0
            MACD.Signal.NEUTRAL -> {}
        }

        // Trend signals
        when (analysis.trend) {
            Trend.STRONG_UPTREND -> buyScore += 1.5
            Trend.UPTREND -> buyScore += 0.5
            Trend.STRONG_DOWNTREND -> sellScore += 1.5
            Trend.DOWNTREND -> sellScore += 0.5
            Trend.SIDEWAYS -> {}
        }

        // Candlestick pattern signals
        analysis.candlestickPattern?.let { pattern ->
            if (pattern.bullish) {
                buyScore += pattern.strength
            } else {
                sellScore += pattern.strength
            }
        }

        // Volume confirmation
        when (analysis.volume.signal) {
            VolumeAnalysis.Signal.HIGH_VOLUME,
            VolumeAnalysis.Signal.ABOVE_AVERAGE -> {
                // Volume confirms the direction
                if (buyScore > sellScore) buyScore += 0.5
                else if (sellScore > buyScore) sellScore += 0.5
            }
            VolumeAnalysis.Signal.LOW_VOLUME,
            VolumeAnalysis.Signal.BELOW_AVERAGE -> {
                // Weak volume, reduce confidence
                buyScore *= 0.8
                sellScore *= 0.8
            }
            else -> {}
        }

        // Bollinger Bands signals
        analysis.bollingerBands?.let { bb ->
            when (bb.signal) {
                BollingerBands.Signal.BELOW_LOWER -> buyScore += 1.0
                BollingerBands.Signal.NEAR_LOWER -> buyScore += 0.5
                BollingerBands.Signal.ABOVE_UPPER -> sellScore += 1.0
                BollingerBands.Signal.NEAR_UPPER -> sellScore += 0.5
                BollingerBands.Signal.MIDDLE -> {}
            }
        }

        // ADX signals
        analysis.adx?.let { adx ->
            when (adx.signal) {
                ADX.Signal.STRONG_TREND -> {
                    // Boost the dominant direction
                    if (buyScore > sellScore) buyScore += 0.5
                    else if (sellScore > buyScore) sellScore += 0.5
                }
                ADX.Signal.WEAK_TREND -> {
                    // Reduce confidence in ranging market
                    buyScore *= 0.85
                    sellScore *= 0.85
                }
                ADX.Signal.MODERATE_TREND -> {}
            }
        }

        // Stochastic RSI signals
        analysis.stochasticRSI?.let { stoch ->
            when (stoch.signal) {
                StochasticRSI.Signal.OVERSOLD -> buyScore += 0.75
                StochasticRSI.Signal.BULLISH -> buyScore += 0.3
                StochasticRSI.Signal.BEARISH -> sellScore += 0.3
                StochasticRSI.Signal.OVERBOUGHT -> sellScore += 0.75
                StochasticRSI.Signal.NEUTRAL -> {}
            }
        }

        // Multi-timeframe confirmation filter
        if (higherTimeframeTrends.isNotEmpty()) {
            for (htfTrend in higherTimeframeTrends) {
                when (htfTrend) {
                    Trend.STRONG_DOWNTREND -> buyScore -= 1.0
                    Trend.DOWNTREND -> buyScore -= 0.5
                    Trend.STRONG_UPTREND -> sellScore -= 1.0
                    Trend.UPTREND -> sellScore -= 0.5
                    Trend.SIDEWAYS -> {}
                }
            }
            // Clamp to zero
            if (buyScore < 0) buyScore = 0.0
            if (sellScore < 0) sellScore = 0.0
        }

        return when {
            buyScore > sellScore && buyScore >= 2.5 -> SignalType.BUY
            sellScore > buyScore && sellScore >= 2.5 -> SignalType.SELL
            else -> SignalType.HOLD
        }
    }

    private fun calculatePriceTargets(
        currentPrice: Double,
        signalType: SignalType,
        analysis: TechnicalAnalysis
    ): Triple<Double, Double, Double> {
        return when (signalType) {
            SignalType.BUY -> {
                // Use support as additional stop loss consideration
                val calculatedStopLoss = currentPrice * (1 - config.stopLossPercent / 100)
                val stopLoss = analysis.support?.let { support ->
                    if (support < calculatedStopLoss && support > calculatedStopLoss * 0.95) {
                        support * 0.99  // Place stop just below support
                    } else calculatedStopLoss
                } ?: calculatedStopLoss

                // Use resistance as take profit target
                val calculatedTakeProfit = currentPrice * (1 + config.takeProfitPercent / 100)
                val takeProfit = analysis.resistance?.let { resistance ->
                    if (resistance > currentPrice && resistance < calculatedTakeProfit) {
                        resistance * 0.99  // Take profit just before resistance
                    } else calculatedTakeProfit
                } ?: calculatedTakeProfit

                Triple(currentPrice, stopLoss, takeProfit)
            }

            SignalType.SELL -> {
                // For sell signals (closing position)
                val stopLoss = currentPrice * (1 + config.stopLossPercent / 100)
                val takeProfit = currentPrice * (1 - config.takeProfitPercent / 100)

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
        availableBalance: Double
    ): Double {
        return when (signalType) {
            SignalType.BUY -> {
                // Calculate base position size
                val baseAmount = config.getMaxAmountPerTrade(availableBalance)

                // Scale by signal strength
                val scaledAmount = baseAmount * (0.5 + (strength * 0.5))

                // Adjust for strong signals
                val adjustedAmount = if (strength >= config.strongSignalThreshold) {
                    scaledAmount * 1.2  // Increase by 20% for strong signals
                } else {
                    scaledAmount
                }

                // Make sure we don't exceed available balance
                min(adjustedAmount, availableBalance)
            }

            SignalType.SELL -> {
                // Sell the entire position
                existingPosition?.let {
                    it.amount * currentPrice
                } ?: 0.0
            }

            SignalType.HOLD -> 0.0
        }
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
