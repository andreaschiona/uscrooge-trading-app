package com.uscrooge.app.data.api

import kotlinx.coroutines.delay
import kotlin.math.min

suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 10000,
    backoffFactor: Double = 2.0,
    retryableException: (Throwable) -> Boolean = { e ->
        e.message?.contains("timeout", ignoreCase = true) == true ||
        e.message?.contains("429", ignoreCase = true) == true ||
        e.message?.contains("rate limit", ignoreCase = true) == true ||
        e.message?.contains("network", ignoreCase = true) == true
    },
    block: suspend () -> Result<T>
): Result<T> {
    var currentDelayMs = initialDelayMs

    for (attempt in 0..maxRetries) {
        try {
            val result = block()
            if (result.isSuccess || attempt == maxRetries) {
                return result
            }

            val exception = result.exceptionOrNull()
            if (exception != null && !retryableException(exception)) {
                return result
            }

            if (attempt < maxRetries) {
                delay(currentDelayMs)
                currentDelayMs = min(currentDelayMs * backoffFactor, maxDelayMs.toDouble()).toLong()
            }
        } catch (e: Throwable) {
            if (!retryableException(e) || attempt == maxRetries) {
                return Result.failure(e)
            }
            delay(currentDelayMs)
            currentDelayMs = min(currentDelayMs * backoffFactor, maxDelayMs.toDouble()).toLong()
        }
    }

    return Result.failure(Exception("Max retries ($maxRetries) exceeded"))
}
