package com.uscrooge.app.analysis

import com.uscrooge.app.data.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TechnicalAnalyzer {

    fun analyze(
        pair: String,
        ohlcData: List<OHLC>,
        currentPrice: Double,
        config: TradingConfig
    ): TechnicalAnalysis {
        require(ohlcData.isNotEmpty()) { "OHLC data cannot be empty" }

        val closePrices = ohlcData.map { it.close }
        val volumes = ohlcData.map { it.volume }

        val rsi = calculateRSI(closePrices, config.rsiPeriod)
        val macd = calculateMACD(
            closePrices,
            config.macdFastPeriod,
            config.macdSlowPeriod,
            config.macdSignalPeriod
        )
        val volumeAnalysis = analyzeVolume(volumes)
        val candlestickPattern = if (config.useCandlestickPatterns) {
            detectCandlestickPattern(ohlcData.takeLast(3))
        } else null
        val trend = detectTrend(closePrices)
        val (support, resistance) = if (config.useSupportResistance) {
            findSupportResistance(ohlcData)
        } else Pair(null, null)

        val bollingerBands = if (closePrices.size >= 20) {
            calculateBollingerBands(closePrices)
        } else null

        val adx = if (ohlcData.size >= 28) {
            calculateADX(ohlcData)
        } else null

        val stochasticRSI = if (closePrices.size >= 28) {
            calculateStochasticRSI(closePrices)
        } else null

        val ichimoku = if (ohlcData.size >= 52) {
            calculateIchimoku(ohlcData)
        } else null

        val fibonacci = if (ohlcData.size >= 20) {
            calculateFibonacciRetracement(ohlcData)
        } else null

        val obv = if (ohlcData.size >= 2) {
            calculateOBV(ohlcData)
        } else null

        val mfi = if (ohlcData.size >= 16) {
            calculateMFI(ohlcData)
        } else null

        return TechnicalAnalysis(
            pair = pair,
            timestamp = System.currentTimeMillis(),
            currentPrice = currentPrice,
            rsi = rsi,
            macd = macd,
            volume = volumeAnalysis,
            candlestickPattern = candlestickPattern,
            trend = trend,
            support = support,
            resistance = resistance,
            bollingerBands = bollingerBands,
            adx = adx,
            stochasticRSI = stochasticRSI,
            ichimoku = ichimoku,
            fibonacci = fibonacci,
            obv = obv,
            mfi = mfi
        )
    }

    fun calculateRSI(prices: List<Double>, period: Int = 14): RSI {
        require(prices.size >= period + 1) { "Not enough data for RSI calculation" }

        var gains = 0.0
        var losses = 0.0

        // Calculate initial average gain and loss
        for (i in 1..period) {
            val change = prices[i] - prices[i - 1]
            if (change > 0) {
                gains += change
            } else {
                losses -= change
            }
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        // Calculate RSI for subsequent periods (smoothed)
        for (i in (period + 1) until prices.size) {
            val change = prices[i] - prices[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        val rsi = 100.0 - (100.0 / (1.0 + rs))

        return RSI(value = rsi, period = period)
    }

    fun calculateMACD(
        prices: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MACD {
        require(prices.size >= slowPeriod) { "Not enough data for MACD calculation" }

        val emaFastSeries = calculateEMASeries(prices, fastPeriod)
        val emaSlowSeries = calculateEMASeries(prices, slowPeriod)

        val macdSeries = mutableListOf<Double>()
        for (i in prices.indices) {
            val fast = emaFastSeries[i]
            val slow = emaSlowSeries[i]
            if (fast.isFinite() && slow.isFinite()) {
                macdSeries.add(fast - slow)
            }
        }

        val macdLine = macdSeries.lastOrNull() ?: 0.0

        val signalLine = if (macdSeries.size >= signalPeriod) {
            calculateEMA(macdSeries, signalPeriod)
        } else {
            macdSeries.average()
        }

        val histogram = macdLine - signalLine

        return MACD(
            macdLine = macdLine,
            signalLine = signalLine,
            histogram = histogram,
            fastPeriod = fastPeriod,
            slowPeriod = slowPeriod,
            signalPeriod = signalPeriod
        )
    }

    private fun calculateEMA(prices: List<Double>, period: Int): Double {
        require(prices.size >= period) { "Not enough data for EMA calculation" }

        val multiplier = 2.0 / (period + 1)
        var ema = prices.take(period).average() // Start with SMA

        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
        }

        return ema
    }

    private fun calculateEMASeries(prices: List<Double>, period: Int): List<Double> {
        require(prices.size >= period) { "Not enough data for EMA calculation" }

        val multiplier = 2.0 / (period + 1)
        val emaValues = MutableList(prices.size) { Double.NaN }
        var ema = prices.take(period).average()
        emaValues[period - 1] = ema

        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
            emaValues[i] = ema
        }

        return emaValues
    }

    fun analyzeVolume(volumes: List<Double>): VolumeAnalysis {
        require(volumes.isNotEmpty()) { "Volume data cannot be empty" }

        val currentVolume = volumes.last()
        val averageVolume = volumes.average()
        val volumeRatio = currentVolume / averageVolume

        val safeVolumeRatio = if (averageVolume > 0.0) volumeRatio else 0.0

        return VolumeAnalysis(
            currentVolume = currentVolume,
            averageVolume = averageVolume,
            volumeRatio = safeVolumeRatio
        )
    }

    fun detectCandlestickPattern(recentCandles: List<OHLC>): CandlestickPattern? {
        if (recentCandles.isEmpty()) return null

        val lastCandle = recentCandles.last()
        val body = abs(lastCandle.close - lastCandle.open)
        val range = lastCandle.high - lastCandle.low
        val bodyRatio = if (range > 0) body / range else 0.0

        val upperShadow = lastCandle.high - max(lastCandle.open, lastCandle.close)
        val lowerShadow = min(lastCandle.open, lastCandle.close) - lastCandle.low

        // Doji: Very small body
        if (bodyRatio < 0.1) {
            return CandlestickPattern.DOJI
        }

        // Hammer / Hanging Man: Long lower shadow, small upper shadow
        if (lowerShadow > body * 2 && upperShadow < body * 0.5) {
            return if (lastCandle.close > lastCandle.open) {
                CandlestickPattern.HAMMER
            } else {
                CandlestickPattern.HANGING_MAN
            }
        }

        // Inverted Hammer / Shooting Star: Long upper shadow, small lower shadow
        if (upperShadow > body * 2 && lowerShadow < body * 0.5) {
            return if (lastCandle.close > lastCandle.open) {
                CandlestickPattern.INVERTED_HAMMER
            } else {
                CandlestickPattern.SHOOTING_STAR
            }
        }

        // Multi-candle patterns
        if (recentCandles.size >= 2) {
            val prevCandle = recentCandles[recentCandles.size - 2]

            // Engulfing patterns
            val bullishEngulfing = lastCandle.close > lastCandle.open &&
                    prevCandle.close < prevCandle.open &&
                    lastCandle.close > prevCandle.open &&
                    lastCandle.open < prevCandle.close

            if (bullishEngulfing) {
                return CandlestickPattern.BULLISH_ENGULFING
            }

            val bearishEngulfing = lastCandle.close < lastCandle.open &&
                    prevCandle.close > prevCandle.open &&
                    lastCandle.close < prevCandle.open &&
                    lastCandle.open > prevCandle.close

            if (bearishEngulfing) {
                return CandlestickPattern.BEARISH_ENGULFING
            }
        }

        // Three candle patterns
        if (recentCandles.size >= 3) {
            val candle1 = recentCandles[recentCandles.size - 3]
            val candle2 = recentCandles[recentCandles.size - 2]
            val candle3 = recentCandles[recentCandles.size - 1]

            // Three White Soldiers: Three consecutive bullish candles
            val threeWhiteSoldiers = candle1.close > candle1.open &&
                    candle2.close > candle2.open &&
                    candle3.close > candle3.open &&
                    candle2.close > candle1.close &&
                    candle3.close > candle2.close

            if (threeWhiteSoldiers) {
                return CandlestickPattern.THREE_WHITE_SOLDIERS
            }

            // Three Black Crows: Three consecutive bearish candles
            val threeBlackCrows = candle1.close < candle1.open &&
                    candle2.close < candle2.open &&
                    candle3.close < candle3.open &&
                    candle2.close < candle1.close &&
                    candle3.close < candle2.close

            if (threeBlackCrows) {
                return CandlestickPattern.THREE_BLACK_CROWS
            }

            // Morning Star: Bearish, small body, bullish
            val candle2Range = candle2.high - candle2.low
            val candle2BodyRatio = if (candle2Range > 0.0) {
                abs(candle2.close - candle2.open) / candle2Range
            } else 0.0
            val morningStar = candle1.close < candle1.open &&
                    candle2BodyRatio < 0.3 &&
                    candle3.close > candle3.open &&
                    candle3.close > (candle1.open + candle1.close) / 2

            if (morningStar) {
                return CandlestickPattern.MORNING_STAR
            }

            // Evening Star: Bullish, small body, bearish
            val eveningStar = candle1.close > candle1.open &&
                    candle2BodyRatio < 0.3 &&
                    candle3.close < candle3.open &&
                    candle3.close < (candle1.open + candle1.close) / 2

            if (eveningStar) {
                return CandlestickPattern.EVENING_STAR
            }
        }

        return null
    }

    fun detectTrend(prices: List<Double>): Trend {
        if (prices.size < 10) return Trend.SIDEWAYS

        val recentPrices = prices.takeLast(20)
        val firstHalf = recentPrices.take(10).average()
        val secondHalf = recentPrices.takeLast(10).average()

        val changePercent = ((secondHalf - firstHalf) / firstHalf) * 100

        return when {
            changePercent > 5.0 -> Trend.STRONG_UPTREND
            changePercent > 2.0 -> Trend.UPTREND
            changePercent < -5.0 -> Trend.STRONG_DOWNTREND
            changePercent < -2.0 -> Trend.DOWNTREND
            else -> Trend.SIDEWAYS
        }
    }

    fun findSupportResistance(ohlcData: List<OHLC>): Pair<Double?, Double?> {
        if (ohlcData.size < 10) return Pair(null, null)

        val recentData = ohlcData.takeLast(50)
        val lows = recentData.map { it.low }
        val highs = recentData.map { it.high }

        // Find local minima for support
        val support = findLocalExtrema(lows, findMinima = true)

        // Find local maxima for resistance
        val resistance = findLocalExtrema(highs, findMinima = false)

        return Pair(support, resistance)
    }

    private fun findLocalExtrema(values: List<Double>, findMinima: Boolean): Double? {
        if (values.size < 3) return null

        val extrema = mutableListOf<Double>()

        for (i in 1 until values.size - 1) {
            val isExtrema = if (findMinima) {
                values[i] < values[i - 1] && values[i] < values[i + 1]
            } else {
                values[i] > values[i - 1] && values[i] > values[i + 1]
            }

            if (isExtrema) {
                extrema.add(values[i])
            }
        }

        return extrema.lastOrNull()
    }

    fun calculateBollingerBands(
        prices: List<Double>,
        period: Int = 20,
        stdDev: Double = 2.0
    ): BollingerBands {
        require(prices.size >= period) { "Not enough data for Bollinger Bands" }

        val recentPrices = prices.takeLast(period)
        val middle = recentPrices.average()
        val variance = recentPrices.sumOf { (it - middle) * (it - middle) } / period
        val sd = sqrt(variance)

        val upper = middle + stdDev * sd
        val lower = middle - stdDev * sd
        val bandwidth = if (middle > 0) (upper - lower) / middle else 0.0
        val currentPrice = prices.last()
        val percentB = if (upper - lower > 0) (currentPrice - lower) / (upper - lower) else 0.5

        return BollingerBands(
            upper = upper,
            middle = middle,
            lower = lower,
            bandwidth = bandwidth,
            percentB = percentB
        )
    }

    fun calculateADX(ohlcData: List<OHLC>, period: Int = 14): ADX {
        require(ohlcData.size >= period * 2) { "Not enough data for ADX calculation" }

        val trueRanges = mutableListOf<Double>()
        val plusDMs = mutableListOf<Double>()
        val minusDMs = mutableListOf<Double>()

        for (i in 1 until ohlcData.size) {
            val current = ohlcData[i]
            val previous = ohlcData[i - 1]

            val highDiff = current.high - previous.high
            val lowDiff = previous.low - current.low

            val plusDM = if (highDiff > lowDiff && highDiff > 0) highDiff else 0.0
            val minusDM = if (lowDiff > highDiff && lowDiff > 0) lowDiff else 0.0

            val tr = maxOf(
                current.high - current.low,
                abs(current.high - previous.close),
                abs(current.low - previous.close)
            )

            trueRanges.add(tr)
            plusDMs.add(plusDM)
            minusDMs.add(minusDM)
        }

        // Smooth using Wilder's method
        var smoothedTR = trueRanges.take(period).sum()
        var smoothedPlusDM = plusDMs.take(period).sum()
        var smoothedMinusDM = minusDMs.take(period).sum()

        val dxValues = mutableListOf<Double>()

        for (i in period until trueRanges.size) {
            smoothedTR = smoothedTR - (smoothedTR / period) + trueRanges[i]
            smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDMs[i]
            smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDMs[i]

            val plusDI = if (smoothedTR > 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
            val minusDI = if (smoothedTR > 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0

            val diSum = plusDI + minusDI
            val dx = if (diSum > 0) (abs(plusDI - minusDI) / diSum) * 100 else 0.0
            dxValues.add(dx)
        }

        val adxValue = if (dxValues.size >= period) {
            // Smooth ADX
            var adx = dxValues.take(period).average()
            for (i in period until dxValues.size) {
                adx = ((adx * (period - 1)) + dxValues[i]) / period
            }
            adx
        } else {
            dxValues.average()
        }

        // Get final +DI and -DI
        val finalPlusDI = if (smoothedTR > 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
        val finalMinusDI = if (smoothedTR > 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0

        return ADX(
            value = adxValue,
            plusDI = finalPlusDI,
            minusDI = finalMinusDI
        )
    }

    fun calculateATR(ohlcData: List<OHLC>, period: Int = 14): Double {
        require(ohlcData.size >= period + 1) { "Not enough data for ATR" }

        val trueRanges = mutableListOf<Double>()
        for (i in 1 until ohlcData.size) {
            val tr = maxOf(
                ohlcData[i].high - ohlcData[i].low,
                abs(ohlcData[i].high - ohlcData[i - 1].close),
                abs(ohlcData[i].low - ohlcData[i - 1].close)
            )
            trueRanges.add(tr)
        }

        var atr = trueRanges.take(period).average()

        for (i in period until trueRanges.size) {
            atr = ((atr * (period - 1)) + trueRanges[i]) / period
        }

        return atr
    }

    fun calculateStochasticRSI(
        prices: List<Double>,
        rsiPeriod: Int = 14,
        stochPeriod: Int = 14,
        kPeriod: Int = 3,
        dPeriod: Int = 3
    ): StochasticRSI {
        require(prices.size >= rsiPeriod + stochPeriod) { "Not enough data for Stochastic RSI" }

        // Calculate RSI series
        val rsiValues = mutableListOf<Double>()
        for (i in rsiPeriod until prices.size) {
            val slice = prices.subList(0, i + 1)
            val rsi = calculateRSI(slice, rsiPeriod)
            rsiValues.add(rsi.value)
        }

        if (rsiValues.size < stochPeriod) {
            return StochasticRSI(k = 50.0, d = 50.0)
        }

        // Calculate Stochastic of RSI
        val stochKValues = mutableListOf<Double>()
        for (i in (stochPeriod - 1) until rsiValues.size) {
            val window = rsiValues.subList(i - stochPeriod + 1, i + 1)
            val lowestRSI = window.min()
            val highestRSI = window.max()
            val stochK = if (highestRSI - lowestRSI > 0) {
                ((rsiValues[i] - lowestRSI) / (highestRSI - lowestRSI)) * 100
            } else 50.0
            stochKValues.add(stochK)
        }

        // Smooth K with SMA (kPeriod)
        val smoothedK = if (stochKValues.size >= kPeriod) {
            stochKValues.takeLast(kPeriod).average()
        } else {
            stochKValues.lastOrNull() ?: 50.0
        }

        // D is SMA of smoothed K values
        val smoothedD = if (stochKValues.size >= kPeriod + dPeriod - 1) {
            val kSmoothed = mutableListOf<Double>()
            for (i in (kPeriod - 1) until stochKValues.size) {
                kSmoothed.add(stochKValues.subList(i - kPeriod + 1, i + 1).average())
            }
            kSmoothed.takeLast(dPeriod).average()
        } else {
            smoothedK
        }

        return StochasticRSI(k = smoothedK, d = smoothedD)
    }

    fun calculateIchimoku(ohlcData: List<OHLC>): Ichimoku {
        require(ohlcData.size >= 52) { "Need at least 52 candles for Ichimoku" }

        val closes = ohlcData.map { it.close }
        val highs = ohlcData.map { it.high }
        val lows = ohlcData.map { it.low }

        val tenkanSen = (highs.takeLast(9).max() + lows.takeLast(9).min()) / 2.0
        val kijunSen = (highs.takeLast(26).max() + lows.takeLast(26).min()) / 2.0

        val senkouHigh52 = highs.takeLast(52).max()
        val senkouLow52 = lows.takeLast(52).min()
        val senkouSpanB = (senkouHigh52 + senkouLow52) / 2.0
        val senkouSpanA = (tenkanSen + kijunSen) / 2.0

        val chikouSpan = if (ohlcData.size >= 27) {
            closes[closes.size - 26]
        } else closes.last()

        return Ichimoku(
            tenkanSen = tenkanSen,
            kijunSen = kijunSen,
            senkouSpanA = senkouSpanA,
            senkouSpanB = senkouSpanB,
            chikouSpan = chikouSpan
        )
    }

    fun calculateFibonacciRetracement(ohlcData: List<OHLC>): FibonacciLevels {
        require(ohlcData.size >= 20) { "Need at least 20 candles for Fibonacci" }

        val recentData = ohlcData.takeLast(50)
        val highs = recentData.map { it.high }
        val lows = recentData.map { it.low }

        val swingHigh = highs.max()
        val swingLow = lows.min()
        val diff = swingHigh - swingLow

        val currentPrice = ohlcData.last().close
        val currentPriceRelative = if (diff > 0) {
            (currentPrice - swingLow) / diff
        } else 0.5

        return FibonacciLevels(
            swingHigh = swingHigh,
            swingLow = swingLow,
            retracement236 = swingHigh - diff * 0.236,
            retracement382 = swingHigh - diff * 0.382,
            retracement500 = swingHigh - diff * 0.5,
            retracement618 = swingHigh - diff * 0.618,
            retracement786 = swingHigh - diff * 0.786,
            extension1272 = swingHigh + diff * 0.272,
            extension1618 = swingHigh + diff * 0.618,
            currentPriceRelative = currentPriceRelative
        )
    }

    fun calculateOBV(ohlcData: List<OHLC>): OBV {
        require(ohlcData.size >= 2) { "Need at least 2 candles for OBV" }

        var obvValue = 0.0
        val obvSeries = mutableListOf<Double>()

        for (i in 1 until ohlcData.size) {
            val current = ohlcData[i]
            val previous = ohlcData[i - 1]

            if (current.close > previous.close) {
                obvValue += current.volume
            } else if (current.close < previous.close) {
                obvValue -= current.volume
            }
            obvSeries.add(obvValue)
        }

        val obvTrend = if (obvSeries.size >= 10) {
            val recent = obvSeries.takeLast(10)
            val firstHalf = recent.take(5).average()
            val secondHalf = recent.takeLast(5).average()
            val trend = secondHalf - firstHalf
            when {
                trend > 0 -> Trend.UPTREND
                trend < 0 -> Trend.DOWNTREND
                else -> Trend.SIDEWAYS
            }
        } else Trend.SIDEWAYS

        val closes = ohlcData.map { it.close }
        val priceTrend = if (closes.size >= 10) {
            val recentCloses = closes.takeLast(10)
            val priceFirstHalf = recentCloses.take(5).average()
            val priceSecondHalf = recentCloses.takeLast(5).average()
            val priceTrendVal = priceSecondHalf - priceFirstHalf
            when {
                priceTrendVal > 0 -> Trend.UPTREND
                priceTrendVal < 0 -> Trend.DOWNTREND
                else -> Trend.SIDEWAYS
            }
        } else Trend.SIDEWAYS

        val divergence = when {
            obvTrend == Trend.UPTREND && priceTrend == Trend.DOWNTREND -> OBV.DivergenceSignal.BULLISH_DIVERGENCE
            obvTrend == Trend.DOWNTREND && priceTrend == Trend.UPTREND -> OBV.DivergenceSignal.BEARISH_DIVERGENCE
            else -> OBV.DivergenceSignal.NO_DIVERGENCE
        }

        return OBV(
            value = obvValue,
            trend = obvTrend,
            divergence = divergence
        )
    }

    fun calculateMFI(ohlcData: List<OHLC>, period: Int = 14): MFI {
        require(ohlcData.size >= period + 1) { "Need at least ${period + 1} candles for MFI" }

        val typicalPrices = ohlcData.map { (it.high + it.low + it.close) / 3.0 }
        val moneyFlows = typicalPrices.zip(ohlcData.map { it.volume }) { tp, vol -> tp * vol }

        var positiveMF = 0.0
        var negativeMF = 0.0

        for (i in (ohlcData.size - period) until ohlcData.size) {
            if (i == 0) continue
            if (typicalPrices[i] > typicalPrices[i - 1]) {
                positiveMF += moneyFlows[i]
            } else {
                negativeMF += moneyFlows[i]
            }
        }

        val moneyRatio = if (negativeMF > 0) positiveMF / negativeMF else 100.0
        val mfiValue = 100.0 - (100.0 / (1.0 + moneyRatio))

        return MFI(value = mfiValue, period = period)
    }
}
