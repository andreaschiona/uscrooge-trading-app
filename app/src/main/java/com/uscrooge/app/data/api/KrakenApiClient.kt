package com.uscrooge.app.data.api

import com.uscrooge.app.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class KrakenApiClient(
    apiKey: String = "",
    apiSecret: String = "",
    timeout: Long = 30000
) {
    private val baseUrl = "https://api.kraken.com/"

    @Volatile
    private var apiKey: String = apiKey

    @Volatile
    private var apiSecret: String = apiSecret

    @Volatile
    private var timeout: Long = timeout

    @Volatile
    private var apiServiceCache: KrakenApiService? = null

    @Volatile
    private var apiServiceCacheKey: String = ""

    fun updateCredentials(
        apiKey: String,
        apiSecret: String,
        timeout: Long = this.timeout
    ) {
        synchronized(this) {
            val normalizedKey = apiKey.trim()
            val normalizedSecret = apiSecret.trim()
            val changed = this.apiKey != normalizedKey ||
                    this.apiSecret != normalizedSecret ||
                    this.timeout != timeout

            this.apiKey = normalizedKey
            this.apiSecret = normalizedSecret
            this.timeout = timeout

            if (changed) {
                apiServiceCache = null
                apiServiceCacheKey = ""
            }
        }
    }

    private fun getApiService(): KrakenApiService {
        val cacheKey = "$apiKey|$apiSecret|$timeout"
        val cached = apiServiceCache
        if (cached != null && apiServiceCacheKey == cacheKey) {
            return cached
        }

        synchronized(this) {
            if (apiServiceCache != null && apiServiceCacheKey == cacheKey) {
                return apiServiceCache!!
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .apply {
                    if (apiKey.isNotEmpty() && apiSecret.isNotEmpty()) {
                        addInterceptor(KrakenAuthInterceptor(apiKey, apiSecret))
                    }
                }
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service = retrofit.create(KrakenApiService::class.java)
            apiServiceCache = service
            apiServiceCacheKey = cacheKey
            return service
        }
    }

    // Public API methods

    suspend fun getTicker(pair: TradingPair): Result<Ticker> {
        return try {
            val krakenPair = pair.toKrakenSymbol()
            val response = getApiService().getTicker(krakenPair)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val tickerData = body.result?.values?.firstOrNull()
                    ?: return Result.failure(Exception("No ticker data"))

                val ticker = Ticker(
                    pair = pair.symbol,
                    ask = tickerData.a[0].toDouble(),
                    bid = tickerData.b[0].toDouble(),
                    lastTrade = tickerData.c[0].toDouble(),
                    volume = tickerData.v[1].toDouble(),
                    volumeWeightedAverage = tickerData.p[1].toDouble(),
                    numberOfTrades = tickerData.t[1],
                    low = tickerData.l[1].toDouble(),
                    high = tickerData.h[1].toDouble(),
                    opening = tickerData.o.toDouble()
                )

                Result.success(ticker)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOHLC(
        pair: TradingPair,
        interval: Int = 60,
        since: Long? = null
    ): Result<List<OHLC>> {
        return try {
            val krakenPair = pair.toKrakenSymbol()
            val response = getApiService().getOHLC(krakenPair, interval, since)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                // Parse OHLC data from dynamic response
                val result = body.result as? Map<*, *>
                val ohlcData = result?.get(krakenPair) as? List<*>
                    ?: return Result.failure(Exception("No OHLC data"))

                val ohlcList = ohlcData.mapNotNull { item ->
                    val array = item as? List<*> ?: return@mapNotNull null
                    if (array.size < 8) return@mapNotNull null

                    OHLC(
                        time = (array[0] as? Number)?.toLong() ?: return@mapNotNull null,
                        open = (array[1] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        high = (array[2] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        low = (array[3] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        close = (array[4] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        vwap = (array[5] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        volume = (array[6] as? String)?.toDoubleOrNull() ?: return@mapNotNull null,
                        count = (array[7] as? Number)?.toInt() ?: return@mapNotNull null
                    )
                }

                Result.success(ohlcList)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderBook(pair: TradingPair, count: Int = 100): Result<OrderBook> {
        return try {
            val krakenPair = pair.toKrakenSymbol()
            val response = getApiService().getOrderBook(krakenPair, count)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val bookData = body.result?.values?.firstOrNull()
                    ?: return Result.failure(Exception("No order book data"))

                val orderBook = OrderBook(
                    pair = pair.symbol,
                    asks = bookData.asks.map {
                        OrderBookEntry(
                            price = it[0].toDouble(),
                            volume = it[1].toDouble(),
                            timestamp = it[2].toLong()
                        )
                    },
                    bids = bookData.bids.map {
                        OrderBookEntry(
                            price = it[0].toDouble(),
                            volume = it[1].toDouble(),
                            timestamp = it[2].toLong()
                        )
                    }
                )

                Result.success(orderBook)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Private API methods (require authentication)

    suspend fun getAccountBalance(): Result<Map<String, Double>> {
        return try {
            val nonce = System.currentTimeMillis() * 1000
            val response = getApiService().getAccountBalance(nonce)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val balances = body.result?.mapValues { it.value.toDouble() }
                    ?: return Result.failure(Exception("No balance data"))

                Result.success(balances)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTradeBalance(asset: String = "ZEUR"): Result<TradeBalance> {
        return try {
            val nonce = System.currentTimeMillis() * 1000
            val response = getApiService().getTradeBalance(nonce, asset)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val tradeBalance = body.result
                    ?: return Result.failure(Exception("No trade balance data"))

                Result.success(tradeBalance)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addOrder(
        pair: TradingPair,
        type: OrderSide,
        volume: Double,
        orderType: OrderType = OrderType.MARKET,
        price: Double? = null,
        validate: Boolean = false
    ): Result<String> {
        return try {
            val nonce = System.currentTimeMillis() * 1000
            val krakenPair = pair.toKrakenSymbol()

            val response = getApiService().addOrder(
                nonce = nonce,
                ordertype = orderType.name.lowercase(),
                type = type.name.lowercase(),
                volume = volume.toString(),
                pair = krakenPair,
                price = price?.toString(),
                validate = validate
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val orderId = body.result?.txid?.firstOrNull()
                    ?: return Result.failure(Exception("No order ID returned"))

                Result.success(orderId)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return try {
            val nonce = System.currentTimeMillis() * 1000
            val response = getApiService().cancelOrder(nonce, orderId)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(true)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOpenOrders(): Result<Map<String, OrderInfo>> {
        return try {
            val nonce = System.currentTimeMillis() * 1000
            val response = getApiService().getOpenOrders(nonce)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(body.result?.open ?: emptyMap())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
