package com.uscrooge.app.backtest

import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.model.OHLC
import com.uscrooge.app.data.model.TradingPair
import kotlinx.coroutines.delay

class BacktestDataLoader(
    private val apiClient: KrakenApiClient
) {

    suspend fun loadHistoricalData(
        pair: TradingPair,
        interval: Int = 60,
        candleCount: Int = 1000
    ): Result<List<OHLC>> {
        return try {
            val allCandles = mutableListOf<OHLC>()
            var since: Long? = null
            val batchSize = 720

            while (allCandles.size < candleCount) {
                val result = apiClient.getOHLC(
                    pair = pair,
                    interval = interval,
                    since = since
                )

                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull()!!)
                }

                val candles = result.getOrNull() ?: emptyList()
                if (candles.isEmpty()) break

                allCandles.addAll(candles)
                since = candles.last().time + 1

                if (candles.size < batchSize) break

                delay(1000)
            }

            val deduplicated = allCandles.distinctBy { it.time }
            val sorted = deduplicated.sortedBy { it.time }
            val limited = sorted.takeLast(candleCount)

            Result.success(limited)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadMultiplePairs(
        pairs: List<TradingPair>,
        interval: Int = 60,
        candleCount: Int = 1000
    ): Map<String, List<OHLC>> {
        val result = mutableMapOf<String, List<OHLC>>()

        for (pair in pairs) {
            val dataResult = loadHistoricalData(pair, interval, candleCount)
            if (dataResult.isSuccess) {
                result[pair.symbol] = dataResult.getOrNull() ?: emptyList()
            }
            delay(2000)
        }

        return result
    }
}
