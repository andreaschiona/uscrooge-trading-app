package com.uscrooge.app.data.repository

import android.util.Log
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.model.BrokerHealth
import com.uscrooge.app.data.model.BrokerHealthStatus
import com.uscrooge.app.data.model.FearGreedHealth
import com.uscrooge.app.data.model.SystemHealth
import com.uscrooge.app.di.BrokerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.google.gson.Gson

@Singleton
class HealthCheckRepository @Inject constructor(
    private val brokerRegistry: BrokerRegistry,
    private val configRepository: ConfigRepository
) {
    companion object {
        private const val TAG = "HealthCheckRepo"
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val FEAR_GREED_URL = "https://api.alternative.me/fng/?limit=1"
        private const val SLOW_THRESHOLD_MS = 2000L
    }

    private val _systemHealth = MutableStateFlow(
        SystemHealth(
            brokers = emptyMap(),
            fearGreed = null,
            lastUpdated = 0L
        )
    )
    val systemHealth: StateFlow<SystemHealth> = _systemHealth.asStateFlow()

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                checkAll()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun checkAll() {
        try {
            val config = configRepository.configFlow.first()
            val healthMap = mutableMapOf<String, BrokerHealth>()

            for (broker in brokerRegistry.getActiveBrokers(config)) {
                checkBroker(broker)?.let { healthMap[it.brokerName] = it }
            }

            val fearGreed = checkFearGreed()

            _systemHealth.value = SystemHealth(
                brokers = healthMap,
                fearGreed = fearGreed,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Health check cycle failed: ${e.message}", e)
        }
    }

    private suspend fun checkBroker(broker: BrokerApi): BrokerHealth? {
        return try {
            broker.health().getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed for ${broker.brokerName}: ${e.message}")
            BrokerHealth(
                brokerName = broker.brokerName,
                status = BrokerHealthStatus.OFFLINE,
                latencyMs = -1,
                lastError = e.message,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    private suspend fun checkFearGreed(): FearGreedHealth? {
        val startTime = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(FEAR_GREED_URL)
                .get()
                .build()
            val response = withContext(Dispatchers.IO) {
                checkClient.newCall(request).execute()
            }
            val latency = System.currentTimeMillis() - startTime

            if (response.isSuccessful && response.body != null) {
                val bodyStr = response.body!!.string()
                val json = gson.fromJson(bodyStr, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                val data = (json["data"] as? List<Map<String, Any>>)?.firstOrNull()
                val value = data?.get("value")?.toString()?.toIntOrNull()

                val status = when {
                    latency > SLOW_THRESHOLD_MS -> BrokerHealthStatus.SLOW
                    else -> BrokerHealthStatus.ONLINE
                }
                FearGreedHealth(
                    value = value,
                    status = status,
                    latencyMs = latency,
                    lastError = null,
                    lastChecked = startTime
                )
            } else {
                FearGreedHealth(
                    value = null,
                    status = BrokerHealthStatus.OFFLINE,
                    latencyMs = latency,
                    lastError = "HTTP ${response.code}",
                    lastChecked = startTime
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            FearGreedHealth(
                value = null,
                status = BrokerHealthStatus.OFFLINE,
                latencyMs = latency,
                lastError = e.message,
                lastChecked = startTime
            )
        }
    }

    fun triggerManualCheck(scope: CoroutineScope) {
        scope.launch { checkAll() }
    }
}
