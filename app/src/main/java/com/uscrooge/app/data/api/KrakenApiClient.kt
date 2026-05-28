package com.uscrooge.app.data.api

import com.uscrooge.app.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class KrakenApiClient(
    apiKey: String = "",
    apiSecret: String = "",
    timeout: Long = 30000,
    private val rateLimiter: RateLimiter = RateLimiter(permitsPerSecond = 2.0, maxBurstSize = 5)
) : BrokerApi {
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
    private var apiServiceCacheKey = ""

    @Volatile
    private var okHttpClientCache: OkHttpClient? = null

    @Volatile
    private var cachedPairs: List<String>? = null

    @Volatile
    private var cachedPairsTime: Long = 0L

    @Volatile
    private var cachedPairDecimals: Map<String, Int> = emptyMap()

    companion object {
        private const val PAIRS_CACHE_MS = 60 * 60 * 1000L // 1 hour
        // Kraken requires strictly-increasing nonces per API key. Unit is
        // nanoseconds-since-epoch (System.currentTimeMillis() * 1_000_000) which
        // is ~1000x the older "* 1000" microsecond formula. This change is
        // intentional and IRREVERSIBLE per API key: once a large nonce has been
        // accepted, reverting to a smaller unit will cause every subsequent
        // request to fail with EAPI:Invalid nonce until the user issues a new
        // API key (or a wider nonce window on Kraken's side).
        private val globalNonce = AtomicLong(0L)

        private fun nextNonce(): Long {
            while (true) {
                val now = System.currentTimeMillis() * 1_000_000L
                val last = globalNonce.get()
                val candidate = if (now > last) now else last + 1L
                if (globalNonce.compareAndSet(last, candidate)) {
                    return candidate
                }
            }
        }
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

    suspend fun getRateLimiterStatus(): RateLimiterStatus {
        return rateLimiter.getStatus()
    }

    /**
     * Releases the cached OkHttp dispatcher executor and connection pool.
     * Safe to call from short-lived [KrakenApiClient] instances (e.g. one-off
     * credential validation) to avoid leaking idle threads and sockets until
     * the next GC.
     */
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
                .addInterceptor(RateLimitInterceptor(rateLimiter))
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
            okHttpClientCache = okHttpClient
            return service
        }
    }

    // Public API methods

    suspend fun getServerTime(): Result<Long> {
        return try {
            val response = getApiService().getServerTime()

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                val unixTime = body.result?.unixtime
                    ?: return Result.failure(Exception("No server time data"))

                Result.success(unixTime)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAvailablePairs(quoteCurrency: String = "EUR"): List<String> {
        val now = System.currentTimeMillis()
        if (cachedPairs != null && (now - cachedPairsTime) < PAIRS_CACHE_MS) {
            return cachedPairs!!
        }

        return try {
            val response = getApiService().getAssetPairs()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return cachedPairs ?: emptyList()
                }
                val pairs = body.result?.values
                    ?.filter { info ->
                        val wsname = info.wsname ?: return@filter false
                        wsname.endsWith("/$quoteCurrency")
                    }
                    ?.mapNotNull { info -> info.wsname }
                    ?.sorted()
                    ?: emptyList()

                cachedPairs = pairs
                cachedPairsTime = now
                pairs
            } else {
                cachedPairs ?: emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("KrakenApiClient", "Failed to fetch asset pairs: ${e.message}")
            cachedPairs ?: emptyList()
        }
    }

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
                // Kraken may return the pair key in a different format than requested;
                // look for the first key that is not "last" (metadata field).
                val ohlcData = (result?.get(krakenPair) as? List<*>)
                    ?: (result?.entries
                        ?.firstOrNull { it.key != "last" && it.value is List<*> }
                        ?.value as? List<*>)
                    ?: return Result.failure(Exception("No OHLC data for pair $krakenPair"))

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

    suspend fun getKrakenAccountBalance(): Result<Map<String, Double>> {
        return try {
            val nonce = nextNonce()
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

    override suspend fun getAccountBalance(): Result<Map<String, Double>> {
        return getKrakenAccountBalance()
    }

    suspend fun getTradeBalance(asset: String = "ZEUR"): Result<TradeBalance> {
        return try {
            val nonce = nextNonce()
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

    private suspend fun getPairDecimals(krakenSymbol: String): Int {
        cachedPairDecimals[krakenSymbol]?.let { return it }
        return try {
            val response = getApiService().getAssetPairs(pair = krakenSymbol)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isEmpty() && body.result != null) {
                    val info = body.result.values.firstOrNull()
                    if (info != null) {
                        cachedPairDecimals = cachedPairDecimals + (krakenSymbol to info.pair_decimals)
                        return info.pair_decimals
                    }
                }
            }
            2
        } catch (e: Exception) {
            2
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
            val nonce = nextNonce()
            val krakenPair = pair.toKrakenSymbol()
            val formattedPrice = price?.let {
                val decimals = getPairDecimals(krakenPair)
                String.format(Locale.US, "%.${decimals}f", it)
            }

            val response = getApiService().addOrder(
                nonce = nonce,
                ordertype = orderType.toKrakenString(),
                type = type.name.lowercase(),
                volume = volume.toString(),
                pair = krakenPair,
                price = formattedPrice,
                validate = validate
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                if (validate) {
                    // Validation-only call: Kraken does not return txid
                    Result.success("validate")
                } else {
                    val orderId = body.result?.txid?.firstOrNull()
                        ?: return Result.failure(Exception("No order ID returned"))
                    Result.success(orderId)
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelKrakenOrder(orderId: String): Result<Boolean> {
        return try {
            val nonce = nextNonce()
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

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return cancelKrakenOrder(orderId)
    }

    suspend fun getKrakenOpenPositions(): Result<Map<String, PositionInfo>> {
        return try {
            val nonce = nextNonce()
            val response = getApiService().getOpenPositions(nonce)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(body.result ?: emptyMap())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getKrakenTradesHistory(start: Long? = null, end: Long? = null): Result<Map<String, TradeInfo>> {
        return try {
            val nonce = nextNonce()
            val response = getApiService().getTradesHistory(nonce, start = start, end = end)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(body.result?.trades ?: emptyMap())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getKrakenOpenOrders(): Result<Map<String, OrderInfo>> {
        return try {
            val nonce = nextNonce()
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

    suspend fun queryOrders(txids: List<String>): Result<Map<String, OrderInfo>> {
        return try {
            val nonce = nextNonce()
            val response = getApiService().queryOrders(nonce, txids.joinToString(","))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(body.result ?: emptyMap())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override val brokerName: String = "Kraken"

    override suspend fun getTicker(symbol: String): Result<Ticker> {
        return getTicker(TradingPair.fromString(symbol))
    }

    override suspend fun getOHLC(symbol: String, interval: Int): Result<List<OHLC>> {
        return getOHLC(TradingPair.fromString(symbol), interval)
    }

    override suspend fun getAvailableBalance(currency: String): Result<Double> {
        val balanceResult = getKrakenAccountBalance()
        return if (balanceResult.isSuccess) {
            val balances = balanceResult.getOrNull().orEmpty()
            val available = balances.entries
                .filter { (asset, _) ->
                    val normalized = asset.uppercase().substringBefore('.')
                    val suffix = asset.uppercase().substringAfter('.', missingDelimiterValue = "")
                    (normalized == currency || normalized == "Z$currency") && suffix != "HOLD"
                }
                .sumOf { it.value }
            Result.success(available)
        } else {
            Result.failure(balanceResult.exceptionOrNull()!!)
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
        return addOrder(
            pair = TradingPair.fromString(symbol),
            type = side,
            volume = quantity,
            orderType = orderType,
            price = limitPrice ?: stopPrice,
            validate = validate
        )
    }

    override suspend fun getOrder(orderId: String): Result<BrokerOrderInfo> {
        return try {
            val orderResult = queryOrder(orderId)
            if (orderResult.isSuccess) {
                val orderInfo = orderResult.getOrNull()
                if (orderInfo != null) {
                    Result.success(
                        BrokerOrderInfo(
                            orderId = orderId,
                            symbol = orderInfo.descr.pair,
                            side = orderInfo.descr.type,
                            type = orderInfo.descr.ordertype,
                            status = orderInfo.status,
                            quantity = orderInfo.vol.toDoubleOrNull() ?: 0.0,
                            filledQuantity = orderInfo.vol_exec.toDoubleOrNull() ?: 0.0,
                            price = orderInfo.price.toDoubleOrNull() ?: 0.0,
                            avgFillPrice = orderInfo.price.toDoubleOrNull() ?: 0.0,
                            fee = orderInfo.fee.toDoubleOrNull() ?: 0.0,
                            createdAt = (orderInfo.opentm * 1000).toLong(),
                            executedAt = if (orderInfo.status == "closed") (orderInfo.opentm * 1000).toLong() else null
                        )
                    )
                } else {
                    Result.failure(Exception("Order not found: $orderId"))
                }
            } else {
                Result.failure(orderResult.exceptionOrNull()!!)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOpenOrders(): Result<List<BrokerOrderInfo>> {
        return try {
            val ordersResult = getKrakenOpenOrders()
            if (ordersResult.isSuccess) {
                val orders = ordersResult.getOrNull().orEmpty().map { (id, info) ->
                    BrokerOrderInfo(
                        orderId = id,
                        symbol = info.descr.pair,
                        side = info.descr.type,
                        type = info.descr.ordertype,
                        status = info.status,
                        quantity = info.vol.toDoubleOrNull() ?: 0.0,
                        filledQuantity = info.vol_exec.toDoubleOrNull() ?: 0.0,
                        price = info.price.toDoubleOrNull() ?: 0.0,
                        avgFillPrice = info.price.toDoubleOrNull() ?: 0.0,
                        fee = info.fee.toDoubleOrNull() ?: 0.0,
                        createdAt = (info.opentm * 1000).toLong(),
                        executedAt = if (info.status == "closed") (info.opentm * 1000).toLong() else null
                    )
                }
                Result.success(orders)
            } else {
                Result.failure(ordersResult.exceptionOrNull()!!)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositions(): Result<List<BrokerPositionInfo>> {
        return try {
            val positionsResult = getKrakenOpenPositions()
            if (positionsResult.isSuccess) {
                val positions = positionsResult.getOrNull().orEmpty().map { (_, info) ->
                    val quantity = info.vol.toDoubleOrNull() ?: 0.0
                    val cost = info.cost.toDoubleOrNull() ?: 0.0
                    val avgEntryPrice = if (quantity > 0) cost / quantity else 0.0
                    val value = info.value?.toDoubleOrNull() ?: cost
                    val pnl = info.net?.toDoubleOrNull() ?: 0.0
                    val pnlPercent = if (cost > 0) (pnl / cost) * 100 else 0.0

                    BrokerPositionInfo(
                        symbol = info.pair,
                        side = if (info.type == "buy") "long" else "short",
                        quantity = quantity,
                        avgEntryPrice = avgEntryPrice,
                        currentPrice = if (quantity > 0) value / quantity else 0.0,
                        marketValue = value,
                        unrealizedPnL = pnl,
                        unrealizedPnLPercent = pnlPercent
                    )
                }
                Result.success(positions)
            } else {
                Result.failure(positionsResult.exceptionOrNull()!!)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun queryOrder(txid: String): Result<OrderInfo?> {
        return try {
            val nonce = nextNonce()
            val response = getApiService().queryOrders(nonce, txid)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error.isNotEmpty()) {
                    return Result.failure(Exception(body.error.joinToString()))
                }

                Result.success(body.result?.values?.firstOrNull())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
