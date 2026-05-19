package com.uscrooge.app.data.model

data class OHLC(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val vwap: Double,      // Volume Weighted Average Price
    val count: Int         // Number of trades
)

data class Ticker(
    val pair: String,
    val ask: Double,
    val bid: Double,
    val lastTrade: Double,
    val volume: Double,
    val volumeWeightedAverage: Double,
    val numberOfTrades: Int,
    val low: Double,
    val high: Double,
    val opening: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class OrderBook(
    val pair: String,
    val asks: List<OrderBookEntry>,
    val bids: List<OrderBookEntry>,
    val timestamp: Long = System.currentTimeMillis()
)

data class OrderBookEntry(
    val price: Double,
    val volume: Double,
    val timestamp: Long
)
