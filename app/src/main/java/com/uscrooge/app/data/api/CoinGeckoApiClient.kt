package com.uscrooge.app.data.api

import android.util.Log
import androidx.annotation.VisibleForTesting
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CoinGeckoApiClient @Inject constructor() {

    companion object {
        private const val TAG = "CoinGeckoApi"
        private const val BASE_URL = "https://api.coingecko.com/api/v3/"
        private const val MAX_OHLC_RETRIES = 2
    }

    @VisibleForTesting
    internal open val service: CoinGeckoApiService = createService()

    private fun createService(): CoinGeckoApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (Log.isLoggable(TAG, Log.DEBUG))
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinGeckoApiService::class.java)
    }

    suspend fun getTopCoinsByVolume(
        vsCurrency: String = "usd",
        limit: Int = 50
    ): Result<List<CoinGeckoMarket>> {
        return try {
            val response = service.getMarkets(
                vsCurrency = vsCurrency,
                order = "volume_desc",
                perPage = limit.coerceIn(1, 250)
            )
            Result.success(response)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch top coins: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getTopCoinsByMarketCap(
        vsCurrency: String = "usd",
        limit: Int = 50
    ): Result<List<CoinGeckoMarket>> {
        return try {
            val response = service.getMarkets(
                vsCurrency = vsCurrency,
                order = "market_cap_desc",
                perPage = limit.coerceIn(1, 250)
            )
            Result.success(response)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch top coins by market cap: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getOHLC(
        coinId: String,
        vsCurrency: String = "usd",
        days: Int = 7
    ): Result<List<List<Double>>> {
        var lastError: Exception? = null
        for (attempt in 1..MAX_OHLC_RETRIES) {
            try {
                val response = service.getOHLC(coinId, vsCurrency, days)
                return Result.success(response)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "OHLC fetch attempt $attempt failed for $coinId: ${e.message}")
            }
        }
        return Result.failure(lastError ?: Exception("Failed to fetch OHLC for $coinId"))
    }

    suspend fun getSimplePrice(
        coinIds: List<String>,
        vsCurrency: String = "usd"
    ): Result<Map<String, CoinGeckoPrice>> {
        return try {
            val response = service.getSimplePrice(
                ids = coinIds.joinToString(","),
                vsCurrencies = vsCurrency
            )
            val prices = response.mapValues { (_, v) ->
                CoinGeckoPrice(
                    price = v["usd"] ?: 0.0,
                    volume24h = v["usd_24h_vol"] ?: 0.0,
                    change24h = v["usd_24h_change"] ?: 0.0
                )
            }
            Result.success(prices)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch simple price: ${e.message}")
            Result.failure(e)
        }
    }
}

data class CoinGeckoPrice(
    val price: Double,
    val volume24h: Double,
    val change24h: Double
)
