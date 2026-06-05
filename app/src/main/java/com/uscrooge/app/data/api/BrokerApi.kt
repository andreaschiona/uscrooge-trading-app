package com.uscrooge.app.data.api

import com.uscrooge.app.data.model.*

/**
 * Abstract interface for broker operations.
 * Implemented by both KrakenApiClient and AlpacaApiClient to provide
 * a unified API for trading, market data, and account management.
 */
interface BrokerApi {
    val brokerName: String

    // Market data
    suspend fun getTicker(symbol: String): Result<Ticker>
    suspend fun getOHLC(symbol: String, interval: Int = 60): Result<List<OHLC>>

    // Account
    suspend fun getAccountBalance(): Result<Map<String, Double>>
    suspend fun getAvailableBalance(currency: String = "USD"): Result<Double>

    // Orders
    suspend fun placeOrder(
        symbol: String,
        side: OrderSide,
        quantity: Double,
        orderType: OrderType = OrderType.MARKET,
        limitPrice: Double? = null,
        stopPrice: Double? = null,
        takeProfitPrice: Double? = null,
        notional: Double? = null,
        validate: Boolean = false
    ): Result<String>

    suspend fun cancelOrder(orderId: String): Result<Boolean>
    suspend fun getOrder(orderId: String): Result<BrokerOrderInfo>
    suspend fun getOpenOrders(): Result<List<BrokerOrderInfo>>

    // Positions
    suspend fun getOpenPositions(): Result<List<BrokerPositionInfo>>

    // Health
    suspend fun health(): Result<BrokerHealth>

    // Utility
    fun updateCredentials(apiKey: String, apiSecret: String, timeout: Long = 30000)
    fun close()
}

/**
 * Unified order info returned by any broker.
 */
data class BrokerOrderInfo(
    val orderId: String,
    val symbol: String,
    val side: String,          // "buy" or "sell"
    val type: String,          // "market", "limit", "stop_loss", "take_profit"
    val status: String,        // "open", "closed", "canceled", "expired", "pending"
    val quantity: Double,
    val filledQuantity: Double,
    val price: Double,         // Limit price or trigger price
    val avgFillPrice: Double,  // Average execution price
    val fee: Double,
    val createdAt: Long,
    val executedAt: Long?
)

/**
 * Unified position info returned by any broker.
 */
data class BrokerPositionInfo(
    val symbol: String,
    val side: String,          // "long" or "short"
    val quantity: Double,
    val avgEntryPrice: Double,
    val currentPrice: Double,
    val marketValue: Double,
    val unrealizedPnL: Double,
    val unrealizedPnLPercent: Double
)
