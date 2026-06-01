package com.uscrooge.app.analysis

import android.util.Log
import com.google.gson.Gson
import com.uscrooge.app.data.model.FearGreedIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FearGreedService @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "FearGreedService"
        private const val API_URL = "https://api.alternative.me/fng/?limit=1"
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 1_000L
    }

    private var cachedIndex: FearGreedIndex? = null
    private var cacheTimestamp: Long = 0
    private val cacheTtlMs = 3_600_000L // 1 hour

    suspend fun fetchFearGreedIndex(forceRefresh: Boolean = false): Result<FearGreedIndex> {
        val now = System.currentTimeMillis()
        val cacheAge = now - cacheTimestamp
        if (!forceRefresh && cachedIndex != null && cacheAge < cacheTtlMs) {
            return Result.success(cachedIndex!!)
        }

        var lastError: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/json")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: "No body"
                    val code = response.code
                    if (attempt < MAX_RETRIES - 1 && code in 500..599) {
                        Log.w(TAG, "Transient error $code on attempt ${attempt + 1}/$MAX_RETRIES, retrying...")
                        lastError = Exception("Fear & Greed API error: $code - $body")
                        response.close()
                        delay(BASE_DELAY_MS * (1 shl attempt))
                        return@repeat
                    }
                    return Result.failure(Exception("Fear & Greed API error: $code - $body"))
                }

                val json = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                val root = gson.fromJson(json, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                val dataList = root?.get("data") as? List<Map<String, Any>>
                if (dataList.isNullOrEmpty()) {
                    return Result.failure(Exception("No data in Fear & Greed response"))
                }

                val entry = dataList.first()
                val rawValue = entry["value"]?.toString()
                val value = rawValue?.toIntOrNull()
                if (value == null) {
                    return Result.failure(Exception("Invalid value in Fear & Greed response: $rawValue"))
                }

                val classification = entry["value_classification"]?.toString() ?: ""
                val timestamp = entry["timestamp"]?.toString()?.toLongOrNull() ?: (now / 1000)

                val index = FearGreedIndex(
                    value = value,
                    classification = classification,
                    timestamp = timestamp
                )

                cachedIndex = index
                cacheTimestamp = now
                Log.d(TAG, "Fear & Greed Index: $value ($classification)")
                return Result.success(index)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Fear & Greed Index on attempt ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(BASE_DELAY_MS * (1 shl attempt))
                }
            }
        }

        Log.w(TAG, "All $MAX_RETRIES attempts failed")
        if (cachedIndex != null) {
            val cacheAgeSec = cacheAge / 1000
            Log.i(TAG, "Returning cached Fear & Greed Index from ${cacheAgeSec}s ago")
            return Result.success(cachedIndex!!)
        }
        return Result.failure(lastError ?: Exception("Failed to fetch Fear & Greed Index after $MAX_RETRIES attempts"))
    }
}
