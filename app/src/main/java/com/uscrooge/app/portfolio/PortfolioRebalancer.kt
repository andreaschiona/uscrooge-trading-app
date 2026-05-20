package com.uscrooge.app.portfolio

import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.TradingConfig
import kotlin.math.max

data class PairAllocation(
    val pair: String,
    val currentAllocation: Double,
    val targetAllocation: Double,
    val deviation: Double,
    val action: RebalanceAction,
    val suggestedAmount: Double
)

enum class RebalanceAction {
    HOLD,
    INCREASE,
    DECREASE,
    CLOSE
}

data class RebalanceRecommendation(
    val allocations: List<PairAllocation>,
    val totalPortfolioValue: Double,
    val expectedImprovement: Double,
    val timestamp: Long = System.currentTimeMillis()
)

class PortfolioRebalancer {

    companion object {
        private const val MIN_REBALANCE_THRESHOLD = 0.05
        private const val LOOKBACK_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L
    }

    fun calculateRebalance(
        positions: List<Position>,
        availableBalance: Double,
        config: TradingConfig,
        pairPerformance: Map<String, PairPerformance>
    ): RebalanceRecommendation {
        val totalPortfolioValue = positions.sumOf { it.currentValue } + availableBalance

        if (totalPortfolioValue == 0.0) {
            return RebalanceRecommendation(
                allocations = emptyList(),
                totalPortfolioValue = 0.0,
                expectedImprovement = 0.0
            )
        }

        val pairCount = config.tradingPairs.size
        val equalWeight = 1.0 / pairCount

        val allocations = config.tradingPairs.map { pair ->
            val position = positions.find { it.pair == pair }
            val currentAllocation = if (position != null && position.isOpen) {
                position.currentValue / totalPortfolioValue
            } else {
                0.0
            }

            val performance = pairPerformance[pair]
            val targetAllocation = calculateTargetAllocation(
                pair = pair,
                equalWeight = equalWeight,
                performance = performance,
                currentAllocation = currentAllocation
            )

            val deviation = targetAllocation - currentAllocation
            val action = when {
                deviation > MIN_REBALANCE_THRESHOLD -> RebalanceAction.INCREASE
                deviation < -MIN_REBALANCE_THRESHOLD -> {
                    if (currentAllocation < 0.02) RebalanceAction.CLOSE
                    else RebalanceAction.DECREASE
                }
                else -> RebalanceAction.HOLD
            }

            val suggestedAmount = max(0.0, deviation * totalPortfolioValue)

            PairAllocation(
                pair = pair,
                currentAllocation = currentAllocation,
                targetAllocation = targetAllocation,
                deviation = deviation,
                action = action,
                suggestedAmount = suggestedAmount
            )
        }

        val expectedImprovement = allocations.sumOf { abs(it.deviation) } / allocations.size

        return RebalanceRecommendation(
            allocations = allocations,
            totalPortfolioValue = totalPortfolioValue,
            expectedImprovement = expectedImprovement
        )
    }

    private fun calculateTargetAllocation(
        pair: String,
        equalWeight: Double,
        performance: PairPerformance?,
        currentAllocation: Double
    ): Double {
        if (performance == null) {
            return equalWeight
        }

        val performanceScore = calculatePerformanceScore(performance)

        val minAllocation = 0.05
        val maxAllocation = 0.40

        val adjustedWeight = equalWeight * (1.0 + performanceScore * 0.5)

        return adjustedWeight.coerceIn(minAllocation, maxAllocation)
    }

    private fun calculatePerformanceScore(performance: PairPerformance): Double {
        val returnScore = performance.totalReturnPercent.coerceIn(-50.0, 100.0) / 100.0
        val winRateScore = (performance.winRate / 100.0) - 0.5
        val sharpeScore = performance.sharpeRatio.coerceIn(-2.0, 5.0) / 5.0
        val drawdownPenalty = performance.maxDrawdownPercent.coerceIn(-50.0, 0.0) / 50.0

        return (returnScore * 0.4 + winRateScore * 0.3 + sharpeScore * 0.2 + drawdownPenalty * 0.1).coerceIn(-1.0, 1.0)
    }

    private fun abs(value: Double): Double = kotlin.math.abs(value)
}

data class PairPerformance(
    val pair: String,
    val totalReturnPercent: Double,
    val winRate: Double,
    val sharpeRatio: Double,
    val maxDrawdownPercent: Double,
    val totalTrades: Int,
    val averageTradeDuration: Long,
    val lastTradeTime: Long
)
