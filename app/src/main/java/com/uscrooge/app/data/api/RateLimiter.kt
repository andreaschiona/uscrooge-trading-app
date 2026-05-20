package com.uscrooge.app.data.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

class RateLimiter(
    private val permitsPerSecond: Double,
    private val maxBurstSize: Int = permitsPerSecond.toInt().coerceAtLeast(1)
) {
    private val mutex = Mutex()
    private var availableTokens: Double = maxBurstSize.toDouble()
    private var lastRefillTimestamp: Long = System.nanoTime()

    suspend fun acquire(tokens: Int = 1): Long {
        return mutex.withLock {
            refillTokens()
            val waitTime = calculateWaitTime(tokens)
            if (waitTime > 0) {
                kotlinx.coroutines.delay(waitTime)
                refillTokens()
            }
            availableTokens -= tokens
            waitTime
        }
    }

    suspend fun tryAcquire(tokens: Int = 1): Boolean {
        return mutex.withLock {
            refillTokens()
            if (availableTokens >= tokens) {
                availableTokens -= tokens
                true
            } else {
                false
            }
        }
    }

    private fun refillTokens() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
        val newTokens = elapsedSeconds * permitsPerSecond
        availableTokens = minOf(maxBurstSize.toDouble(), availableTokens + newTokens)
        lastRefillTimestamp = now
    }

    private fun calculateWaitTime(tokens: Int): Long {
        if (availableTokens >= tokens) return 0L
        val deficit = tokens - availableTokens
        val waitSeconds = deficit / permitsPerSecond
        return (waitSeconds * 1000).toLong().coerceAtLeast(100)
    }

    suspend fun getStatus(): RateLimiterStatus {
        return mutex.withLock {
            refillTokens()
            RateLimiterStatus(
                availableTokens = availableTokens,
                maxTokens = maxBurstSize.toDouble(),
                permitsPerSecond = permitsPerSecond
            )
        }
    }
}

data class RateLimiterStatus(
    val availableTokens: Double,
    val maxTokens: Double,
    val permitsPerSecond: Double
)
