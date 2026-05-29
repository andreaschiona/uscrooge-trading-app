package com.uscrooge.app.analysis

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.uscrooge.app.data.model.FearGreedIndex
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

        return try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "No body"
                return Result.failure(Exception("Fear & Greed API error: ${response.code} - $body"))
            }

            val json = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val apiResponse = gson.fromJson(json, FearGreedApiResponse::class.java)

            if (apiResponse?.data.isNullOrEmpty()) {
                return Result.failure(Exception("No data in Fear & Greed response"))
            }

            val entry = apiResponse.data.first()
            val value = entry.value.toIntOrNull()
            if (value == null) {
                return Result.failure(Exception("Invalid value in Fear & Greed response: ${entry.value}"))
            }

            val index = FearGreedIndex(
                value = value,
                classification = entry.valueClassification,
                timestamp = entry.timestamp.toLongOrNull() ?: (now / 1000)
            )

            cachedIndex = index
            cacheTimestamp = now
            Log.d(TAG, "Fear & Greed Index: $value (${entry.valueClassification})")
            Result.success(index)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Fear & Greed Index", e)
            if (cachedIndex != null) {
                val cacheAgeSec = cacheAge / 1000
                Log.i(TAG, "Returning cached Fear & Greed Index from ${cacheAgeSec}s ago")
                Result.success(cachedIndex!!)
            }
            Result.failure(e)
        }
    }

    private data class FearGreedApiResponse(
        val name: String? = null,
        val data: List<FearGreedDataEntry> = emptyList()
    )

    private data class FearGreedDataEntry(
        val value: String = "",
        @SerializedName("value_classification")
        val valueClassification: String = "",
        val timestamp: String = ""
    )
}
