package com.uscrooge.app.di

import android.util.Log
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the single mutable instances of broker API clients and keeps their
 * credentials/timeout in sync with [ConfigRepository.configFlow].
 *
 * Rationale: previously the [KrakenApiClient] singleton was constructed with
 * empty credentials in [com.uscrooge.app.UScroogeApplication] and never refreshed
 * when the user saved Settings (except as a side effect of opening the Dashboard),
 * which silently broke background workers. This class is the single source of
 * truth: every consumer must inject [BrokerRegistry] (or one of its providers)
 * instead of constructing a client directly.
 */
@Singleton
class BrokerRegistry @Inject constructor(
    private val configRepository: ConfigRepository
) {

    // Created once with empty credentials and mutated in place via
    // KrakenApiClient.updateCredentials. Consumers always get the same instance.
    val krakenApiClient: KrakenApiClient = KrakenApiClient(
        apiKey = "",
        apiSecret = "",
        timeout = 30_000L
    )

    private val applyMutex = Mutex()
    private var strategyRef: TradingStrategy? = null
    private var executorRef: OrderExecutor? = null

    /**
     * Must be called once from [com.uscrooge.app.UScroogeApplication.onCreate]
     * with an application-scoped coroutine scope and the strategy/executor
     * instances obtained from the Hilt graph. Subsequent config changes are
     * propagated to:
     *  - [KrakenApiClient] credentials/timeout
     *  - [TradingStrategy] active config
     *  - [OrderExecutor] active config
     *
     * Strategy/executor are passed as parameters (not injected here) to avoid
     * a circular dependency: both consume [KrakenApiClient], which is provided
     * to the Hilt graph by [ApiModule] from this registry.
     */
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
                    // ConfigRepository.configFlow only catches IOException; any
                    // other failure would otherwise kill this collector silently
                    // for the rest of the process lifetime.
                    Log.e(TAG, "configFlow failed; config propagation stopped", e)
                }
                .collect { config -> applyConfig(config) }
        }
    }

    /**
     * Pushes [config] into the Kraken client, strategy and executor synchronously.
     * Safe to call from a [androidx.work.CoroutineWorker] on cold start before
     * the [start] collector has emitted, to avoid sending private API requests
     * with empty credentials.
     *
     * Idempotent and serialized via a mutex so concurrent calls (worker + flow
     * collector) cannot interleave partial updates.
     */
    suspend fun applyConfig(config: TradingConfig) {
        applyMutex.withLock {
            krakenApiClient.updateCredentials(
                apiKey = config.krakenApiKey.trim(),
                apiSecret = config.krakenApiSecret.trim(),
                timeout = config.apiTimeout
            )
            strategyRef?.updateConfig(config)
            executorRef?.updateConfig(config)
        }
    }

    private companion object {
        const val TAG = "BrokerRegistry"
    }
}
