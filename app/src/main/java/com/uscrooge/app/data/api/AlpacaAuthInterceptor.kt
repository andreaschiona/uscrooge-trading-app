package com.uscrooge.app.data.api

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * OkHttp interceptor that adds Alpaca API key authentication headers.
 * Alpaca uses simple header-based authentication:
 * - APCA-API-KEY-ID: your API key
 * - APCA-API-SECRET-KEY: your API secret
 */
class AlpacaAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("APCA-API-KEY-ID", apiKey)
            .header("APCA-API-SECRET-KEY", apiSecret)
            .header("Content-Type", "application/json")
            .build()

        return chain.proceed(request)
    }
}
