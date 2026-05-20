package com.uscrooge.app.data.api

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RateLimitInterceptor(
    private val rateLimiter: RateLimiter,
    private val isPrivateEndpoint: (String) -> Boolean = { path ->
        path.startsWith("/0/private/")
    }
) : Interceptor {

    companion object {
        private const val TAG = "RateLimitInterceptor"
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 1000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        var retryCount = 0
        var lastException: IOException? = null

        while (retryCount <= MAX_RETRIES) {
            try {
                if (retryCount > 0) {
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl (retryCount - 1))
                    Log.w(TAG, "Rate limit retry $retryCount for $path, waiting ${delayMs}ms")
                    Thread.sleep(delayMs)
                }

                runBlocking {
                    rateLimiter.acquire()
                }

                val response = chain.proceed(request)

                if (response.code == 429) {
                    response.close()
                    retryCount++
                    lastException = IOException("Rate limit exceeded (HTTP 429)")
                    continue
                }

                val rateLimitRemaining = response.header("X-RateLimit-Remaining")
                val rateLimitReset = response.header("X-RateLimit-Reset")

                if (rateLimitRemaining != null) {
                    val remaining = rateLimitRemaining.toIntOrNull()
                    if (remaining != null && remaining <= 2) {
                        Log.w(TAG, "Rate limit almost exceeded: $remaining requests remaining")
                    }
                }

                return response
            } catch (e: IOException) {
                lastException = e
                if (e.message?.contains("429") == true || e.message?.contains("Rate limit") == true) {
                    retryCount++
                    continue
                }
                throw e
            }
        }

        throw lastException ?: IOException("Rate limit exceeded after $MAX_RETRIES retries")
    }
}
