package com.uscrooge.app.di

import android.util.Log
import com.google.gson.Gson
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the single mutable instances of broker API clients and keeps their
 * credentials/timeout in sync with [ConfigRepository.configFlow].
 *
 * Supports multiple brokers (Kraken for crypto, Alpaca for stocks).
 * Each broker operates independently - if credentials are not configured,
 * that broker is simply disabled.
 */
@Singleton
class BrokerRegistry @Inject constructor(
    private val configRepository: ConfigRepository,
    private val gitHubIssueReporter: GitHubIssueReporter,
    private val gson: Gson? = null,
    private val okHttpClient: OkHttpClient? = null
) {

    val krakenApiClient: KrakenApiClient = KrakenApiClient(
        apiKey = "",
        apiSecret = "",
        timeout = 30_000L,
        sharedGson = gson,
        sharedOkHttp = okHttpClient
    )

    val alpacaApiClient: AlpacaApiClient = AlpacaApiClient(
        apiKey = "",
        apiSecret = "",
        isPaperTrading = true,
        timeout = 30_000L,
        sharedGson = gson,
        sharedOkHttp = okHttpClient
    )

    /**
     * Returns all configured brokers that have valid credentials.
     */
    fun getActiveBrokers(config: TradingConfig): List<BrokerApi> {
        val brokers = mutableListOf<BrokerApi>()
        if (config.enableCryptoTrading &&
            config.krakenApiKey.isNotBlank() && config.krakenApiSecret.isNotBlank()) {
            brokers.add(krakenApiClient)
        }
        if (config.enableStockTrading &&
            config.alpacaApiKey.isNotBlank() &&
            config.alpacaApiSecret.isNotBlank()) {
            brokers.add(alpacaApiClient)
        }
        return brokers
    }

    /**
     * Returns the broker for a given pair/symbol.
     * Pairs with EUR quote currency -> Kraken (crypto)
     * Pairs with USD quote currency -> Alpaca (stocks)
     */
    fun getBrokerForPair(config: TradingConfig, pair: String): BrokerApi? {
        val quote = pair.substringAfter("/").uppercase()
        return when (quote) {
            "EUR" -> {
                if (config.enableCryptoTrading &&
                    config.krakenApiKey.isNotBlank() && config.krakenApiSecret.isNotBlank()) krakenApiClient else null
            }
            "USD" -> {
                if (config.enableStockTrading &&
                    config.alpacaApiKey.isNotBlank() &&
                    config.alpacaApiSecret.isNotBlank()) alpacaApiClient else null
            }
            else -> null
        }
    }

    private val applyMutex = Mutex()
    private var strategyRef: TradingStrategy? = null
    private var executorRef: OrderExecutor? = null

    fun start(
        appScope: CoroutineScope,
        tradingStrategy: TradingStrategy,
        orderExecutor: OrderExecutor
    ) {
        strategyRef = tradingStrategy
        executorRef = orderExecutor
        appScope.launch {
            configRepository.configFlow
                .distinctUntilChanged()
                .catch { e ->
                    Log.e(TAG, "configFlow failed; config propagation stopped", e)
                }
                .collect { config -> applyConfig(config) }
        }
    }

    suspend fun applyConfig(config: TradingConfig) {
        applyMutex.withLock {
            krakenApiClient.updateCredentials(
                apiKey = config.krakenApiKey.trim(),
                apiSecret = config.krakenApiSecret.trim(),
                timeout = config.apiTimeout
            )
            alpacaApiClient.updateCredentials(
                apiKey = config.alpacaApiKey.trim(),
                apiSecret = config.alpacaApiSecret.trim(),
                timeout = config.apiTimeout,
                paperTrading = config.alpacaPaperTrading
            )
            strategyRef?.updateConfig(config)
            executorRef?.updateConfig(config)
            gitHubIssueReporter.configureToken(config.githubToken)
        }
    }

    private companion object {
        const val TAG = "BrokerRegistry"
    }
}
