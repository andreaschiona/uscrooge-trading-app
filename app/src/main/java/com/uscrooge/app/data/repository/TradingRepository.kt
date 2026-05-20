package com.uscrooge.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
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
    private val apiClient: KrakenApiClient,
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
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val technicalAnalyzer = TechnicalAnalyzer()

    private val _lastAnalysisLog = MutableStateFlow<AnalysisLog?>(null)
    val lastAnalysisLog: StateFlow<AnalysisLog?> = _lastAnalysisLog.asStateFlow()

    init {
        // Restore persisted analysis log
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
    suspend fun getTicker(pair: TradingPair): Result<Ticker> = apiClient.getTicker(pair)

    suspend fun getOHLC(pair: TradingPair, interval: Int = 60): Result<List<OHLC>> =
        apiClient.getOHLC(pair, interval)

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
                val result = apiClient.getOHLC(pair, timeframe)
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
            apiClient.updateCredentials(
                apiKey = it.krakenApiKey,
                apiSecret = it.krakenApiSecret,
                timeout = it.apiTimeout
            )
        }

        val positions = positionDao.getOpenPositions().first()
        val localTotalInvested = positions.sumOf { it.totalInvested }
        val localCurrentValue = positions.sumOf { it.currentValue }

        // Get balances from Kraken
        val balanceResult = apiClient.getAccountBalance()
        val balances = balanceResult.getOrNull().orEmpty()
        val availableFromBalance = balances.entries
            .filter { (asset, _) ->
                val normalizedAsset = asset.normalizeKrakenAsset()
                val suffix = asset.krakenAssetSuffix()
                normalizedAsset in EUR_ASSETS && suffix != "HOLD"
            }
            .sumOf { it.value }

        balanceResult.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Kraken Balance call failed: ${error.message}")
        }

        val tradeBalanceResult = apiClient.getTradeBalance("ZEUR")
        val tradeBalance = tradeBalanceResult.getOrNull()
        val availableFromTradeBalance = tradeBalance?.mf?.toDoubleOrNull()
            ?: tradeBalance?.tb?.toDoubleOrNull()

        val availableBalance = when {
            availableFromBalance > 0.0 -> availableFromBalance
            availableFromTradeBalance != null && availableFromTradeBalance > 0.0 -> availableFromTradeBalance
            else -> availableFromBalance
        }
        val availableBalanceSource = when {
            availableFromBalance > 0.0 -> "Balance"
            availableFromTradeBalance != null && availableFromTradeBalance > 0.0 -> "TradeBalance"
            else -> "None"
        }

        tradeBalanceResult.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Kraken TradeBalance call failed: ${error.message}")
        }
        if (availableBalance == 0.0) {
            Log.d(TAG, "Kraken Balance assets=${balances.keys.sorted()} availableFromBalance=$availableFromBalance")
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

            // Get OHLC data for primary timeframe
            val ohlcResult = getOHLC(tradingPair, config.primaryTimeframe)
            if (ohlcResult.isFailure) {
                return Result.failure(ohlcResult.exceptionOrNull()!!)
            }

            val ohlcData = ohlcResult.getOrNull()!!
            if (ohlcData.size < 50) {
                return Result.failure(Exception("Not enough historical data"))
            }

            // Get current price
            val tickerResult = getTicker(tradingPair)
            if (tickerResult.isFailure) {
                return Result.failure(tickerResult.exceptionOrNull()!!)
            }

            val currentPrice = tickerResult.getOrNull()!!.lastTrade

            // Multi-timeframe trend analysis
            val higherTimeframeTrends = if (config.useMultiTimeframe) {
                fetchHigherTimeframeTrends(tradingPair, config)
            } else {
                emptyList()
            }

            // Get current positions
            val currentPositions = positionDao.getOpenPositions().first()

            // Generate signal
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

        // Fetch secondary timeframe
        try {
            val secondaryResult = apiClient.getOHLC(pair, config.secondaryTimeframe)
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

        // Fetch tertiary timeframe
        try {
            val tertiaryResult = apiClient.getOHLC(pair, config.tertiaryTimeframe)
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

        // Fetch available balance from Kraken to use as budget
        val portfolio = getPortfolio(config)
        val availableBalance = portfolio.availableBalance

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

        val log = AnalysisLog(
            timestamp = System.currentTimeMillis(),
            entries = logEntries
        )
        _lastAnalysisLog.value = log
        // Persist to SharedPreferences
        try {
            prefs.edit().putString(KEY_LAST_LOG, gson.toJson(log)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist analysis log", e)
        }

        return signals
    }

    /**
     * Syncs open positions from Kraken by checking account balances and trade history.
     * For spot trading, Kraken doesn't track "open positions" - we reconstruct them
     * from asset balances and recent trades to calculate average entry price.
     */
    suspend fun syncOpenPositionsFromKraken(config: TradingConfig) {
        try {
            apiClient.updateCredentials(
                apiKey = config.krakenApiKey,
                apiSecret = config.krakenApiSecret,
                timeout = config.apiTimeout
            )

            // Get account balances to find which assets we hold
            val balanceResult = apiClient.getAccountBalance()
            if (balanceResult.isFailure) {
                Log.w(TAG, "syncOpenPositions: failed to get balances: ${balanceResult.exceptionOrNull()?.message}")
                return
            }
            val balances = balanceResult.getOrNull() ?: return

            // Map Kraken asset names to our pair format
            // e.g. "XXBT" -> "BTC", "XETH" -> "ETH", "SOL" -> "SOL"
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

            // Determine which pairs are configured
            val configuredBases = config.tradingPairs.map { pair ->
                TradingPair.fromString(pair).base
            }.toSet()

            // Find assets we hold that match configured trading pairs
            val heldAssets = mutableMapOf<String, Double>() // base -> amount
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

            // Get trades history to calculate average entry prices
            val tradesResult = apiClient.getTradesHistory()
            val trades = tradesResult.getOrNull() ?: emptyMap()

            // Group buy trades by base asset to compute average entry price
            val buyTradesByBase = mutableMapOf<String, MutableList<Pair<Double, Double>>>() // base -> list of (price, volume)
            for ((_, trade) in trades) {
                if (trade.type != "buy") continue
                val tradePair = trade.pair
                // Resolve base from Kraken pair name
                val base = resolveBaseFromKrakenPair(tradePair, krakenToBase) ?: continue
                if (base !in heldAssets) continue
                val price = trade.price.toDoubleOrNull() ?: continue
                val vol = trade.vol.toDoubleOrNull() ?: continue
                buyTradesByBase.getOrPut(base) { mutableListOf() }.add(price to vol)
            }

            // Now reconcile with local DB
            val localOpenPositions = positionDao.getOpenPositions().first()
            val localPairSet = localOpenPositions.map { it.pair }.toSet()

            for ((base, amount) in heldAssets) {
                val pairSymbol = "$base/EUR"  // Assuming EUR quote
                if (pairSymbol !in config.tradingPairs.map { it.uppercase() } &&
                    pairSymbol !in config.tradingPairs) continue

                // Calculate average entry price from buy trades
                val buyTrades = buyTradesByBase[base]
                val avgEntryPrice = if (!buyTrades.isNullOrEmpty()) {
                    val totalCost = buyTrades.sumOf { it.first * it.second }
                    val totalVol = buyTrades.sumOf { it.second }
                    if (totalVol > 0) totalCost / totalVol else 0.0
                } else {
                    0.0
                }

                // Get current price
                val currentPrice = try {
                    val tickerResult = apiClient.getTicker(TradingPair.fromString(pairSymbol))
                    tickerResult.getOrNull()?.lastTrade ?: avgEntryPrice
                } catch (e: Exception) {
                    avgEntryPrice
                }

                val totalInvested = amount * avgEntryPrice
                val currentValue = amount * currentPrice
                val unrealizedPnL = currentValue - totalInvested
                val unrealizedPnLPercent = if (totalInvested > 0) (unrealizedPnL / totalInvested) * 100 else 0.0

                // Check if we already have this position locally
                val existingPosition = positionDao.getOpenPositionByPair(pairSymbol)
                if (existingPosition != null) {
                    // Update existing position with current data from Kraken
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
                    // Create new position from Kraken data
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
                            isOpen = true
                        )
                    )
                }
            }

            // Close local positions that no longer exist on Kraken
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

    private fun resolveBaseFromKrakenPair(
        krakenPair: String,
        krakenToBase: Map<String, String>
    ): String? {
        // Try to match known patterns like "XXBTZEUR", "SOLEUR", "XETHZEUR"
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

    // Cleanup old data
    suspend fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
        signalDao.deleteOldSignals(cutoffTime)
    }
}
