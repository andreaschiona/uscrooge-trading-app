package com.uscrooge.app.data.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class PriceUpdate(
    val pair: String,
    val price: Double,
    val bid: Double,
    val ask: Double,
    val volume: Double,
    val timestamp: Long
)

@Singleton
class KrakenWebSocketClient @Inject constructor() {

    companion object {
        private const val TAG = "KrakenWebSocket"
        private const val WS_URL = "wss://ws.kraken.com/v2"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _priceUpdates = MutableSharedFlow<PriceUpdate>(
        replay = 1,
        extraBufferCapacity = 64
    )
    val priceUpdates: SharedFlow<PriceUpdate> = _priceUpdates.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var subscribedPairs: List<String> = emptyList()

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val isConnected: Boolean get() = connected.get()

    fun connect(pairs: List<String>) {
        if (connected.get()) {
            Log.d(TAG, "Already connected")
            return
        }

        subscribedPairs = pairs
        shouldReconnect.set(true)
        reconnectAttempts.set(0)
        doConnect()
    }

    fun disconnect() {
        shouldReconnect.set(false)
        connected.set(false)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        Log.i(TAG, "Disconnected")
    }

    private fun doConnect() {
        val request = Request.Builder()
            .url(WS_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                connected.set(true)
                reconnectAttempts.set(0)
                subscribeToTicker(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                connected.set(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                connected.set(false)
                scheduleReconnect()
            }
        })
    }

    private fun subscribeToTicker(ws: WebSocket) {
        if (subscribedPairs.isEmpty()) return

        val params = JSONObject().apply {
            put("channel", "ticker")
            put("symbol", JSONArray(subscribedPairs.map { pairToKrakenWsSymbol(it) }))
        }

        val message = JSONObject().apply {
            put("method", "subscribe")
            put("params", params)
        }

        ws.send(message.toString())
        Log.d(TAG, "Subscribed to ticker for ${subscribedPairs.size} pairs")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Skip heartbeat and system status messages
            val channel = json.optString("channel", "")
            if (channel != "ticker") return

            val data = json.optJSONArray("data") ?: return

            for (i in 0 until data.length()) {
                val ticker = data.getJSONObject(i)
                val symbol = ticker.optString("symbol", "")
                val last = ticker.optDouble("last", Double.NaN)
                val bid = ticker.optDouble("bid", Double.NaN)
                val ask = ticker.optDouble("ask", Double.NaN)
                val volume = ticker.optDouble("volume", 0.0)

                if (symbol.isNotEmpty() && !last.isNaN()) {
                    val pair = krakenWsSymbolToPair(symbol)
                    val update = PriceUpdate(
                        pair = pair,
                        price = last,
                        bid = if (bid.isNaN()) last else bid,
                        ask = if (ask.isNaN()) last else ask,
                        volume = volume,
                        timestamp = System.currentTimeMillis()
                    )
                    _priceUpdates.tryEmit(update)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return

        val attempt = reconnectAttempts.incrementAndGet()
        val delayMs = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 5)),
            MAX_RECONNECT_DELAY_MS
        )

        Log.d(TAG, "Scheduling reconnect attempt $attempt in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            if (shouldReconnect.get() && !connected.get()) {
                Log.i(TAG, "Reconnecting (attempt $attempt)")
                doConnect()
            }
        }
    }

    private fun pairToKrakenWsSymbol(pair: String): String {
        // Convert "BTC/EUR" to "BTC/EUR" (Kraken v2 WS uses this format)
        return pair.uppercase()
    }

    private fun krakenWsSymbolToPair(symbol: String): String {
        // Convert Kraken WS symbol back to our format
        return symbol.uppercase()
    }
}
