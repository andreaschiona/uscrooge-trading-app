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

    // Rate limiter specifically for data API calls (IEX free tier: 200 req/min)
    private val dataRateLimiter = RateLimiter(permitsPerSecond = 3.0, maxBurstSize = 5)

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

    // Cached market hours state
    @Volatile
    private var marketOpenCache: Boolean? = null

    @Volatile
    private var marketOpenCacheTime: Long = 0L

    // Cached asset list
    @Volatile
    private var cachedAssets: List<String>? = null

    @Volatile
    private var cachedAssetsTime: Long = 0L

    companion object {
        private const val TAG = "AlpacaApiClient"
        private const val MARKET_HOURS_CACHE_MS = 5 * 60 * 1000L
        private const val ASSETS_CACHE_MS = 60 * 60 * 1000L

        private val POPULAR_STOCKS = listOf(
            "AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "META", "NVDA", "JPM",
            "V", "JNJ", "WMT", "PG", "MA", "UNH", "HD", "DIS", "BAC", "XOM",
            "NFLX", "ADBE", "CRM", "PYPL", "INTC", "CMCSA", "PFE", "VZ",
            "KO", "PEP", "T", "MRK", "ABT", "COST", "AVGO", "TMO", "CVX",
            "MCD", "DHR", "LLY", "NEE", "BMY", "QCOM", "TXN", "UNP", "PM",
            "HON", "LOW", "ORCL", "IBM", "AMGN", "SBUX", "BA", "SPY", "QQQ",
            "IWM", "DIA", "VTI", "VOO"
        )
    }

    private val tradingBaseUrl: String
        get() = if (isPaperTrading) "https://paper-api.alpaca.markets/" else "https://api.alpaca.markets/"

    private val dataBaseUrl: String
        get() = "https://data.alpaca.markets/"

    override fun updateCredentials(
        apiKey: String,
        apiSecret: String,
        timeout: Long
    ) {
        updateCredentials(apiKey, apiSecret, timeout, isPaperTrading)
    }

    fun updateCredentials(
        apiKey: String,
        apiSecret: String,
        timeout: Long,
        paperTrading: Boolean
    ) {
        synchronized(this) {
            val normalizedKey = apiKey.trim()
            val normalizedSecret = apiSecret.trim()
            val changed = this.apiKey != normalizedKey ||
                    this.apiSecret != normalizedSecret ||
                    this.timeout != timeout ||
                    this.isPaperTrading != paperTrading

            this.apiKey = normalizedKey
            this.apiSecret = normalizedSecret
            this.timeout = timeout
            this.isPaperTrading = paperTrading

            if (changed) {
                shutdownCachedOkHttpClient()
                apiServiceCache = null
                apiServiceCacheKey = ""
                marketOpenCache = null
                marketOpenCacheTime = 0L
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

    // === Market Hours & Asset Discovery ===

    suspend fun isMarketOpen(): Boolean {
        val now = System.currentTimeMillis()
        if (marketOpenCache != null && (now - marketOpenCacheTime) < MARKET_HOURS_CACHE_MS) {
            return marketOpenCache!!
        }

        return try {
            val response = getApiService().getClock()
            if (response.isSuccessful && response.body() != null) {
                val isOpen = response.body()!!.is_open
                marketOpenCache = isOpen
                marketOpenCacheTime = now
                isOpen
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check market hours: ${e.message}")
            false
        }
    }

    suspend fun getAvailableAssets(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedAssets != null && (now - cachedAssetsTime) < ASSETS_CACHE_MS) {
            return cachedAssets!!
        }

        return try {
            val response = getApiService().getAssets(status = "active", assetClass = "us_equity")
            if (response.isSuccessful && response.body() != null) {
                val validExchanges = setOf("NYSE", "NASDAQ", "ARCA")
                val assets = response.body()!!
                    .filter { it.tradable && it.fractionable && it.exchange in validExchanges }
                    .map { it.symbol }
                    .sorted()

                cachedAssets = assets
                cachedAssetsTime = now
                Log.d(TAG, "Fetched ${assets.size} tradable assets from Alpaca")
                assets
            } else {
                Log.w(TAG, "Failed to fetch assets, using fallback list")
                POPULAR_STOCKS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch assets: ${e.message}, using fallback list")
            POPULAR_STOCKS
        }
    }

    private suspend fun acquireDataRateLimit() {
        dataRateLimiter.acquire()
    }

    private fun normalizeSymbol(symbol: String): String {
        return symbol.substringBefore("/").uppercase()
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
        if (!isMarketOpen()) {
            return Result.failure(Exception("US stock market is currently closed"))
        }

        return try {
            acquireDataRateLimit()
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
            acquireDataRateLimit()
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
                Log.d(TAG, "Raw OHLC response for $symbol ($timeframe): ${jsonString.take(300)}")

                val ohlcList = parseBars(jsonString, normalizedSymbol)
                Log.d(TAG, "Parsed bars for $symbol: ${ohlcList.size} items")

                if (ohlcList.isEmpty()) {
                    Log.w(TAG, "No OHLC data for $symbol (timeframe=$timeframe), trying daily fallback")
                    if (timeframe != "1Day") {
                        return getOHLCWithTimeframe(symbol, "1Day", now)
                    }
                    Result.failure(Exception("No OHLC data for $symbol (timeframe=$timeframe)"))
                } else {
                    Log.d(TAG, "Successfully parsed ${ohlcList.size} OHLC bars for $symbol")
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

    private suspend fun getOHLCWithTimeframe(symbol: String, timeframe: String, now: ZonedDateTime): Result<List<OHLC>> {
        return try {
            acquireDataRateLimit()
            val normalizedSymbol = normalizeSymbol(symbol)
            val startTime = now.minus(180, ChronoUnit.DAYS)

            val response = getDataApiService().getStockBarsRaw(
                symbol = normalizedSymbol,
                timeframe = timeframe,
                start = startTime.format(DateTimeFormatter.ISO_INSTANT),
                limit = 5000
            )

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                Log.d(TAG, "Daily fallback response for $symbol: ${jsonString.take(300)}")

                val ohlcList = parseBars(jsonString, normalizedSymbol)
                Log.d(TAG, "Daily fallback bars for $symbol: ${ohlcList.size} items")

                if (ohlcList.isEmpty()) {
                    Log.w(TAG, "Daily fallback also empty for $symbol")
                    Result.failure(Exception("No OHLC data for $symbol (tried $timeframe and 1Day)"))
                } else {
                    Log.d(TAG, "Daily fallback successful: ${ohlcList.size} bars for $symbol")
                    Result.success(ohlcList)
                }
            } else {
                Result.failure(Exception("Daily fallback failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Daily fallback exception for $symbol: ${e.message}")
            Result.failure(Exception("Daily fallback error: ${e.message}"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBars(jsonString: String, symbol: String): List<OHLC> {
        val gson = Gson()
        val jsonElement = gson.fromJson(jsonString, com.google.gson.JsonElement::class.java)
        val barsElement = jsonElement.asJsonObject["bars"]

        val barsList: List<Map<String, Any>> = when {
            barsElement.isJsonArray -> {
                gson.fromJson<List<Map<String, Any>>>(barsElement, object : TypeToken<List<Map<String, Any>>>() {}.type)
            }
            barsElement.isJsonObject -> {
                val barsMap = gson.fromJson<Map<String, List<Map<String, Any>>>>(barsElement, object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type)
                barsMap[symbol] ?: barsMap.values.firstOrNull() ?: emptyList()
            }
            else -> emptyList()
        }

        return barsList.mapNotNull { bar ->
            try {
                OHLC(
                    time = (bar["t"] as? String)?.let { Instant.parse(it).toEpochMilli() }
                        ?: (bar["t"] as? Number)?.toLong() ?: 0L,
                    open = (bar["o"] as? Double) ?: (bar["o"] as? Number)?.toDouble() ?: 0.0,
                    high = (bar["h"] as? Double) ?: (bar["h"] as? Number)?.toDouble() ?: 0.0,
                    low = (bar["l"] as? Double) ?: (bar["l"] as? Number)?.toDouble() ?: 0.0,
                    close = (bar["c"] as? Double) ?: (bar["c"] as? Number)?.toDouble() ?: 0.0,
                    vwap = (bar["vw"] as? Double) ?: (bar["vw"] as? Number)?.toDouble() ?: 0.0,
                    volume = (bar["v"] as? Number)?.toDouble() ?: 0.0,
                    count = (bar["n"] as? Number)?.toInt() ?: 0
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse bar: $bar - ${e.message}")
                null
            }
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
