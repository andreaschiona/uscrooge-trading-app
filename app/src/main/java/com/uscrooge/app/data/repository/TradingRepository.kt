package com.uscrooge.app.data.repository

import android.util.Log
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradingRepository @Inject constructor(
    private val apiClient: KrakenApiClient,
    private val signalDao: TradingSignalDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val strategy: TradingStrategy
) {
    companion object {
        private const val TAG = "TradingRepository"
        private val EUR_ASSETS = setOf("ZEUR", "EUR", "XEUR")
    }

    private val _lastAnalysisLog = MutableStateFlow<AnalysisLog?>(null)
    val lastAnalysisLog: StateFlow<AnalysisLog?> = _lastAnalysisLog.asStateFlow()

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
        config: TradingConfig
    ): Result<TradingSignal?> {
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

            // Get current positions
            val currentPositions = positionDao.getOpenPositions().first()

            // Generate signal
            val signal = strategy.generateSignal(
                pair = pair,
                ohlcData = ohlcData,
                currentPrice = currentPrice,
                currentPositions = currentPositions
            )

            signal?.let { insertSignal(it) }

            Result.success(signal)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun analyzeAllPairs(config: TradingConfig): List<TradingSignal> {
        val signals = mutableListOf<TradingSignal>()
        val logEntries = mutableListOf<AnalysisLogEntry>()

        for (pair in config.tradingPairs) {
            try {
                val result = analyzePairAndGenerateSignal(pair, config)
                if (result.isSuccess) {
                    val signal = result.getOrNull()
                    signal?.let { signals.add(it) }
                    logEntries.add(
                        AnalysisLogEntry(
                            pair = pair,
                            isSuccess = true,
                            signalType = signal?.type,
                            strength = signal?.strength
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

        _lastAnalysisLog.value = AnalysisLog(
            timestamp = System.currentTimeMillis(),
            entries = logEntries
        )

        return signals
    }

    // Cleanup old data
    suspend fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
        signalDao.deleteOldSignals(cutoffTime)
    }
}
