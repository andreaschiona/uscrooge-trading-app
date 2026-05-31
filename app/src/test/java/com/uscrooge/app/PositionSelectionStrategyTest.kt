package com.uscrooge.app

import com.uscrooge.app.data.api.CoinGeckoApiClient
import com.uscrooge.app.data.api.CoinGeckoMarket
import com.uscrooge.app.data.model.AssetRankingConfig
import com.uscrooge.app.strategy.PositionSelectionStrategy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PositionSelectionStrategyTest {

    private lateinit var strategy: PositionSelectionStrategy
    private lateinit var coinGeckoApi: CoinGeckoApiClient

    @Before
    fun setup() {
        coinGeckoApi = mockk(relaxed = true)
        strategy = PositionSelectionStrategy(coinGeckoApi)
    }

    @Test
    fun `selectTopPositions returns ranked assets sorted by composite score`() = runTest {
        val mockMarkets = listOf(
            createCoin("bitcoin", "btc", "Bitcoin", 50000.0, 1e12, 5e10, 2.0, 5.0, 10.0),
            createCoin("ethereum", "eth", "Ethereum", 3000.0, 5e11, 3e10, 3.0, 8.0, 15.0),
            createCoin("solana", "sol", "Solana", 150.0, 1e11, 5e9, 5.0, 12.0, 25.0),
            createCoin("cardano", "ada", "Cardano", 0.5, 2e10, 1e9, -1.0, -2.0, -5.0)
        )

        coEvery { coinGeckoApi.getTopCoinsByVolume(any(), any()) } returns Result.success(mockMarkets)

        val result = strategy.selectTopPositions(
            currentPositions = emptyList(),
            config = AssetRankingConfig(
                scanLimit = 50,
                minMarketCap = 1_000_000.0,
                minVolume24h = 100_000.0,
                maxAssetsToRank = 10
            )
        )

        assertTrue(result.isSuccess)
        val rankings = result.getOrNull()!!
        assertTrue(rankings.isNotEmpty())

        for (i in 1 until rankings.size) {
            assertTrue(
                "Rankings should be sorted by compositeScore descending",
                rankings[i - 1].compositeScore >= rankings[i].compositeScore
            )
        }

        assertTrue("Rank should be sequential", rankings.all { it.rank in 1..rankings.size })
    }

    @Test
    fun `selectTopPositions filters out low cap and low volume coins`() = runTest {
        val mockMarkets = listOf(
            createCoin("bigcoin", "BIG", "Big Coin", 100.0, 1e9, 5e7, 1.0, 2.0, 5.0),
            createCoin("smallcoin", "SML", "Small Coin", 0.01, 1e5, 1e3, 0.0, 0.0, 0.0),
            createCoin("tinycoin", "TNY", "Tiny Coin", 0.001, 1e3, 1e2, 0.0, 0.0, 0.0)
        )

        coEvery { coinGeckoApi.getTopCoinsByVolume(any(), any()) } returns Result.success(mockMarkets)

        val result = strategy.selectTopPositions(
            config = AssetRankingConfig(
                minMarketCap = 100_000_000.0,
                minVolume24h = 1_000_000.0
            )
        )

        assertTrue(result.isSuccess)
        val rankings = result.getOrNull()!!
        assertTrue("Only bigcoin should pass filters", rankings.all { it.symbol == "BIG" })
    }

    @Test
    fun `selectTopPositions excludes already held positions`() = runTest {
        val mockMarkets = listOf(
            createCoin("bitcoin", "btc", "Bitcoin", 50000.0, 1e12, 5e10, 2.0, 5.0, 10.0),
            createCoin("ethereum", "eth", "Ethereum", 3000.0, 5e11, 3e10, 3.0, 8.0, 15.0)
        )

        coEvery { coinGeckoApi.getTopCoinsByVolume(any(), any()) } returns Result.success(mockMarkets)

        val now = System.currentTimeMillis()
        val currentPositions = listOf(
            com.uscrooge.app.data.model.Position(
                pair = "BTC/EUR",
                amount = 1.0,
                averageEntryPrice = 48000.0,
                currentPrice = 50000.0,
                totalInvested = 48000.0,
                currentValue = 50000.0,
                unrealizedPnL = 2000.0,
                unrealizedPnLPercent = 4.17,
                openedAt = now - 86400000,
                updatedAt = now,
                isOpen = true
            )
        )

        val result = strategy.selectTopPositions(
            currentPositions = currentPositions,
            config = AssetRankingConfig(maxAssetsToRank = 10)
        )

        assertTrue(result.isSuccess)
        val rankings = result.getOrNull()!!
        assertTrue("BTC should be excluded since already held",
            rankings.none { it.symbol == "BTC" })
    }

    @Test
    fun `selectTopPositions handles API failure gracefully`() = runTest {
        coEvery { coinGeckoApi.getTopCoinsByVolume(any(), any()) } returns
            Result.failure(Exception("API rate limited"))

        val result = strategy.selectTopPositions()

        assertTrue(result.isFailure)
    }

    @Test
    fun `momentum scoring favors positive price changes`() = runTest {
        val mockMarkets = listOf(
            createCoin("rising", "RIS", "Rising Coin", 10.0, 1e9, 5e7, 2.0, 10.0, 30.0),
            createCoin("falling", "FAL", "Falling Coin", 10.0, 1e9, 5e7, -2.0, -10.0, -30.0)
        )

        coEvery { coinGeckoApi.getTopCoinsByVolume(any(), any()) } returns Result.success(mockMarkets)

        val result = strategy.selectTopPositions(config = AssetRankingConfig(maxAssetsToRank = 10))
        assertTrue(result.isSuccess)
        val rankings = result.getOrNull()!!

        val risingScore = rankings.first { it.symbol == "RIS" }.momentumScore
        val fallingScore = rankings.first { it.symbol == "FAL" }.momentumScore
        assertTrue("Rising coin should have higher momentum score than falling coin",
            risingScore > fallingScore)
    }

    private fun createCoin(
        id: String,
        symbol: String,
        name: String,
        price: Double,
        marketCap: Double,
        volume: Double,
        change1h: Double,
        change24h: Double,
        change7d: Double
    ): CoinGeckoMarket = CoinGeckoMarket(
        id = id,
        symbol = symbol,
        name = name,
        current_price = price,
        market_cap = marketCap,
        market_cap_rank = null,
        total_volume = volume,
        price_change_percentage_1h_in_currency = change1h,
        price_change_percentage_24h = change24h,
        price_change_percentage_7d_in_currency = change7d
    )
}
