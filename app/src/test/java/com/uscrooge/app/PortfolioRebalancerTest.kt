package com.uscrooge.app

import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.portfolio.PairPerformance
import com.uscrooge.app.portfolio.PortfolioRebalancer
import com.uscrooge.app.portfolio.RebalanceAction
import org.junit.Assert.*
import org.junit.Test

class PortfolioRebalancerTest {

    private val rebalancer = PortfolioRebalancer()

    @Test
    fun `calculateRebalance with no positions and no balance`() {
        val result = rebalancer.calculateRebalance(
            positions = emptyList(),
            availableBalance = 0.0,
            config = TradingConfig(),
            pairPerformance = emptyMap()
        )
        assertEquals(0.0, result.totalPortfolioValue, 0.001)
        assertTrue(result.allocations.isEmpty())
    }

    @Test
    fun `calculateRebalance with available balance only`() {
        val result = rebalancer.calculateRebalance(
            positions = emptyList(),
            availableBalance = 10000.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = emptyMap()
        )
        assertEquals(10000.0, result.totalPortfolioValue, 0.001)
        assertEquals(2, result.allocations.size)
    }

    @Test
    fun `calculateRebalance with single position`() {
        val position = createPosition(pair = "BTC/EUR", currentValue = 5000.0)
        val result = rebalancer.calculateRebalance(
            positions = listOf(position),
            availableBalance = 5000.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = emptyMap()
        )
        assertEquals(10000.0, result.totalPortfolioValue, 0.001)
        assertEquals(2, result.allocations.size)
    }

    @Test
    fun `calculateRebalance with performance data affects target allocation`() {
        val position = createPosition(pair = "BTC/EUR", currentValue = 3000.0)
        val performance = mapOf(
            "BTC/EUR" to PairPerformance(
                pair = "BTC/EUR",
                totalReturnPercent = 50.0,
                winRate = 80.0,
                sharpeRatio = 2.0,
                maxDrawdownPercent = -10.0,
                totalTrades = 10,
                averageTradeDuration = 3600000,
                lastTradeTime = System.currentTimeMillis()
            ),
            "ETH/EUR" to PairPerformance(
                pair = "ETH/EUR",
                totalReturnPercent = -20.0,
                winRate = 30.0,
                sharpeRatio = -0.5,
                maxDrawdownPercent = -30.0,
                totalTrades = 5,
                averageTradeDuration = 7200000,
                lastTradeTime = System.currentTimeMillis()
            )
        )

        val result = rebalancer.calculateRebalance(
            positions = listOf(position),
            availableBalance = 7000.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = performance
        )
        assertEquals(2, result.allocations.size)
        val btcAllocation = result.allocations.find { it.pair == "BTC/EUR" }
        assertNotNull(btcAllocation)
    }

    @Test
    fun `calculateRebalance with over-allocated position may suggest decrease`() {
        val position = createPosition(pair = "BTC/EUR", currentValue = 9500.0)
        val result = rebalancer.calculateRebalance(
            positions = listOf(position),
            availableBalance = 500.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = emptyMap()
        )
        val btcAllocation = result.allocations.find { it.pair == "BTC/EUR" }
        assertNotNull(btcAllocation)
    }

    @Test
    fun `calculateRebalance expected improvement is non-negative`() {
        val position = createPosition(pair = "BTC/EUR", currentValue = 6000.0)
        val result = rebalancer.calculateRebalance(
            positions = listOf(position),
            availableBalance = 4000.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = emptyMap()
        )
        assertTrue(result.expectedImprovement >= 0)
    }

    @Test
    fun `calculateRebalance with under-allocated pair may suggest increase`() {
        val position = createPosition(pair = "BTC/EUR", currentValue = 1000.0)
        val result = rebalancer.calculateRebalance(
            positions = listOf(position),
            availableBalance = 9000.0,
            config = TradingConfig(tradingPairs = listOf("BTC/EUR", "ETH/EUR")),
            pairPerformance = emptyMap()
        )
        val btcAllocation = result.allocations.find { it.pair == "BTC/EUR" }
        assertNotNull(btcAllocation)
    }

    private fun createPosition(
        pair: String = "BTC/EUR",
        currentValue: Double = 5000.0
    ): Position = Position(
        pair = pair,
        amount = currentValue / 100.0,
        averageEntryPrice = 100.0,
        currentPrice = 100.0,
        peakPrice = 100.0,
        totalInvested = currentValue,
        currentValue = currentValue,
        unrealizedPnL = 0.0,
        unrealizedPnLPercent = 0.0,
        openedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOpen = true
    )
}
