package com.uscrooge.app.data.model

data class AssetRanking(
    val coinId: String,
    val symbol: String,
    val name: String,
    val currentPrice: Double,
    val marketCapRank: Int?,
    val marketCap: Double,
    val volume24h: Double,
    val volumeToMarketCapRatio: Double,
    val priceChange1h: Double?,
    val priceChange24h: Double?,
    val priceChange7d: Double?,
    val volatilityScore: Double,
    val liquidityScore: Double,
    val momentumScore: Double,
    val compositeScore: Double,
    val rank: Int
)

data class AssetRankingConfig(
    val vsCurrency: String = "usd",
    val scanLimit: Int = 100,
    val minMarketCap: Double = 10_000_000.0,
    val minVolume24h: Double = 1_000_000.0,
    val maxAssetsToRank: Int = 20,
    val volumeWeight: Double = 0.25,
    val momentumWeight: Double = 0.35,
    val liquidityWeight: Double = 0.20,
    val volatilityWeight: Double = 0.20
)
