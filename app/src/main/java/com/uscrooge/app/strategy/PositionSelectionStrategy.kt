package com.uscrooge.app.strategy

import android.util.Log
import com.uscrooge.app.data.api.CoinGeckoApiClient
import com.uscrooge.app.data.api.CoinGeckoMarket
import com.uscrooge.app.data.model.AssetRanking
import com.uscrooge.app.data.model.AssetRankingConfig
import com.uscrooge.app.data.model.Position
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Singleton
class PositionSelectionStrategy @Inject constructor(
    private val coinGeckoApi: CoinGeckoApiClient
) {
    companion object {
        private const val TAG = "PositionSelection"
    }

    suspend fun selectTopPositions(
        currentPositions: List<Position> = emptyList(),
        config: AssetRankingConfig = AssetRankingConfig()
    ): Result<List<AssetRanking>> {
        return try {
            val markets = coinGeckoApi.getTopCoinsByVolume(
                vsCurrency = config.vsCurrency,
                limit = config.scanLimit
            ).getOrThrow()

            val filtered = markets.filter { coin ->
                val cap = coin.market_cap ?: 0.0
                val vol = coin.total_volume ?: 0.0
                cap >= config.minMarketCap && vol >= config.minVolume24h
            }

            val ranked = scoreAndRank(filtered, config)

            val topN = ranked.take(config.maxAssetsToRank)

            val withoutCurrent = topN.filter { ranking ->
                currentPositions.none { pos ->
                    pos.pair.substringBefore("/").equals(ranking.symbol, ignoreCase = true)
                }
            }

            Result.success(withoutCurrent.ifEmpty {
                topN.take(5)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Position selection failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun scoreAndRank(
        coins: List<CoinGeckoMarket>,
        config: AssetRankingConfig
    ): List<AssetRanking> {
        val scored = coins.map { coin ->
            val price = coin.current_price ?: 0.0
            val marketCap = coin.market_cap ?: 0.0
            val volume24h = coin.total_volume ?: 0.0
            val volToCapRatio = if (marketCap > 0) volume24h / marketCap else 0.0
            val change1h = coin.price_change_percentage_1h_in_currency ?: 0.0
            val change24h = coin.price_change_percentage_24h ?: 0.0
            val change7d = coin.price_change_percentage_7d_in_currency ?: 0.0

            val liquidityScore = scoreLiquidity(marketCap, volume24h, volToCapRatio)
            val momentumScore = scoreMomentum(change1h, change24h, change7d)
            val volatilityScore = scoreVolatility(change1h, change24h, change7d)

            val composite =
                config.momentumWeight * momentumScore +
                config.liquidityWeight * liquidityScore +
                config.volatilityWeight * volatilityScore

            AssetRanking(
                coinId = coin.id,
                symbol = coin.symbol.uppercase(),
                name = coin.name,
                currentPrice = price,
                marketCapRank = coin.market_cap_rank,
                marketCap = marketCap,
                volume24h = volume24h,
                volumeToMarketCapRatio = volToCapRatio,
                priceChange1h = change1h,
                priceChange24h = change24h,
                priceChange7d = change7d,
                volatilityScore = volatilityScore,
                liquidityScore = liquidityScore,
                momentumScore = momentumScore,
                compositeScore = composite,
                rank = 0
            )
        }

        return scored
            .sortedByDescending { it.compositeScore }
            .mapIndexed { index, ranking -> ranking.copy(rank = index + 1) }
    }

    private fun scoreLiquidity(
        marketCap: Double,
        volume24h: Double,
        volToCapRatio: Double
    ): Double {
        val capScore = min(log10(max(marketCap, 1.0)) / 12.0, 1.0)

        val volumeScore = min(log10(max(volume24h, 1.0)) / 10.0, 1.0)

        val ratioScore = min(volToCapRatio * 10.0, 1.0)

        return capScore * 0.4 + volumeScore * 0.4 + ratioScore * 0.2
    }

    private fun scoreMomentum(
        change1h: Double,
        change24h: Double,
        change7d: Double
    ): Double {
        val h1Score = normalizeMomentum(change1h * 24)
        val d1Score = normalizeMomentum(change24h)
        val d7Score = normalizeMomentum(change7d / 7)

        return h1Score * 0.3 + d1Score * 0.4 + d7Score * 0.3
    }

    private fun normalizeMomentum(percentChange: Double): Double {
        val clamped = percentChange.coerceIn(-20.0, 20.0)
        return (clamped + 20.0) / 40.0
    }

    private fun scoreVolatility(
        change1h: Double,
        change24h: Double,
        change7d: Double
    ): Double {
        val h1Vol = abs(change1h) / 5.0
        val d1Vol = abs(change24h) / 20.0
        val d7Vol = abs(change7d) / 50.0

        val rawVol = h1Vol * 0.3 + d1Vol * 0.4 + d7Vol * 0.3

        return min(rawVol, 1.0)
    }

    suspend fun enrichWithAnalysis(
        rankings: List<AssetRanking>,
        vsCurrency: String = "usd"
    ): Result<List<AssetRanking>> {
        if (rankings.isEmpty()) return Result.success(rankings)

        val coinIds = rankings.map { it.coinId }
        val prices = coinGeckoApi.getSimplePrice(coinIds, vsCurrency).getOrNull() ?: emptyMap()

        val enriched = rankings.map { ranking ->
            val priceData = prices[ranking.coinId]
            if (priceData != null) {
                val volRatio = if (ranking.marketCap > 0)
                    priceData.volume24h / ranking.marketCap else 0.0
                val liqScore = scoreLiquidity(ranking.marketCap, priceData.volume24h, volRatio)
                val momScore = scoreMomentum(
                    priceData.change24h / 24,
                    priceData.change24h,
                    priceData.change24h * 7
                )
                val volScore = scoreVolatility(
                    priceData.change24h / 24,
                    priceData.change24h,
                    priceData.change24h
                )
                val composite = momScore * 0.35 + liqScore * 0.25 + volScore * 0.20

                ranking.copy(
                    currentPrice = priceData.price,
                    volume24h = priceData.volume24h,
                    volumeToMarketCapRatio = volRatio,
                    priceChange24h = priceData.change24h,
                    liquidityScore = liqScore,
                    momentumScore = momScore,
                    volatilityScore = volScore,
                    compositeScore = composite
                )
            } else ranking
        }

        val finalRanked = enriched
            .sortedByDescending { it.compositeScore }
            .mapIndexed { index, ranking -> ranking.copy(rank = index + 1) }

        return Result.success(finalRanked)
    }
}
