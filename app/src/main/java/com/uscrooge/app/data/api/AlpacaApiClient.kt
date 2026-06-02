package com.uscrooge.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.uscrooge.app.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class AlpacaApiClient(
    apiKey: String = "",
    apiSecret: String = "",
    private var isPaperTrading: Boolean = true,
    timeout: Long = 30000,
    private val rateLimiter: RateLimiter = RateLimiter(permitsPerSecond = 5.0, maxBurstSize = 10),
    private val sharedGson: com.google.gson.Gson? = null,
    private val sharedOkHttp: OkHttpClient? = null
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
    private var dataApiServiceCache: AlpacaApiService? = null

    @Volatile
    private var dataApiServiceCacheKey = ""

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
                dataApiServiceCache = null
                dataApiServiceCacheKey = ""
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
            dataApiServiceCache = null
            dataApiServiceCacheKey = ""
        }
    }

    private fun shutdownCachedOkHttpClient() {
        okHttpClientCache?.let { client ->
            if (sharedOkHttp == null) {
                try {
                    client.connectionPool.evictAll()
                } catch (_: Exception) {
                }
            }
        }
        okHttpClientCache = null
    }

    private fun buildOkHttpClient(rateLimiter: RateLimiter): OkHttpClient {
        val loggingLevel = if (android.util.Log.isLoggable("AlpacaApi", android.util.Log.DEBUG)) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.HEADERS
        }
        return (sharedOkHttp?.newBuilder()
            ?: OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS))
            .addInterceptor(RateLimitInterceptor(rateLimiter))
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .apply {
                if (apiKey.isNotEmpty() && apiSecret.isNotEmpty()) {
                    addInterceptor(AlpacaAuthInterceptor(apiKey, apiSecret))
                }
            }
            .build()
    }

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        val gson = sharedGson ?: com.google.gson.Gson()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
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

            val okHttpClient = buildOkHttpClient(rateLimiter)
            val retrofit = buildRetrofit(tradingBaseUrl, okHttpClient)

            val service = retrofit.create(AlpacaApiService::class.java)
            apiServiceCache = service
            apiServiceCacheKey = cacheKey
            okHttpClientCache = okHttpClient
            return service
        }
    }

    private fun getDataApiService(): AlpacaApiService {
        val cacheKey = "data|$apiKey|$apiSecret|$timeout"
        val cached = dataApiServiceCache
        if (cached != null && dataApiServiceCacheKey == cacheKey) {
            return cached
        }

        synchronized(this) {
            if (dataApiServiceCache != null && dataApiServiceCacheKey == cacheKey) {
                return dataApiServiceCache!!
            }

            val okHttpClient = buildOkHttpClient(dataRateLimiter)
            val retrofit = buildRetrofit(dataBaseUrl, okHttpClient)

            val service = retrofit.create(AlpacaApiService::class.java)
            dataApiServiceCache = service
            dataApiServiceCacheKey = cacheKey
            return service
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
                Log.w(TAG, "Market hours API returned ${response.code()}, falling back to local time")
                val localIsOpen = isMarketOpenByLocalTime()
                marketOpenCache = localIsOpen
                marketOpenCacheTime = now
                localIsOpen
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check market hours: ${e.message}, falling back to local time")
            val localIsOpen = isMarketOpenByLocalTime()
            marketOpenCache = localIsOpen
            marketOpenCacheTime = now
            localIsOpen
        }
    }

    private fun isMarketOpenByLocalTime(): Boolean {
        val now = ZonedDateTime.now(ZoneId.of("America/New_York"))
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return false
        }
        val openTime = now.withHour(9).withMinute(30)
        val closeTime = now.withHour(16).withMinute(0)
        return !now.isBefore(openTime) && now.isBefore(closeTime)
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
                    .let { symbols ->
                        (POPULAR_STOCKS.filter { it in symbols } + symbols.filter { it !in POPULAR_STOCKS }.sorted())
                    }

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

    override suspend fun health(): Result<BrokerHealth> {
        val startTime = System.currentTimeMillis()
        return try {
            val result = getAccountBalance()
            val latency = System.currentTimeMillis() - startTime
            if (result.isSuccess) {
                val status = when {
                    latency > 2000 -> BrokerHealthStatus.SLOW
                    else -> BrokerHealthStatus.ONLINE
                }
                Result.success(
                    BrokerHealth(
                        brokerName = brokerName,
                        status = status,
                        latencyMs = latency,
                        lastError = null,
                        lastChecked = startTime
                    )
                )
            } else {
                val error = result.exceptionOrNull()
                Result.success(
                    BrokerHealth(
                        brokerName = brokerName,
                        status = BrokerHealthStatus.OFFLINE,
                        latencyMs = latency,
                        lastError = classifyHealthError(error),
                        lastChecked = startTime
                    )
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Result.success(
                BrokerHealth(
                    brokerName = brokerName,
                    status = BrokerHealthStatus.OFFLINE,
                    latencyMs = latency,
                    lastError = classifyHealthError(e),
                    lastChecked = startTime
                )
            )
        }
    }

    private fun classifyHealthError(error: Throwable?): String {
        if (error == null) return "Unknown error"
        val message = error.message ?: error.javaClass.simpleName
        return when {
            message.contains("401") || message.contains("403") ||
                message.contains("invalid key", ignoreCase = true) ||
                message.contains("invalid signature", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) ||
                message.contains("forbidden", ignoreCase = true) ||
                message.contains("Invalid API credentials", ignoreCase = true) ->
                "Invalid API credentials: wrong or missing Alpaca API key/secret"

            message.contains("422") || message.contains("400") ->
                "Invalid API request: $message"

            message.contains("429") ->
                "Rate limit exceeded: too many requests to Alpaca"

            error is java.net.UnknownHostException ||
                error is java.net.ConnectException ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("Failed to connect", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true) ->
                "Unreachable URL: cannot connect to Alpaca (check internet or URL)"

            error is java.net.SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                "Connection timeout: Alpaca is slow or unreachable"

            message.contains("500") || message.contains("502") || message.contains("503") ->
                "Alpaca server error (${message.take(50)}): try again later"

            else -> "Connection error: $message"
        }
    }

    override suspend fun getTicker(symbol: String): Result<Ticker> {
        if (!isMarketOpen()) {
            return Result.failure(Exception("US stock market is currently closed"))
        }

        return try {
            acquireDataRateLimit()
            val normalizedSymbol = normalizeSymbol(symbol)
            val tradeResult = parseLatestTrade(normalizedSymbol)

            if (tradeResult.isFailure) {
                val error = tradeResult.exceptionOrNull()!!
                Log.w(TAG, "getTicker trade parse failed for $symbol: ${error.message}")
                return Result.failure(Exception("Alpaca ticker error: ${error.message}"))
            }

            val (lastPrice, volume) = tradeResult.getOrNull()!!

            val quoteResponse = getDataApiService().getLatestQuote(normalizedSymbol)
            val quote = if (quoteResponse.isSuccessful && quoteResponse.body() != null) {
                parseLatestQuote(quoteResponse.body()!!.string())
            } else {
                null
            }

            val bid = quote?.bid ?: lastPrice
            val ask = quote?.ask ?: lastPrice

            Result.success(
                Ticker(
                    pair = symbol,
                    ask = ask,
                    bid = bid,
                    lastTrade = lastPrice,
                    volume = volume,
                    volumeWeightedAverage = lastPrice,
                    numberOfTrades = 1,
                    low = lastPrice,
                    high = lastPrice,
                    opening = lastPrice
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getTicker exception for $symbol: ${e.message}", e)
            val errorMsg = e.message ?: "${e.javaClass.simpleName}: null"
            Result.failure(Exception("Alpaca ticker error: $errorMsg"))
        }
    }

    private suspend fun parseLatestTrade(symbol: String): Result<Pair<Double, Double>> {
        return try {
            val response = getDataApiService().getLatestTrade(symbol)
            if (!response.isSuccessful) {
                return Result.failure(Exception(extractErrorMessage(response)))
            }
            val body = response.body() ?: return Result.failure(Exception("empty response body"))
            val jsonString = body.string()
            if (jsonString.isBlank()) {
                return Result.failure(Exception("empty response body for $symbol"))
            }

            val root = Gson().fromJson(jsonString, JsonElement::class.java)
            if (root == null || !root.isJsonObject) {
                return Result.failure(Exception("unexpected response format for $symbol"))
            }

            val tradeObj = root.asJsonObject.getAsJsonObject("trade")
                ?: return Result.failure(Exception("no trade data for $symbol"))

            val price = tradeObj.get("p")?.asDouble
                ?: return Result.failure(Exception("no trade price for $symbol"))
            val size = tradeObj.get("s")?.asInt ?: 0
            val volume = size.toDouble()

            Result.success(price to volume)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class ParsedQuote(val bid: Double, val ask: Double)

    private fun parseLatestQuote(jsonString: String): ParsedQuote? {
        return try {
            if (jsonString.isBlank()) return null
            val root = Gson().fromJson(jsonString, JsonElement::class.java)
            if (root == null || !root.isJsonObject) return null
            val quoteObj = root.asJsonObject.getAsJsonObject("quote") ?: return null
            val bid = quoteObj.get("bp")?.asDouble ?: return null
            val ask = quoteObj.get("ap")?.asDouble ?: return null
            ParsedQuote(bid, ask)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse quote response: ${e.message}")
            null
        }
    }

    override suspend fun getOHLC(symbol: String, interval: Int): Result<List<OHLC>> {
        return try {
            acquireDataRateLimit()
            val normalizedSymbol = normalizeSymbol(symbol)
            val timeframe = intervalToTimeframe(interval)
            val now = ZonedDateTime.now(java.time.ZoneOffset.UTC)
            val startTime = when {
                interval <= 60 -> now.minus(21, ChronoUnit.DAYS)
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

                if (ohlcList.size < 50) {
                    Log.w(TAG, "Insufficient OHLC data for $symbol (${ohlcList.size} bars, need 50), trying daily fallback")
                    if (timeframe != "1Day") {
                        return getOHLCWithTimeframe(symbol, "1Day", now)
                    }
                    Result.failure(Exception("Not enough OHLC data for $symbol (timeframe=$timeframe, ${ohlcList.size} bars)"))
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
            val errorMsg = e.message ?: "${e.javaClass.simpleName}: null"
            Result.failure(Exception("Alpaca OHLC error: $errorMsg"))
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
            val errorMsg = e.message ?: "${e.javaClass.simpleName}: null"
            Result.failure(Exception("Daily fallback error: $errorMsg"))
        }
    }

    private fun parseBars(jsonString: String, symbol: String): List<OHLC> {
        if (jsonString.isBlank()) return emptyList()
        val jsonElement = try {
            Gson().fromJson(jsonString, JsonElement::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON response for $symbol: ${e.message}")
            return emptyList()
        }
        if (jsonElement == null || !jsonElement.isJsonObject) {
            Log.w(TAG, "Unexpected JSON type for $symbol: ${jsonElement?.javaClass?.simpleName ?: "null"}")
            return emptyList()
        }

        val barsElement = jsonElement.asJsonObject["bars"] ?: return emptyList()

        val barsList: List<Map<String, Any?>> = when {
            barsElement.isJsonArray -> {
                barsElement.asJsonArray.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    try {
                        element.asJsonObject.entrySet().associate { entry ->
                            entry.key to jsonElementToValue(entry.value)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse bar element in $symbol: ${e.message}")
                        null
                    }
                }
            }
            barsElement.isJsonObject -> {
                try {
                    val barsMap = barsElement.asJsonObject.entrySet().associate { entry ->
                        entry.key to if (entry.value.isJsonArray) {
                            entry.value.asJsonArray.mapNotNull { element ->
                                if (!element.isJsonObject) return@mapNotNull null
                                try {
                                    element.asJsonObject.entrySet().associate { innerEntry ->
                                        innerEntry.key to jsonElementToValue(innerEntry.value)
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        } else emptyList()
                    }
                    barsMap[symbol] ?: barsMap.values.firstOrNull() ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse multi-symbol bars for $symbol: ${e.message}")
                    emptyList()
                }
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

    private fun jsonElementToValue(element: JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isString -> primitive.asString
                    primitive.isNumber -> primitive.asNumber
                    primitive.isBoolean -> primitive.asBoolean
                    else -> ""
                }
            }
            else -> element.toString()
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
        notional: Double?,
        validate: Boolean
    ): Result<String> {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return Result.failure(Exception("Alpaca API key not configured. Enable stock trading and set your API credentials in Settings."))
        }
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
                qty = if (notional != null) null else quantity,
                notional = notional,
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
