package com.uscrooge.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.uscrooge.app.analysis.FearGreedService
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.PositionSelectionStrategy
import com.uscrooge.app.strategy.SignalResult
import com.uscrooge.app.strategy.TradingStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val krakenApiClient: KrakenApiClient,
    private val alpacaApiClient: AlpacaApiClient,
    private val brokerRegistry: BrokerRegistry,
    private val signalDao: TradingSignalDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val strategy: TradingStrategy,
    private val gson: Gson,
    private val fearGreedService: FearGreedService,
    private val sentimentAnalyzer: SentimentAnalyzer,
    private val gitHubIssueReporter: GitHubIssueReporter,
    private val positionSelectionStrategy: PositionSelectionStrategy
) {
    companion object {
        private const val TAG = "TradingRepository"
        private val EUR_ASSETS = setOf("ZEUR", "EUR", "XEUR")
        private const val PREFS_NAME = "analysis_log_prefs"
        private const val KEY_LAST_LOG = "last_analysis_log"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val technicalAnalyzer = TechnicalAnalyzer()

    // In-memory OHLC cache with TTL
    private data class OhlcCacheEntry(val data: List<OHLC>, val cachedAt: Long)
    private val ohlcCache = mutableMapOf<String, OhlcCacheEntry>()
    private val ohlcCacheTtlMs = 60_000L // 1 minute

    private val _lastAnalysisLog = MutableStateFlow<AnalysisLog?>(null)
    val lastAnalysisLog: StateFlow<AnalysisLog?> = _lastAnalysisLog.asStateFlow()

    init {
        val json = prefs.getString(KEY_LAST_LOG, null)
        if (json != null) {
            try {
                _lastAnalysisLog.value = gson.fromJson(json, AnalysisLog::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore analysis log", e)
            }
        }
    }

    private fun String.normalizeKrakenAsset(): String = uppercase().substringBefore('.')

    private fun String.krakenAssetSuffix(): String? =
        uppercase().substringAfter('.', missingDelimiterValue = "").ifEmpty { null }

    private fun getBrokerForPair(config: TradingConfig, pair: String): BrokerApi? {
        return brokerRegistry.getBrokerForPair(config, pair)
    }

    private fun isCryptoPair(pair: String): Boolean {
        val quote = pair.substringAfter("/").uppercase()
        return quote == "EUR"
    }

    // Signals
    fun getAllSignals(): Flow<List<TradingSignal>> = signalDao.getAllSignals()

    fun getPendingSignals(): Flow<List<TradingSignal>> =
        signalDao.getSignalsByStatus(SignalStatus.PENDING)

    suspend fun getPendingSignalsList(): List<TradingSignal> =
        signalDao.getSignalsByStatusList(SignalStatus.PENDING)

    suspend fun getPendingSignalByPair(pair: String): TradingSignal? =
        signalDao.getPendingSignalByPair(pair, SignalStatus.PENDING)

    suspend fun getSignalById(id: Long): TradingSignal? = signalDao.getSignalById(id)

    suspend fun insertSignal(signal: TradingSignal): Long = signalDao.insertSignal(signal)

    suspend fun updateSignal(signal: TradingSignal) = signalDao.updateSignal(signal)

    // Orders
    fun getAllOrders(): Flow<List<Order>> = orderDao.getAllOrders()

    fun getOrdersByStatus(status: OrderStatus): Flow<List<Order>> =
        orderDao.getOrdersByStatus(status)

    suspend fun insertOrder(order: Order) = orderDao.insertOrder(order)

    // Positions
    fun getOpenPositions(): Flow<List<Position>> = positionDao.getOpenPositions()

    fun getAllPositions(): Flow<List<Position>> = positionDao.getAllPositions()

    suspend fun getOpenPositionByPair(pair: String): Position? =
        positionDao.getOpenPositionByPair(pair)

    suspend fun insertPosition(position: Position): Long = positionDao.insertPosition(position)

    suspend fun updatePosition(position: Position) = positionDao.updatePosition(position)

    // Market data
    suspend fun getTicker(pair: TradingPair, broker: BrokerApi? = null): Result<Ticker> {
        val activeBroker = broker ?: if (isCryptoPair(pair.symbol)) krakenApiClient else alpacaApiClient
        return activeBroker.getTicker(pair.symbol)
    }

    suspend fun getOHLC(pair: TradingPair, interval: Int = 60, broker: BrokerApi? = null): Result<List<OHLC>> {
        val cacheKey = "${pair.symbol}_$interval"
        val now = System.currentTimeMillis()
        ohlcCache[cacheKey]?.let { entry ->
            if (now - entry.cachedAt < ohlcCacheTtlMs) {
                return Result.success(entry.data)
            }
        }
        val activeBroker = broker ?: if (isCryptoPair(pair.symbol)) krakenApiClient else alpacaApiClient
        val result = activeBroker.getOHLC(pair.symbol, interval)
        if (result.isSuccess) {
            ohlcCache[cacheKey] = OhlcCacheEntry(result.getOrNull()!!, now)
        }
        return result
    }

    suspend fun getMultiTimeframeOHLC(
        pair: TradingPair,
        config: TradingConfig
    ): Result<Map<Int, List<OHLC>>> {
        return try {
            val timeframes = listOf(
                config.primaryTimeframe,
                config.secondaryTimeframe,
                config.tertiaryTimeframe
            )

            coroutineScope {
                val deferred = timeframes.map { timeframe ->
                    async {
                        timeframe to getOHLC(pair, timeframe)
                    }
                }
                val results = mutableMapOf<Int, List<OHLC>>()
                deferred.forEach { deferredResult ->
                    val (timeframe, result) = deferredResult.await()
                    if (result.isSuccess) {
                        results[timeframe] = result.getOrNull()!!
                    }
                }
                if (results.isEmpty()) {
                    Result.failure(Exception("Failed to fetch any timeframe data"))
                } else {
                    Result.success(results)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Portfolio
    suspend fun getPortfolio(config: TradingConfig? = null): Portfolio = coroutineScope {
        config?.let {
            krakenApiClient.updateCredentials(
                apiKey = it.krakenApiKey,
                apiSecret = it.krakenApiSecret,
                timeout = it.apiTimeout
            )
            alpacaApiClient.updateCredentials(
                apiKey = it.alpacaApiKey,
                apiSecret = it.alpacaApiSecret,
                timeout = it.apiTimeout
            )
        }

        val positions = positionDao.getOpenPositions().first()
        val localTotalInvested = positions.sumOf { it.totalInvested }
        val localCurrentValue = positions.sumOf { it.currentValue }

        // Fetch balances from Kraken if crypto trading is enabled
        val cryptoEnabled = config?.krakenApiKey.isNullOrBlank().not()
        val krakenBalanceDeferred = if (cryptoEnabled) {
            async { krakenApiClient.getAccountBalance() }
        } else null
        val tradeBalanceDeferred = if (cryptoEnabled) {
            async { krakenApiClient.getTradeBalance("ZEUR") }
        } else null

        val krakenKeyInfo: Set<String> = if (cryptoEnabled) {
            val krakenBalanceResult = krakenBalanceDeferred!!.await()
            val krakenBalances = krakenBalanceResult.getOrNull().orEmpty()

            krakenBalanceResult.exceptionOrNull()?.let { error ->
                Log.w(TAG, "Kraken Balance call failed: ${error.message}")
            }

            krakenBalances.keys
        } else {
            emptySet()
        }

        val (availableFromKrakenBalance, availableFromTradeBalance) = if (cryptoEnabled) {
            val krakenBalanceResult = krakenBalanceDeferred!!.await()
            val krakenBalances = krakenBalanceResult.getOrNull().orEmpty()
            val balance = krakenBalances.entries
                .filter { (asset, _) ->
                    val normalizedAsset = asset.normalizeKrakenAsset()
                    val suffix = asset.krakenAssetSuffix()
                    normalizedAsset in EUR_ASSETS && suffix != "HOLD"
                }
                .sumOf { it.value }

            val tradeBalanceResult = tradeBalanceDeferred!!.await()
            val tradeBal = tradeBalanceResult.getOrNull()?.let { tb ->
                tb.mf?.toDoubleOrNull() ?: tb.tb?.toDoubleOrNull()
            }

            Pair(balance, tradeBal)
        } else {
            Pair(0.0, null)
        }

        var availableBalance = when {
            availableFromKrakenBalance > 0.0 -> availableFromKrakenBalance
            availableFromTradeBalance != null && availableFromTradeBalance > 0.0 -> availableFromTradeBalance
            else -> availableFromKrakenBalance
        }
        var availableBalanceSource = when {
            availableFromKrakenBalance > 0.0 -> "Kraken Balance"
            availableFromTradeBalance != null && availableFromTradeBalance > 0.0 -> "Kraken TradeBalance"
            else -> "None"
        }

        // Get Alpaca buying power if enabled (concurrently with Kraken calls)
        val alpacaEnabled = config?.enableStockTrading == true && config.alpacaApiKey.isNotBlank()
        if (alpacaEnabled) {
            val alpacaBalanceResult = alpacaApiClient.getAvailableBalance("USD")
            if (alpacaBalanceResult.isSuccess) {
                val alpacaBalance = alpacaBalanceResult.getOrNull() ?: 0.0
                Log.d(TAG, "Alpaca available balance: $alpacaBalance USD (paper=${config?.alpacaPaperTrading})")
                if (alpacaBalance > 0.0) {
                    availableBalance += alpacaBalance
                    availableBalanceSource = if (availableBalanceSource == "None") "Alpaca" else "$availableBalanceSource + Alpaca"
                }
            } else {
                Log.w(TAG, "Alpaca balance fetch failed: ${alpacaBalanceResult.exceptionOrNull()?.message}")
            }
        }

        if (availableBalance == 0.0 && cryptoEnabled) {
            Log.d(TAG, "Kraken Balance assets=${krakenKeyInfo.sorted()} availableFromKrakenBalance=$availableFromKrakenBalance")
        }

        // Compute totals from all locally-stored positions (Kraken + Alpaca).
        val totalInvested = localTotalInvested
        val currentValue = localCurrentValue
        val totalPnL = currentValue - totalInvested
        val totalPnLPercent = if (totalInvested > 0) (totalPnL / totalInvested) * 100 else 0.0

        Portfolio(
            totalInvested = totalInvested,
            currentValue = currentValue,
            totalPnL = totalPnL,
            totalPnLPercent = totalPnLPercent,
            positions = positions,
            availableBalance = availableBalance,
            availableBalanceSource = availableBalanceSource
        )
    }

    // Signal generation
    suspend fun analyzePairAndGenerateSignal(
        pair: String,
        config: TradingConfig,
        availableBalance: Double,
        broker: BrokerApi,
        sentiment: FearGreedIndex? = null
    ): Result<SignalResult> {
        return try {
            val tradingPair = TradingPair.fromString(pair)

            val ohlcResult = getOHLC(tradingPair, config.primaryTimeframe, broker)
            if (ohlcResult.isFailure) {
                return Result.failure(ohlcResult.exceptionOrNull()!!)
            }

            val ohlcData = ohlcResult.getOrNull()!!
            if (ohlcData.size < 50) {
                return Result.failure(Exception("Not enough historical data"))
            }

            val tickerResult = getTicker(tradingPair, broker)
            if (tickerResult.isFailure) {
                return Result.failure(tickerResult.exceptionOrNull()!!)
            }

            val currentPrice = tickerResult.getOrNull()!!.lastTrade

            val higherTimeframeTrends = if (config.useMultiTimeframe) {
                fetchHigherTimeframeTrends(tradingPair, config, broker)
            } else {
                emptyList()
            }

            val currentPositions = positionDao.getOpenPositions().first()

            val signalResult = strategy.generateSignal(
                pair = pair,
                ohlcData = ohlcData,
                currentPrice = currentPrice,
                currentPositions = currentPositions,
                availableBalance = availableBalance,
                higherTimeframeTrends = higherTimeframeTrends,
                sentiment = sentiment
            )

            signalResult.signal?.let { newSignal ->
                val existingPending = getPendingSignalByPair(pair)
                if (existingPending != null) {
                    updateSignal(
                        existingPending.copy(
                            type = newSignal.type,
                            strength = newSignal.strength,
                            currentPrice = newSignal.currentPrice,
                            suggestedPrice = newSignal.suggestedPrice,
                            stopLoss = newSignal.stopLoss,
                            takeProfit = newSignal.takeProfit,
                            suggestedAmount = newSignal.suggestedAmount,
                            riskRewardRatio = newSignal.riskRewardRatio,
                            timestamp = System.currentTimeMillis(),
                            reasons = newSignal.reasons
                        )
                    )
                } else {
                    insertSignal(newSignal)
                }
            }

            Result.success(signalResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchHigherTimeframeTrends(
        pair: TradingPair,
        config: TradingConfig,
        broker: BrokerApi
    ): List<Trend> {
        val trends = mutableListOf<Trend>()

        try {
            val secondaryResult = getOHLC(pair, config.secondaryTimeframe, broker)
            if (secondaryResult.isSuccess) {
                val data = secondaryResult.getOrNull()!!
                if (data.size >= 10) {
                    val prices = data.map { it.close }
                    trends.add(technicalAnalyzer.detectTrend(prices))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch secondary timeframe for ${pair.base}: ${e.message}")
        }

        try {
            val tertiaryResult = getOHLC(pair, config.tertiaryTimeframe, broker)
            if (tertiaryResult.isSuccess) {
                val data = tertiaryResult.getOrNull()!!
                if (data.size >= 10) {
                    val prices = data.map { it.close }
                    trends.add(technicalAnalyzer.detectTrend(prices))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch tertiary timeframe for ${pair.base}: ${e.message}")
        }

        return trends
    }

    suspend fun analyzeAllPairs(config: TradingConfig): List<TradingSignal> {
        val signals = mutableListOf<TradingSignal>()
        val logEntries = mutableListOf<AnalysisLogEntry>()

        val portfolio = getPortfolio(config)
        val availableBalance = portfolio.availableBalance

        // Fetch Fear & Greed Index once per cycle (if enabled)
        val globalSentiment = if (config.sentimentEnabled) {
            val result = fearGreedService.fetchFearGreedIndex()
            if (result.isSuccess) {
                result.getOrNull()
            } else {
                val error = result.exceptionOrNull()
                val errorDetail = error?.let { "${it::class.simpleName}: ${it.message}" } ?: "Unknown"
                Log.w(TAG, "Fear & Greed fetch failed: $errorDetail", error)
                // Report to GitHub
                try {
                    gitHubIssueReporter.reportError(
                        title = "Fear & Greed Index fetch failed",
                        body = "Error: $errorDetail\n\nTimestamp: ${System.currentTimeMillis()}\n\nStack trace:\n${error?.stackTraceToString() ?: "N/A"}",
                        labels = listOf("bug", "auto-reported", "sentiment")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to report sentiment error to GitHub", e)
                }
                null
            }
        } else null

        // Build crypto list: wishlist always included, supplemented by dynamic Kraken pairs
        // or CoinGecko-powered position selection when enabled.
        val cryptoPairsToAnalyze = run {
            val wishlistPairs = config.tradingPairs.map { it.uppercase() }.toSet()

            if (config.enablePositionSelection) {
                val rankingConfig = config.toAssetRankingConfig()
                val currentPositions = positionDao.getOpenPositions().first()
                val rankingResult = positionSelectionStrategy.selectTopPositions(
                    currentPositions = currentPositions,
                    config = rankingConfig
                )

                if (rankingResult.isSuccess) {
                    val rankedAssets = rankingResult.getOrNull()!!
                    val rankedPairs = rankedAssets.map { "${it.symbol}/EUR" }
                    val combined = (wishlistPairs + rankedPairs)
                        .distinct()
                        .take(config.maxCryptoPairsToScan)
                    Log.d(TAG, "Position selection: ranked ${rankedAssets.size} assets, analyzing ${combined.size} pairs (wishlist + ranked)")
                    combined
                } else {
                    val fallbackPairs = (wishlistPairs + krakenApiClient.getAvailablePairs("EUR"))
                        .distinct()
                        .sortedWith(compareByDescending { it in wishlistPairs })
                        .take(config.maxCryptoPairsToScan)
                    Log.d(TAG, "Position selection failed, fallback to ${fallbackPairs.size} pairs")
                    fallbackPairs
                }
            } else {
                val quoteCurrency = config.tradingPairs.firstOrNull()
                    ?.substringAfter("/")?.uppercase() ?: "EUR"
                val dynamicKrakenPairs = krakenApiClient.getAvailablePairs(quoteCurrency)
                (wishlistPairs + dynamicKrakenPairs)
                    .distinct()
                    .sortedWith(compareByDescending { it in wishlistPairs })
                    .take(config.maxCryptoPairsToScan)
                    .also { scanned ->
                        Log.d(TAG, "Analyzing ${scanned.size} crypto pairs (${wishlistPairs.size} wishlist + dynamic from Kraken, capped at ${config.maxCryptoPairsToScan})")
                    }
            }
        }

        for (pair in cryptoPairsToAnalyze) {
            try {
                val result = analyzePairAndGenerateSignal(pair, config, availableBalance, krakenApiClient, sentiment = globalSentiment)
                if (result.isSuccess) {
                    val signalResult = result.getOrNull()!!
                    signalResult.signal?.let { signals.add(it) }
                    val analysis = signalResult.analysis
                    logEntries.add(
                        AnalysisLogEntry(
                            pair = pair,
                            isSuccess = true,
                            signalType = signalResult.signal?.type,
                            strength = signalResult.signal?.strength,
                            currentPrice = analysis.currentPrice,
                            rsiValue = analysis.rsi.value,
                            rsiSignal = analysis.rsi.signal.name,
                            macdHistogram = analysis.macd.histogram,
                            macdSignal = analysis.macd.signal.name,
                            trend = analysis.trend.name,
                            volumeRatio = analysis.volume.volumeRatio,
                            candlestickPattern = analysis.candlestickPattern?.name,
                            availableBalance = availableBalance,
                            broker = "Kraken"
                        )
                    )
                } else {
                    val error = result.exceptionOrNull()
                    Log.w(TAG, "Analysis failed for $pair: ${error?.message}", error)
                    logEntries.add(
                        AnalysisLogEntry(
                            pair = pair,
                            isSuccess = false,
                            errorMessage = error?.message ?: "Unknown error",
                            broker = "Kraken"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error analyzing $pair: ${e.message}", e)
                logEntries.add(
                    AnalysisLogEntry(
                        pair = pair,
                        isSuccess = false,
                        errorMessage = e.message ?: "Unexpected error",
                        broker = "Kraken"
                    )
                )
            }
        }

        // Analyze stock pairs if enabled
        if (config.enableStockTrading && config.alpacaApiKey.isNotBlank()) {
            val marketOpen = alpacaApiClient.isMarketOpen()
            if (!marketOpen) {
                Log.d(TAG, "US stock market is closed, skipping stock analysis")
                logEntries.add(
                    AnalysisLogEntry(
                        pair = "MARKET",
                        isSuccess = true,
                        signalType = null,
                        strength = null,
                        errorMessage = "US market closed - analysis skipped",
                        broker = "Alpaca"
                    )
                )
            } else {
                val dynamicAssets = alpacaApiClient.getAvailableAssets()
                val wishlistBases = config.stockTradingPairs
                    .map { it.substringBefore("/").uppercase() }
                    .toSet()
                // Wishlist always included, dynamic list fills remaining slots
                val stocksToAnalyze = (wishlistBases.toList() + dynamicAssets)
                    .distinct()
                    .sortedWith(compareByDescending { it in wishlistBases })
                    .take(config.maxStockPairsToScan)

                Log.d(TAG, "Analyzing ${stocksToAnalyze.size} stocks (${wishlistBases.size} wishlist + dynamic, capped at ${config.maxStockPairsToScan}): ${stocksToAnalyze.joinToString(", ")}")

                for (symbol in stocksToAnalyze) {
                    val pair = "$symbol/USD"
                    try {
                        val result = analyzePairAndGenerateSignal(pair, config, availableBalance, alpacaApiClient, sentiment = globalSentiment)
                        if (result.isSuccess) {
                            val signalResult = result.getOrNull()!!
                            signalResult.signal?.let { signals.add(it) }
                            val analysis = signalResult.analysis
                            logEntries.add(
                                AnalysisLogEntry(
                                    pair = pair,
                                    isSuccess = true,
                                    signalType = signalResult.signal?.type,
                                    strength = signalResult.signal?.strength,
                                    currentPrice = analysis.currentPrice,
                                    rsiValue = analysis.rsi.value,
                                    rsiSignal = analysis.rsi.signal.name,
                                    macdHistogram = analysis.macd.histogram,
                                    macdSignal = analysis.macd.signal.name,
                                    trend = analysis.trend.name,
                                    volumeRatio = analysis.volume.volumeRatio,
                                    candlestickPattern = analysis.candlestickPattern?.name,
                                    availableBalance = availableBalance,
                                    broker = "Alpaca"
                                )
                            )
                        } else {
                            val error = result.exceptionOrNull()
                            Log.w(TAG, "Stock analysis failed for $pair: ${error?.message}", error)
                            logEntries.add(
                                AnalysisLogEntry(
                                    pair = pair,
                                    isSuccess = false,
                                    errorMessage = error?.message ?: "Unknown error",
                                    broker = "Alpaca"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error analyzing stock $pair: ${e.message}", e)
                        logEntries.add(
                            AnalysisLogEntry(
                                pair = pair,
                                isSuccess = false,
                                errorMessage = e.message ?: "Unexpected error",
                                broker = "Alpaca"
                            )
                        )
                    }
                }
            }
        }

        val log = AnalysisLog(
            timestamp = System.currentTimeMillis(),
            entries = logEntries
        )
        _lastAnalysisLog.value = log
        try {
            prefs.edit().putString(KEY_LAST_LOG, gson.toJson(log)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist analysis log", e)
        }

        return signals
    }

    suspend fun syncOpenPositionsFromKraken(config: TradingConfig) {
        try {
            krakenApiClient.updateCredentials(
                apiKey = config.krakenApiKey,
                apiSecret = config.krakenApiSecret,
                timeout = config.apiTimeout
            )

            val krakenToBase = mapOf(
                "XXBT" to "BTC", "XBT" to "BTC",
                "XETH" to "ETH", "ETH" to "ETH",
                "XXRP" to "XRP", "XRP" to "XRP",
                "SOL" to "SOL", "DOT" to "DOT",
                "ADA" to "ADA", "MATIC" to "MATIC",
                "LINK" to "LINK", "AVAX" to "AVAX",
                "ATOM" to "ATOM", "UNI" to "UNI",
                "LTC" to "LTC", "XLTC" to "LTC"
            )

            val configuredBases = config.tradingPairs.map { pair ->
                TradingPair.fromString(pair).base
            }.toSet()

            // 1) Fetch Kraken margin positions via the dedicated endpoint
            val krakenPositions = krakenApiClient.getOpenPositions().getOrNull().orEmpty()

            // 2) Fetch account balances for spot holdings not covered by margin positions
            val balanceResult = krakenApiClient.getAccountBalance()
            if (balanceResult.isFailure) {
                Log.w(TAG, "syncOpenPositions: failed to get balances: ${balanceResult.exceptionOrNull()?.message}")
            }
            val balances = balanceResult.getOrNull().orEmpty()

            // Track which bases have active positions (from margin or spot)
            val activeBases = mutableSetOf<String>()

            // --- Process margin positions (OpenPositions endpoint) ---
            for (pos in krakenPositions) {
                val base = resolveBaseFromKrakenPair(pos.symbol, krakenToBase) ?: continue
                if (base !in configuredBases) continue

                val pairSymbol = "$base/EUR"
                if (pairSymbol !in config.tradingPairs.map { it.uppercase() } &&
                    pairSymbol !in config.tradingPairs) continue

                activeBases.add(base)

                val quantity = pos.quantity
                val avgEntryPrice = pos.avgEntryPrice
                val currentPrice = pos.currentPrice
                val totalInvested = quantity * avgEntryPrice
                val currentValue = quantity * currentPrice

                val existingPosition = positionDao.getOpenPositionByPair(pairSymbol)
                if (existingPosition != null) {
                    positionDao.updatePosition(
                        existingPosition.copy(
                            amount = quantity,
                            currentPrice = currentPrice,
                            currentValue = currentValue,
                            unrealizedPnL = pos.unrealizedPnL,
                            unrealizedPnLPercent = pos.unrealizedPnLPercent,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    positionDao.insertPosition(
                        Position(
                            pair = pairSymbol,
                            amount = quantity,
                            averageEntryPrice = avgEntryPrice,
                            currentPrice = currentPrice,
                            totalInvested = totalInvested,
                            currentValue = currentValue,
                            unrealizedPnL = pos.unrealizedPnL,
                            unrealizedPnLPercent = pos.unrealizedPnLPercent,
                            openedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            isOpen = true,
                            broker = "Kraken"
                        )
                    )
                }
            }

            // --- Process spot holdings (balance snapshot) ---
            val heldAssets = mutableMapOf<String, Double>()
            for ((krakenAsset, amount) in balances) {
                if (amount <= 0.0) continue
                val normalized = krakenAsset.normalizeKrakenAsset()
                val suffix = krakenAsset.krakenAssetSuffix()
                if (suffix == "HOLD") continue

                val base = krakenToBase[normalized] ?: normalized
                if (base !in EUR_ASSETS && base !in activeBases) {
                    heldAssets[base] = (heldAssets[base] ?: 0.0) + amount
                }
            }

            if (heldAssets.isNotEmpty()) {
                val tradesResult = krakenApiClient.getKrakenTradesHistory()
                val trades = tradesResult.getOrNull() ?: emptyMap()

                val buyTradesByBase = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
                for ((_, trade) in trades) {
                    if (trade.type != "buy") continue
                    val tradePair = trade.pair
                    val base = resolveBaseFromKrakenPair(tradePair, krakenToBase) ?: continue
                    if (base !in heldAssets) continue
                    val price = trade.price.toDoubleOrNull() ?: continue
                    val vol = trade.vol.toDoubleOrNull() ?: continue
                    buyTradesByBase.getOrPut(base) { mutableListOf() }.add(price to vol)
                }

                for ((base, amount) in heldAssets) {
                    val pairSymbol = "$base/EUR"
                    val orderMinimum = krakenApiClient.getOrderMinimum("${base}EUR")
                    if (orderMinimum > 0.0 && amount < orderMinimum) {
                        Log.d(TAG, "syncOpenPositions: skipping $base balance $amount below minimum $orderMinimum")
                        continue
                    }
                    activeBases.add(base)

                    val buyTrades = buyTradesByBase[base]
                    val avgEntryPrice = if (!buyTrades.isNullOrEmpty()) {
                        val totalCost = buyTrades.sumOf { it.first * it.second }
                        val totalVol = buyTrades.sumOf { it.second }
                        if (totalVol > 0) totalCost / totalVol else 0.0
                    } else {
                        0.0
                    }

                    val currentPrice = try {
                        val tickerResult = krakenApiClient.getTicker(TradingPair.fromString(pairSymbol))
                        tickerResult.getOrNull()?.lastTrade ?: avgEntryPrice
                    } catch (e: Exception) {
                        avgEntryPrice
                    }

                    val totalInvested = amount * avgEntryPrice
                    val currentValue = amount * currentPrice
                    val unrealizedPnL = currentValue - totalInvested
                    val unrealizedPnLPercent = if (totalInvested > 0) (unrealizedPnL / totalInvested) * 100 else 0.0

                    val existingPosition = positionDao.getOpenPositionByPair(pairSymbol)
                    if (existingPosition != null) {
                        positionDao.updatePosition(
                            existingPosition.copy(
                                amount = amount,
                                currentPrice = currentPrice,
                                currentValue = currentValue,
                                unrealizedPnL = unrealizedPnL,
                                unrealizedPnLPercent = unrealizedPnLPercent,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    } else {
                        positionDao.insertPosition(
                            Position(
                                pair = pairSymbol,
                                amount = amount,
                                averageEntryPrice = avgEntryPrice,
                                currentPrice = currentPrice,
                                totalInvested = totalInvested,
                                currentValue = currentValue,
                                unrealizedPnL = unrealizedPnL,
                                unrealizedPnLPercent = unrealizedPnLPercent,
                                openedAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                isOpen = true,
                                broker = "Kraken"
                            )
                        )
                    }
                }
            }

            // --- Close Kraken positions that no longer exist on the exchange ---
            val localOpenPositions = positionDao.getOpenPositions().first()
            val now = System.currentTimeMillis()
            val minAgeToClose = config.checkIntervalSeconds * 1000L

            for (localPos in localOpenPositions) {
                if (localPos.broker != "Kraken") continue

                val base = TradingPair.fromString(localPos.pair).base
                if (base !in activeBases) {
                    // Do not close freshly opened positions whose buy order
                    // may not have settled on the exchange yet.
                    if (now - localPos.openedAt < minAgeToClose) continue

                    positionDao.updatePosition(
                        localPos.copy(
                            isOpen = false,
                            closedAt = now,
                            realizedPnL = localPos.unrealizedPnL,
                            updatedAt = now
                        )
                    )
                }
            }

            Log.d(TAG, "syncOpenPositions: synced ${activeBases.size} positions from Kraken")
        } catch (e: Exception) {
            Log.e(TAG, "syncOpenPositions failed: ${e.message}", e)
        }
    }

    suspend fun syncOpenPositionsFromAlpaca(config: TradingConfig) {
        try {
            alpacaApiClient.updateCredentials(
                apiKey = config.alpacaApiKey,
                apiSecret = config.alpacaApiSecret,
                timeout = config.apiTimeout
            )

            val positionsResult = alpacaApiClient.getOpenPositions()
            if (positionsResult.isFailure) {
                Log.w(TAG, "syncAlpacaPositions: failed to get positions: ${positionsResult.exceptionOrNull()?.message}")
                return
            }

            val alpacaPositions = positionsResult.getOrNull().orEmpty()
            val localOpenPositions = positionDao.getOpenPositions().first()

            for (alpacaPos in alpacaPositions) {
                val pairSymbol = "${alpacaPos.symbol}/USD"
                val quantity = alpacaPos.quantity
                val avgEntryPrice = alpacaPos.avgEntryPrice
                val currentPrice = alpacaPos.currentPrice
                val totalInvested = quantity * avgEntryPrice
                val currentValue = quantity * currentPrice
                val unrealizedPnL = alpacaPos.unrealizedPnL
                val unrealizedPnLPercent = alpacaPos.unrealizedPnLPercent

                val existingPosition = positionDao.getOpenPositionByPair(pairSymbol)
                if (existingPosition != null) {
                    positionDao.updatePosition(
                        existingPosition.copy(
                            amount = quantity,
                            currentPrice = currentPrice,
                            currentValue = currentValue,
                            unrealizedPnL = unrealizedPnL,
                            unrealizedPnLPercent = unrealizedPnLPercent,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    positionDao.insertPosition(
                        Position(
                            pair = pairSymbol,
                            amount = quantity,
                            averageEntryPrice = avgEntryPrice,
                            currentPrice = currentPrice,
                            totalInvested = totalInvested,
                            currentValue = currentValue,
                            unrealizedPnL = unrealizedPnL,
                            unrealizedPnLPercent = unrealizedPnLPercent,
                            openedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            isOpen = true,
                            broker = "Alpaca"
                        )
                    )
                }
            }

            // Close local Alpaca positions that no longer exist
            val now = System.currentTimeMillis()
            val minAgeToClose = config.checkIntervalSeconds * 1000L

            for (localPos in localOpenPositions) {
                if (localPos.broker == "Alpaca") {
                    // Do not close freshly opened positions whose buy order
                    // may not have settled on the exchange yet.
                    if (now - localPos.openedAt < minAgeToClose) continue

                    val symbol = localPos.pair.substringBefore("/")
                    val stillExists = alpacaPositions.any { it.symbol == symbol }
                    if (!stillExists) {
                        positionDao.updatePosition(
                            localPos.copy(
                                isOpen = false,
                                closedAt = now,
                                realizedPnL = localPos.unrealizedPnL,
                                updatedAt = now
                            )
                        )
                    }
                }
            }

            Log.d(TAG, "syncAlpacaPositions: synced ${alpacaPositions.size} positions from Alpaca")
        } catch (e: Exception) {
            Log.e(TAG, "syncAlpacaPositions failed: ${e.message}", e)
        }
    }

    private fun resolveBaseFromKrakenPair(
        krakenPair: String,
        krakenToBase: Map<String, String>
    ): String? {
        val normalized = krakenPair.replace("/", "")
        val eurSuffixes = listOf("ZEUR", "EUR")
        val usdSuffixes = listOf("ZUSD", "USD")
        val allSuffixes = eurSuffixes + usdSuffixes

        for (suffix in allSuffixes) {
            if (normalized.endsWith(suffix)) {
                val rawBase = normalized.removeSuffix(suffix)
                return krakenToBase[rawBase] ?: rawBase
            }
        }
        return null
    }

    suspend fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        signalDao.deleteOldSignals(cutoffTime)
    }

    suspend fun validatePendingSignals(config: TradingConfig) {
        val pendingSignals = getPendingSignalsList()
        if (pendingSignals.isEmpty()) return

        val openPositions = positionDao.getOpenPositions().first()
        val availableBalance = getPortfolio(config).availableBalance

        for (signal in pendingSignals) {
            try {
                val tradingPair = TradingPair.fromString(signal.pair)
                val broker = getBrokerForPair(config, signal.pair)
                    ?: continue

                val ohlcResult = getOHLC(tradingPair, config.primaryTimeframe, broker)
                if (ohlcResult.isFailure) continue

                val ohlcData = ohlcResult.getOrNull()!!
                if (ohlcData.size < 50) {
                    markSignalAsMissed(signal)
                    continue
                }

                val tickerResult = getTicker(tradingPair, broker)
                if (tickerResult.isFailure) {
                    markSignalAsMissed(signal)
                    continue
                }

                val currentPrice = tickerResult.getOrNull()!!.lastTrade

                val result = strategy.generateSignal(
                    pair = signal.pair,
                    ohlcData = ohlcData,
                    currentPrice = currentPrice,
                    currentPositions = openPositions,
                    availableBalance = availableBalance
                )

                val newSignal = result.signal
                if (newSignal != null && newSignal.type == signal.type && newSignal.strength >= config.minSignalStrength) {
                    updateSignal(
                        signal.copy(
                            strength = newSignal.strength,
                            currentPrice = newSignal.currentPrice,
                            suggestedPrice = newSignal.suggestedPrice,
                            stopLoss = newSignal.stopLoss,
                            takeProfit = newSignal.takeProfit,
                            suggestedAmount = newSignal.suggestedAmount,
                            riskRewardRatio = newSignal.riskRewardRatio,
                            timestamp = System.currentTimeMillis(),
                            reasons = newSignal.reasons
                        )
                    )
                    Log.d(TAG, "Updated pending signal ${signal.id} for ${signal.pair} - still valid")
                } else {
                    markSignalAsMissed(signal)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error validating signal ${signal.id} for ${signal.pair}: ${e.message}")
            }
        }
    }

    private suspend fun markSignalAsMissed(signal: TradingSignal) {
        updateSignal(signal.copy(status = SignalStatus.MISSED))
        Log.d(TAG, "Signal ${signal.id} for ${signal.pair} marked as MISSED")
    }
}

fun TradingConfig.toAssetRankingConfig(): AssetRankingConfig {
    return AssetRankingConfig(
        vsCurrency = "usd",
        scanLimit = positionSelectionScanLimit,
        minMarketCap = positionSelectionMinMarketCap,
        minVolume24h = positionSelectionMinVolume,
        maxAssetsToRank = positionSelectionMaxResults,
        volumeWeight = positionSelectionVolumeWeight,
        momentumWeight = positionSelectionMomentumWeight,
        liquidityWeight = positionSelectionLiquidityWeight,
        volatilityWeight = positionSelectionVolatilityWeight
    )
}
