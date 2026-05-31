package com.uscrooge.app.data.model

import org.junit.Assert.*
import org.junit.Test

class AssetRankingTest {

    @Test
    fun `AssetRankingConfig has correct defaults`() {
        val config = AssetRankingConfig()

        assertEquals("usd", config.vsCurrency)
        assertEquals(100, config.scanLimit)
        assertEquals(10_000_000.0, config.minMarketCap, 0.01)
        assertEquals(1_000_000.0, config.minVolume24h, 0.01)
        assertEquals(20, config.maxAssetsToRank)
        assertEquals(0.25, config.volumeWeight, 0.01)
        assertEquals(0.35, config.momentumWeight, 0.01)
        assertEquals(0.20, config.liquidityWeight, 0.01)
        assertEquals(0.20, config.volatilityWeight, 0.01)
    }

    @Test
    fun `AssetRankingConfig weights sum to 1`() {
        val config = AssetRankingConfig()
        val sum = config.volumeWeight + config.momentumWeight +
            config.liquidityWeight + config.volatilityWeight
        assertEquals(1.0, sum, 0.01)
    }

    @Test
    fun `AssetRankingConfig can be created with custom values`() {
        val config = AssetRankingConfig(
            vsCurrency = "eur",
            scanLimit = 50,
            minMarketCap = 5_000_000.0,
            minVolume24h = 500_000.0,
            maxAssetsToRank = 10,
            volumeWeight = 0.1,
            momentumWeight = 0.5,
            liquidityWeight = 0.2,
            volatilityWeight = 0.2
        )

        assertEquals("eur", config.vsCurrency)
        assertEquals(50, config.scanLimit)
        assertEquals(5_000_000.0, config.minMarketCap, 0.01)
        assertEquals(10, config.maxAssetsToRank)
    }

    @Test
    fun `AssetRanking is created with correct field values`() {
        val ranking = AssetRanking(
            coinId = "bitcoin",
            symbol = "BTC",
            name = "Bitcoin",
            currentPrice = 50000.0,
            marketCapRank = 1,
            marketCap = 1_000_000_000_000.0,
            volume24h = 50_000_000_000.0,
            volumeToMarketCapRatio = 0.05,
            priceChange1h = 0.5,
            priceChange24h = 2.0,
            priceChange7d = 10.0,
            volatilityScore = 0.3,
            liquidityScore = 0.9,
            momentumScore = 0.7,
            compositeScore = 0.65,
            rank = 1
        )

        assertEquals("bitcoin", ranking.coinId)
        assertEquals("BTC", ranking.symbol)
        assertEquals("Bitcoin", ranking.name)
        assertEquals(50000.0, ranking.currentPrice, 0.01)
        assertEquals(1, ranking.marketCapRank)
        assertEquals(1_000_000_000_000.0, ranking.marketCap, 0.01)
        assertEquals(50_000_000_000.0, ranking.volume24h, 0.01)
        assertEquals(0.05, ranking.volumeToMarketCapRatio, 0.01)
        assertEquals(0.5, ranking.priceChange1h!!, 0.01)
        assertEquals(2.0, ranking.priceChange24h!!, 0.01)
        assertEquals(10.0, ranking.priceChange7d!!, 0.01)
        assertEquals(0.3, ranking.volatilityScore, 0.01)
        assertEquals(0.9, ranking.liquidityScore, 0.01)
        assertEquals(0.7, ranking.momentumScore, 0.01)
        assertEquals(0.65, ranking.compositeScore, 0.01)
        assertEquals(1, ranking.rank)
    }

    @Test
    fun `AssetRanking supports nullable marketCapRank`() {
        val ranking = AssetRanking(
            coinId = "test", symbol = "TST", name = "Test",
            currentPrice = 1.0, marketCapRank = null, marketCap = 0.0,
            volume24h = 0.0, volumeToMarketCapRatio = 0.0,
            priceChange1h = null, priceChange24h = null, priceChange7d = null,
            volatilityScore = 0.0, liquidityScore = 0.0,
            momentumScore = 0.0, compositeScore = 0.0, rank = 0
        )

        assertNull(ranking.marketCapRank)
        assertNull(ranking.priceChange1h)
    }
}
