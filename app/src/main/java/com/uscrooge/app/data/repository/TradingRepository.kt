package com.uscrooge.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.strategy.SignalResult
import com.uscrooge.app.strategy.TradingStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val strategy: TradingStrategy
) {
    companion object {
        private const val TAG = "TradingRepository"
        private val EUR_ASSETS = setOf("ZEUR", "EUR", "XEUR")
        private const val PREFS_NAME = "analysis_log_prefs"
        private const val KEY_LAST_LOG = "last_analysis_log"
        private val CRYPTO_ASSETS = setOf("BTC", "ETH", "SOL", "XRP", "DOT", "ADA", "MATIC", "LINK", "AVAX", "ATOM", "UNI", "LTC")
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val technicalAnalyzer = TechnicalAnalyzer()

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
        val base = pair.substringBefore("/").uppercase()
        return base in CRYPTO_ASSETS
    }

    // Signals
    fun getAllSignals(): Flow<List<TradingSignal>> = signalDao.getAllSignals()

    fun getPendingSignals(): Flow<List<TradingSignal>> =
        signalDao.getSignalsByStatus(SignalStatus.PENDING)

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
    suspend fun getTicker(pair: TradingPair): Result<Ticker> {
        return if (isCryptoPair(pair.symbol)) {
            krakenApiClient.getTicker(pair)
        } else {
            alpacaApiClient.getTicker(pair.base)
        }
    }

    suspend fun getOHLC(pair: TradingPair, interval: Int = 60): Result<List<OHLC>> {
        return if (isCryptoPair(pair.symbol)) {
            krakenApiClient.getOHLC(pair, interval)
        } else {
            alpacaApiClient.getOHLC(pair.base, interval)
        }
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

            val results = mutableMapOf<Int, List<OHLC>>()

            for (timeframe in timeframes) {
                val result = getOHLC(pair, timeframe)
                if (result.isSuccess) {
                    results[timeframe] = result.getOrNull()!!
                }
            }

            if (results.isEmpty()) {
                Result.failure(Exception("Failed to fetch any timeframe data"))
            } else {
                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Portfolio
    suspend fun getPortfolio(config: TradingConfig? = null): Portfolio {
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

        // Get Kraken balances
        val krakenBalanceResult = krakenApiClient.getAccountBalance()
        val krakenBalances = krakenBalanceResult.getOrNull().orEmpty()
        val availableFromKrakenBalance = krakenBalances.entries
            .filter { (asset, _) ->
                val normalizedAsset = asset.normalizeKrakenAsset()
                val suffix = asset.krakenAssetSuffix()
                normalizedAsset in EUR_ASSETS && suffix != "HOLD"
            }
            .sumOf { it.value }

        krakenBalanceResult.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Kraken Balance call failed: ${error.message}")
        }

        val tradeBalanceResult = krakenApiClient.getTradeBalance("ZEUR")
        val tradeBalance = tradeBalanceResult.getOrNull()
        val availableFromTradeBalance = tradeBalance?.mf?.toDoubleOrNull()
            ?: tradeBalance?.tb?.toDoubleOrNull()

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

        tradeBalanceResult.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Kraken TradeBalance call failed: ${error.message}")
        }

        // Get Alpaca buying power if enabled
        val alpacaBalanceResult = alpacaApiClient.getAvailableBalance("USD")
        if (alpacaBalanceResult.isSuccess) {
            val alpacaBalance = alpacaBalanceResult.getOrNull() ?: 0.0
            if (alpacaBalance > 0.0) {
                availableBalance += alpacaBalance
                availableBalanceSource = if (availableBalanceSource == "None") "Alpaca" else "$availableBalanceSource + Alpaca"
            }
        }

        if (availableBalance == 0.0) {
            Log.d(TAG, "Kraken Balance assets=${krakenBalances.keys.sorted()} availableFromKrakenBalance=$availableFromKrakenBalance")
            Log.d(
                TAG,
                "Kraken TradeBalance mf=${tradeBalance?.mf} tb=${tradeBalance?.tb} e=${tradeBalance?.e}"
            )
        }

        val krakenTotalInvested = tradeBalance?.c?.toDoubleOrNull()
        val krakenCurrentValue = tradeBalance?.v?.toDoubleOrNull()
        val krakenTotalPnL = tradeBalance?.n?.toDoubleOrNull()

        val totalInvested = krakenTotalInvested ?: localTotalInvested
        val currentValue = krakenCurrentValue ?: localCurrentValue
        val totalPnL = krakenTotalPnL ?: (currentValue - totalInvested)
        val totalPnLPercent = if (totalInvested > 0) (totalPnL / totalInvested) * 100 else 0.0

        return Portfolio(
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
        availableBalance: Double
    ): Result<SignalResult> {
        return try {
            val tradingPair = TradingPair.fromString(pair)

            val ohlcResult = getOHLC(tradingPair, config.primaryTimeframe)
            if (ohlcResult.isFailure) {
                return Result.failure(ohlcResult.exceptionOrNull()!!)
            }

            val ohlcData = ohlcResult.getOrNull()!!
            if (ohlcData.size < 50) {
                return Result.failure(Exception("Not enough historical data"))
            }

            val tickerResult = getTicker(tradingPair)
            if (tickerResult.isFailure) {
                return Result.failure(tickerResult.exceptionOrNull()!!)
            }

            val currentPrice = tickerResult.getOrNull()!!.lastTrade

            val higherTimeframeTrends = if (config.useMultiTimeframe) {
                fetchHigherTimeframeTrends(tradingPair, config)
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
                higherTimeframeTrends = higherTimeframeTrends
            )

            signalResult.signal?.let { insertSignal(it) }

            Result.success(signalResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchHigherTimeframeTrends(
        pair: TradingPair,
        config: TradingConfig
    ): List<Trend> {
        val trends = mutableListOf<Trend>()

        try {
            val secondaryResult = getOHLC(pair, config.secondaryTimeframe)
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
            val tertiaryResult = getOHLC(pair, config.tertiaryTimeframe)
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

        // Analyze crypto pairs
        for (pair in config.tradingPairs) {
            try {
                val result = analyzePairAndGenerateSignal(pair, config, availableBalance)
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
                            availableBalance = availableBalance
                        )
                    )
                } else {
                    val error = result.exceptionOrNull()
                    Log.w(TAG, "Analysis failed for $pair: ${error?.message}", error)
                    logEntries.add(
                        AnalysisLogEntry(
                            pair = pair,
                            isSuccess = false,
                            errorMessage = error?.message ?: "Unknown error"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error analyzing $pair: ${e.message}", e)
                logEntries.add(
                    AnalysisLogEntry(
                        pair = pair,
                        isSuccess = false,
                        errorMessage = e.message ?: "Unexpected error"
                    )
                )
            }
        }

        // Analyze stock pairs if enabled
        if (config.enableStockTrading && config.alpacaApiKey.isNotBlank()) {
            for (pair in config.stockTradingPairs) {
                try {
                    val result = analyzePairAndGenerateSignal(pair, config, availableBalance)
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
                                availableBalance = availableBalance
                            )
                        )
                    } else {
                        val error = result.exceptionOrNull()
                        Log.w(TAG, "Stock analysis failed for $pair: ${error?.message}", error)
                        logEntries.add(
                            AnalysisLogEntry(
                                pair = pair,
                                isSuccess = false,
                                errorMessage = error?.message ?: "Unknown error"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error analyzing stock $pair: ${e.message}", e)
                    logEntries.add(
                        AnalysisLogEntry(
                            pair = pair,
                            isSuccess = false,
                            errorMessage = e.message ?: "Unexpected error"
                        )
                    )
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

            val balanceResult = krakenApiClient.getAccountBalance()
            if (balanceResult.isFailure) {
                Log.w(TAG, "syncOpenPositions: failed to get balances: ${balanceResult.exceptionOrNull()?.message}")
                return
            }
            val balances = balanceResult.getOrNull() ?: return

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

            val heldAssets = mutableMapOf<String, Double>()
            for ((krakenAsset, amount) in balances) {
                if (amount <= 0.0) continue
                val normalized = krakenAsset.normalizeKrakenAsset()
                val suffix = krakenAsset.krakenAssetSuffix()
                if (suffix == "HOLD") continue

                val base = krakenToBase[normalized] ?: normalized
                if (base in configuredBases) {
                    heldAssets[base] = (heldAssets[base] ?: 0.0) + amount
                }
            }

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

            val localOpenPositions = positionDao.getOpenPositions().first()

            for ((base, amount) in heldAssets) {
                val pairSymbol = "$base/EUR"
                if (pairSymbol !in config.tradingPairs.map { it.uppercase() } &&
                    pairSymbol !in config.tradingPairs) continue

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

            for (localPos in localOpenPositions) {
                val base = TradingPair.fromString(localPos.pair).base
                if (base !in heldAssets) {
                    positionDao.updatePosition(
                        localPos.copy(
                            isOpen = false,
                            closedAt = System.currentTimeMillis(),
                            realizedPnL = localPos.unrealizedPnL,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            Log.d(TAG, "syncOpenPositions: synced ${heldAssets.size} positions from Kraken")
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
            for (localPos in localOpenPositions) {
                if (localPos.broker == "Alpaca") {
                    val symbol = localPos.pair.substringBefore("/")
                    val stillExists = alpacaPositions.any { it.symbol == symbol }
                    if (!stillExists) {
                        positionDao.updatePosition(
                            localPos.copy(
                                isOpen = false,
                                closedAt = System.currentTimeMillis(),
                                realizedPnL = localPos.unrealizedPnL,
                                updatedAt = System.currentTimeMillis()
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
        val eurSuffixes = listOf("ZEUR", "EUR")
        val usdSuffixes = listOf("ZUSD", "USD")
        val allSuffixes = eurSuffixes + usdSuffixes

        for (suffix in allSuffixes) {
            if (krakenPair.endsWith(suffix)) {
                val rawBase = krakenPair.removeSuffix(suffix)
                return krakenToBase[rawBase] ?: rawBase
            }
        }
        return null
    }

    suspend fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        signalDao.deleteOldSignals(cutoffTime)
    }
}
