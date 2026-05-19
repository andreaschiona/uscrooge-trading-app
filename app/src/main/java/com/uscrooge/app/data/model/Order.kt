package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey
    val orderId: String,
    val pair: String,
    val type: OrderType,
    val side: OrderSide,
    val price: Double,
    val amount: Double,              // Volume in base currency
    val cost: Double,                // Total cost in quote currency
    val fee: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val executedAt: Long? = null,
    val signalId: Long? = null
)

enum class OrderType {
    MARKET,
    LIMIT,
    STOP_LOSS,
    TAKE_PROFIT
}

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderStatus {
    PENDING,
    OPEN,
    CLOSED,
    CANCELED,
    EXPIRED,
    FAILED
}
