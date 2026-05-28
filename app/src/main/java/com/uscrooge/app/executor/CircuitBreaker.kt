package com.uscrooge.app.executor

import android.util.Log
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.model.TradingConfig
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Circuit breaker that halts automatic trading when dangerous conditions are detected:
 * - Daily drawdown exceeds threshold
 * - Consecutive order failures exceed limit
 * - Max daily trades reached
 *
 * Once tripped, trading is halted for a configurable cooldown period.
 */
@Singleton
class CircuitBreaker @Inject constructor(
    private val orderDao: OrderDao,
    private val positionDao: PositionDao
) {
    companion object {
        private const val TAG = "CircuitBreaker"
    }

    private val consecutiveFailures = AtomicInteger(0)
    private val trippedAt = AtomicLong(0L)

    @Volatile
    private var tripReason: String? = null

    /**
     * Returns null if trading is allowed, or a reason string if it should be blocked.
     */
    suspend fun checkTradingAllowed(config: TradingConfig): String? {
        if (!config.circuitBreakerEnabled) return null

        // Check cooldown
        val trippedTime = trippedAt.get()
        if (trippedTime > 0) {
            val elapsed = System.currentTimeMillis() - trippedTime
            val cooldownMs = config.circuitBreakerCooldownMinutes * 60_000L
            if (elapsed < cooldownMs) {
                val remainingMin = (cooldownMs - elapsed) / 60_000L
                return "Circuit breaker active: $tripReason (${remainingMin}min remaining)"
            } else {
                // Cooldown expired, reset
                reset()
            }
        }

        // Check max daily trades
        val startOfDay = getStartOfDayMillis()
        val dailyTradeCount = orderDao.getTradeCountSince(startOfDay)
        if (dailyTradeCount >= config.maxDailyTrades) {
            trip("Max daily trades reached ($dailyTradeCount/${config.maxDailyTrades})")
            return tripReason
        }

        // Check consecutive failures
        if (consecutiveFailures.get() >= config.maxConsecutiveFailures) {
            trip("${config.maxConsecutiveFailures} consecutive order failures")
            return tripReason
        }

        // Check daily drawdown
        val drawdownCheck = checkDailyDrawdown(config)
        if (drawdownCheck != null) {
            trip(drawdownCheck)
            return tripReason
        }

        return null
    }

    /**
     * Call when an order succeeds.
     */
    fun recordSuccess() {
        consecutiveFailures.set(0)
    }

    /**
     * Call when an order fails.
     */
    fun recordFailure() {
        consecutiveFailures.incrementAndGet()
        Log.w(TAG, "Order failure recorded. Consecutive: ${consecutiveFailures.get()}")
    }

    /**
     * Manually reset the circuit breaker (e.g. from UI).
     */
    fun reset() {
        trippedAt.set(0L)
        tripReason = null
        consecutiveFailures.set(0)
        Log.i(TAG, "Circuit breaker reset")
    }

    fun isTripped(): Boolean = trippedAt.get() > 0

    fun getTripReason(): String? = tripReason

    private fun trip(reason: String) {
        trippedAt.set(System.currentTimeMillis())
        tripReason = reason
        Log.e(TAG, "CIRCUIT BREAKER TRIPPED: $reason")
    }

    private suspend fun checkDailyDrawdown(config: TradingConfig): String? {
        val positions = positionDao.getOpenPositions().first()
        if (positions.isEmpty()) return null

        val totalInvested = positions.sumOf { it.totalInvested }
        if (totalInvested <= 0) return null

        val totalPnL = positions.sumOf { it.unrealizedPnL }
        val drawdownPercent = (totalPnL / totalInvested) * 100

        if (drawdownPercent < -config.maxDailyDrawdownPercent) {
            return "Daily drawdown ${String.format(Locale.US, "%.1f", drawdownPercent)}% exceeds limit of -${config.maxDailyDrawdownPercent}%"
        }

        return null
    }

    private fun getStartOfDayMillis(): Long {
        val now = System.currentTimeMillis()
        return now - (now % (24 * 60 * 60 * 1000))
    }
}
