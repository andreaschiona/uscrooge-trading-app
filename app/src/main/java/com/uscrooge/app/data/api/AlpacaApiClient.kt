package com.uscrooge.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.uscrooge.app.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class AlpacaApiClient(
    apiKey: String = "",
    apiSecret: String = "",
    private var isPaperTrading: Boolean = true,
    timeout: Long = 30000,
    private val rateLimiter: RateLimiter = RateLimiter(permitsPerSecond = 5.0, maxBurstSize = 10)
) : BrokerApi {

    override val brokerName: String = "Alpaca"

    @Volatile
    private var apiKey: String = apiKey

    @Volatile
    private var apiSecret: String = apiSecret

    @Volatile
    private var timeout: Long = timeout

    @Volatile
    private var apiServiceCache: AlpacaApiService? = null

    @Volatile
    private var apiServiceCacheKey = ""

    @Volatile
    private var okHttpClientCache: OkHttpClient? = null

    private val tradingBaseUrl: String
        get() = if (isPaperTrading) "https://paper-api.alpaca.markets/" else "https://api.alpaca.markets/"

    private val dataBaseUrl: String
        get() = "https://data.alpaca.markets/"

    companion object {
        private const val TAG = "AlpacaApiClient"

        // Map common stock symbols to a normalized format
        private val SYMBOL_NORMALIZATION = mapOf(
            "AAPL" to "AAPL", "MSFT" to "MSFT", "GOOGL" to "GOOGL",
            "AMZN" to "AMZN", "TSLA" to "TSLA", "META" to "META",
            "NVDA" to "NVDA", "JPM" to "JPM", "V" to "V",
            "JNJ" to "JNJ", "WMT" to "WMT", "PG" to "PG",
            "MA" to "MA", "UNH" to "UNH", "HD" to "HD",
            "DIS" to "DIS", "BAC" to "BAC", "XOM" to "XOM",
            "NFLX" to "NFLX", "ADBE" to "ADBE", "CRM" to "CRM",
            "PYPL" to "PYPL", "INTC" to "INTC", "CMCSA" to "CMCSA",
            "PFE" to "PFE", "VZ" to "VZ", "KO" to "KO",
            "PEP" to "PEP", "T" to "T", "MRK" to "MRK",
            "ABT" to "ABT", "COST" to "COST", "AVGO" to "AVGO",
            "TMO" to "TMO", "CVX" to "CVX", "MCD" to "MCD",
            "DHR" to "DHR", "LLY" to "LLY", "NEE" to "NEE",
            "BMY" to "BMY", "QCOM" to "QCOM", "TXN" to "TXN",
            "UNP" to "UNP", "PM" to "PM", "HON" to "HON",
            "LOW" to "LOW", "ORCL" to "ORCL", "IBM" to "IBM",
            "AMGN" to "AMGN", "SBUX" to "SBUX", "BA" to "BA",
            "SPY" to "SPY", "QQQ" to "QQQ", "IWM" to "IWM",
            "DIA" to "DIA", "VTI" to "VTI", "VOO" to "VOO"
        )
    }

    override fun updateCredentials(
        apiKey: String,
        apiSecret: String,
        timeout: Long
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
                shutdownCachedOkHttpClient()
                apiServiceCache = null
                apiServiceCacheKey = ""
            }
        }
    }

    override fun close() {
        synchronized(this) {
            shutdownCachedOkHttpClient()
            apiServiceCache = null
            apiServiceCacheKey = ""
        }
    }

    private fun shutdownCachedOkHttpClient() {
        okHttpClientCache?.let { client ->
            try {
                client.dispatcher.executorService.shutdown()
            } catch (_: Exception) {
            }
            try {
                client.connectionPool.evictAll()
            } catch (_: Exception) {
            }
        }
        okHttpClientCache = null
    }

    private fun getApiService(): AlpacaApiService {
        val cacheKey = "$apiKey|$apiSecret|$timeout|$isPaperTrading"
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
                .addInterceptor(RateLimitInterceptor(rateLimiter))
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .apply {
                    if (apiKey.isNotEmpty() && apiSecret.isNotEmpty()) {
                        addInterceptor(AlpacaAuthInterceptor(apiKey, apiSecret))
                    }
                }
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(tradingBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service = retrofit.create(AlpacaApiService::class.java)
            apiServiceCache = service
            apiServiceCacheKey = cacheKey
            okHttpClientCache = okHttpClient
            return service
        }
    }

    private fun getDataApiService(): AlpacaApiService {
        val cacheKey = "data|$apiKey|$apiSecret|$timeout"
        // Reuse the same cache since authentication is the same
        val cached = apiServiceCache
        if (cached != null && apiServiceCacheKey == cacheKey) {
            return cached
        }

        synchronized(this) {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .addInterceptor(RateLimitInterceptor(rateLimiter))
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .apply {
                    if (apiKey.isNotEmpty() && apiSecret.isNotEmpty()) {
                        addInterceptor(AlpacaAuthInterceptor(apiKey, apiSecret))
                    }
                }
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(dataBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return retrofit.create(AlpacaApiService::class.java)
        }
    }

    // === BrokerApi Implementation ===

    private fun extractErrorMessage(response: retrofit2.Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            if (errorBody.isNullOrBlank()) {
                "HTTP ${response.code()} ${response.message()}"
            } else {
                "HTTP ${response.code()}: $errorBody"
            }
        } catch (_: Exception) {
            "HTTP ${response.code()} ${response.message()}"
        }
    }

    override suspend fun getTicker(symbol: String): Result<Ticker> {
        return try {
            val normalizedSymbol = normalizeSymbol(symbol)
            val response = getDataApiService().getLatestTrade(normalizedSymbol)

            if (response.isSuccessful && response.body() != null) {
                val trade = response.body()!!.trade
                val quoteResponse = getDataApiService().getLatestQuote(normalizedSymbol)
                val quote = if (quoteResponse.isSuccessful) quoteResponse.body()?.quote else null

                val lastPrice = trade.p
                val bid = quote?.bp ?: lastPrice
                val ask = quote?.ap ?: lastPrice

                Result.success(
                    Ticker(
                        pair = symbol,
                        ask = ask,
                        bid = bid,
                        lastTrade = lastPrice,
                        volume = trade.s.toDouble(),
                        volumeWeightedAverage = lastPrice,
                        numberOfTrades = 1,
                        low = lastPrice,
                        high = lastPrice,
                        opening = lastPrice
                    )
                )
            } else {
                val errorMsg = extractErrorMessage(response)
                Log.w(TAG, "getTicker failed for $symbol: $errorMsg")
                Result.failure(Exception("Alpaca ticker error: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTicker exception for $symbol: ${e.message}", e)
            Result.failure(Exception("Alpaca ticker error: ${e.message}"))
        }
    }

    override suspend fun getOHLC(symbol: String, interval: Int): Result<List<OHLC>> {
        return try {
            val normalizedSymbol = normalizeSymbol(symbol)
            val timeframe = intervalToTimeframe(interval)
            val now = ZonedDateTime.now(java.time.ZoneOffset.UTC)
            val startTime = when {
                interval <= 60 -> now.minus(7, ChronoUnit.DAYS)
                interval <= 360 -> now.minus(30, ChronoUnit.DAYS)
                interval <= 1440 -> now.minus(90, ChronoUnit.DAYS)
                else -> now.minus(180, ChronoUnit.DAYS)
            }

            val response = getDataApiService().getStockBarsRaw(
                symbol = normalizedSymbol,
                timeframe = timeframe,
                start = startTime.format(DateTimeFormatter.ISO_INSTANT),
                limit = 5000
            )

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val gson = Gson()
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val jsonMap: Map<String, Any> = gson.fromJson(jsonString, type)

                @Suppress("UNCHECKED_CAST")
                val barsList = (jsonMap["bars"] as? Map<String, List<Map<String, Any>>>)?.get(normalizedSymbol)
                    ?: (jsonMap["bars"] as? Map<String, List<Map<String, Any>>>)?.values?.firstOrNull()
                    ?: emptyList()

                val ohlcList = barsList.mapNotNull { bar ->
                    try {
                        OHLC(
                            time = (bar["t"] as? String)?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
                            open = (bar["o"] as? Double) ?: (bar["o"] as? Number)?.toDouble() ?: 0.0,
                            high = (bar["h"] as? Double) ?: (bar["h"] as? Number)?.toDouble() ?: 0.0,
                            low = (bar["l"] as? Double) ?: (bar["l"] as? Number)?.toDouble() ?: 0.0,
                            close = (bar["c"] as? Double) ?: (bar["c"] as? Number)?.toDouble() ?: 0.0,
                            vwap = (bar["vw"] as? Double) ?: (bar["vw"] as? Number)?.toDouble() ?: 0.0,
                            volume = (bar["v"] as? Number)?.toDouble() ?: 0.0,
                            count = (bar["n"] as? Number)?.toInt() ?: 0
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                if (ohlcList.isEmpty()) {
                    val msg = "No OHLC data for $symbol (timeframe=$timeframe)"
                    Log.w(TAG, msg)
                    Result.failure(Exception(msg))
                } else {
                    Result.success(ohlcList)
                }
            } else {
                val errorMsg = extractErrorMessage(response)
                Log.w(TAG, "getOHLC failed for $symbol: $errorMsg")
                Result.failure(Exception("Alpaca OHLC error: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOHLC exception for $symbol: ${e.message}", e)
            Result.failure(Exception("Alpaca OHLC error: ${e.message}"))
        }
    }

    override suspend fun getAccountBalance(): Result<Map<String, Double>> {
        return try {
            val response = getApiService().getAccount()

            if (response.isSuccessful && response.body() != null) {
                val account = response.body()!!
                val balances = mutableMapOf<String, Double>()
                balances["USD"] = account.cash.toDoubleOrNull() ?: 0.0
                balances["PORTFOLIO_VALUE"] = account.portfolio_value.toDoubleOrNull() ?: 0.0
                balances["EQUITY"] = account.equity.toDoubleOrNull() ?: 0.0
                balances["BUYING_POWER"] = account.buying_power.toDoubleOrNull() ?: 0.0
                balances["LONG_MARKET_VALUE"] = account.long_market_value.toDoubleOrNull() ?: 0.0
                Result.success(balances)
            } else {
                Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAvailableBalance(currency: String): Result<Double> {
        return try {
            val balanceResult = getAccountBalance()
            if (balanceResult.isSuccess) {
                val balances = balanceResult.getOrNull()!!
                val buyingPower = balances["BUYING_POWER"] ?: 0.0
                val cash = balances["USD"] ?: 0.0
                Result.success(maxOf(buyingPower, cash))
            } else {
                Result.failure(balanceResult.exceptionOrNull()!!)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun placeOrder(
        symbol: String,
        side: OrderSide,
        quantity: Double,
        orderType: OrderType,
        limitPrice: Double?,
        stopPrice: Double?,
        takeProfitPrice: Double?,
        validate: Boolean
    ): Result<String> {
        return try {
            val normalizedSymbol = normalizeSymbol(symbol)

            val alpacaOrderType = when (orderType) {
                OrderType.MARKET -> "market"
                OrderType.LIMIT -> "limit"
                OrderType.STOP_LOSS -> "stop"
                OrderType.TAKE_PROFIT -> "limit"
            }

            val orderRequest = AlpacaOrderRequest(
                symbol = normalizedSymbol,
                qty = quantity,
                side = side.name.lowercase(),
                type = alpacaOrderType,
                time_in_force = "day",
                limit_price = if (orderType == OrderType.LIMIT || orderType == OrderType.TAKE_PROFIT) limitPrice else null,
                stop_price = if (orderType == OrderType.STOP_LOSS) stopPrice else null
            )

            // Alpaca doesn't have a validate-only mode, so we just skip execution
            if (validate) {
                return Result.success("validate")
            }

            val response = getApiService().placeOrder(orderRequest)

            if (response.isSuccessful && response.body() != null) {
                val order = response.body()!!
                Result.success(order.id)
            } else {
                Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return try {
            val response = getApiService().cancelOrder(orderId)

            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrder(orderId: String): Result<BrokerOrderInfo> {
        return try {
            val response = getApiService().getOrder(orderId)

            if (response.isSuccessful && response.body() != null) {
                val order = response.body()!!
                Result.success(
                    BrokerOrderInfo(
                        orderId = order.id,
                        symbol = order.symbol,
                        side = order.side,
                        type = order.type,
                        status = mapAlpacaStatus(order.status),
                        quantity = order.qty?.toDoubleOrNull() ?: order.notional?.toDoubleOrNull() ?: 0.0,
                        filledQuantity = order.filled_qty.toDoubleOrNull() ?: 0.0,
                        price = order.limit_price?.toDoubleOrNull() ?: order.stop_price?.toDoubleOrNull() ?: 0.0,
                        avgFillPrice = order.filled_avg_price?.toDoubleOrNull() ?: 0.0,
                        fee = 0.0, // Alpaca is commission-free for stocks
                        createdAt = parseAlpacaTimestamp(order.created_at),
                        executedAt = order.filled_at?.let { parseAlpacaTimestamp(it) }
                    )
                )
            } else {
                Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOpenOrders(): Result<List<BrokerOrderInfo>> {
        return try {
            val response = getApiService().getOrders(status = "open")

            if (response.isSuccessful && response.body() != null) {
                val orders = response.body()!!.map { order ->
                    BrokerOrderInfo(
                        orderId = order.id,
                        symbol = order.symbol,
                        side = order.side,
                        type = order.type,
                        status = mapAlpacaStatus(order.status),
                        quantity = order.qty?.toDoubleOrNull() ?: order.notional?.toDoubleOrNull() ?: 0.0,
                        filledQuantity = order.filled_qty.toDoubleOrNull() ?: 0.0,
                        price = order.limit_price?.toDoubleOrNull() ?: order.stop_price?.toDoubleOrNull() ?: 0.0,
                        avgFillPrice = order.filled_avg_price?.toDoubleOrNull() ?: 0.0,
                        fee = 0.0,
                        createdAt = parseAlpacaTimestamp(order.created_at),
                        executedAt = order.filled_at?.let { parseAlpacaTimestamp(it) }
                    )
                }
                Result.success(orders)
            } else {
                Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositions(): Result<List<BrokerPositionInfo>> {
        return try {
            val response = getApiService().getPositions()

            if (response.isSuccessful && response.body() != null) {
                val positions = response.body()!!.map { pos ->
                    BrokerPositionInfo(
                        symbol = pos.symbol,
                        side = pos.side,
                        quantity = pos.qty.toDoubleOrNull() ?: 0.0,
                        avgEntryPrice = pos.avg_entry_price.toDoubleOrNull() ?: 0.0,
                        currentPrice = pos.current_price.toDoubleOrNull() ?: 0.0,
                        marketValue = pos.market_value.toDoubleOrNull() ?: 0.0,
                        unrealizedPnL = pos.unrealized_pl.toDoubleOrNull() ?: 0.0,
                        unrealizedPnLPercent = pos.unrealized_plpc.toDoubleOrNull() ?: 0.0
                    )
                }
                Result.success(positions)
            } else {
                // Alpaca returns 404 if no positions
                if (response.code() == 404) {
                    Result.success(emptyList())
                } else {
                    Result.failure(Exception("Alpaca API error: ${response.code()} - ${response.errorBody()?.string()}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // === Helper Methods ===

    private fun normalizeSymbol(symbol: String): String {
        // Handle formats like "AAPL/USD" -> "AAPL" or just "AAPL"
        val base = symbol.substringBefore("/")
        return SYMBOL_NORMALIZATION[base.uppercase()] ?: base.uppercase()
    }

    private fun intervalToTimeframe(intervalMinutes: Int): String {
        return when {
            intervalMinutes <= 1 -> "1Min"
            intervalMinutes <= 5 -> "5Min"
            intervalMinutes <= 15 -> "15Min"
            intervalMinutes <= 30 -> "30Min"
            intervalMinutes <= 60 -> "1H"
            intervalMinutes <= 120 -> "2H"
            intervalMinutes <= 240 -> "4H"
            intervalMinutes <= 1440 -> "1Day"
            else -> "1Week"
        }
    }

    private fun mapAlpacaStatus(status: String): String {
        return when (status.lowercase()) {
            "filled" -> "closed"
            "accepted", "new", "pending_new", "pending_cancel", "pending_replace" -> "open"
            "canceled", "cancelled" -> "canceled"
            "expired" -> "expired"
            "stopped", "rejected", "replaced" -> "canceled"
            else -> status.lowercase()
        }
    }

    private fun parseAlpacaTimestamp(timestamp: String): Long {
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
