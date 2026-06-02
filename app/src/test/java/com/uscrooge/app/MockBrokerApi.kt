package com.uscrooge.app

import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.BrokerOrderInfo
import com.uscrooge.app.data.api.BrokerPositionInfo
import com.uscrooge.app.data.model.*

class MockBrokerApi(
    override val brokerName: String = "Mock"
) : BrokerApi {

    var tickerResult: Result<Ticker> = Result.failure(Exception("Not mocked"))
    var ohlcResult: Result<List<OHLC>> = Result.failure(Exception("Not mocked"))
    var balanceResult: Result<Map<String, Double>> = Result.failure(Exception("Not mocked"))
    var availableBalanceResult: Result<Double> = Result.failure(Exception("Not mocked"))
    var placeOrderResult: Result<String> = Result.failure(Exception("Not mocked"))
    var cancelOrderResult: Result<Boolean> = Result.failure(Exception("Not mocked"))
    var getOrderResult: Result<BrokerOrderInfo> = Result.failure(Exception("Not mocked"))
    var getOpenOrdersResult: Result<List<BrokerOrderInfo>> = Result.failure(Exception("Not mocked"))
    var getOpenPositionsResult: Result<List<BrokerPositionInfo>> = Result.failure(Exception("Not mocked"))
    var healthResult: Result<BrokerHealth> = Result.success(
        BrokerHealth(
            brokerName = brokerName,
            status = BrokerHealthStatus.ONLINE,
            latencyMs = 50,
            lastError = null,
            lastChecked = System.currentTimeMillis()
        )
    )

    var lastPlaceOrderSymbol: String? = null
    var lastPlaceOrderSide: OrderSide? = null
    var lastPlaceOrderQuantity: Double? = null
    var lastPlaceOrderType: OrderType? = null
    var lastCancelOrderId: String? = null

    override suspend fun getTicker(symbol: String): Result<Ticker> = tickerResult
    override suspend fun getOHLC(symbol: String, interval: Int): Result<List<OHLC>> = ohlcResult
    override suspend fun getAccountBalance(): Result<Map<String, Double>> = balanceResult
    override suspend fun getAvailableBalance(currency: String): Result<Double> = availableBalanceResult

    override suspend fun placeOrder(
        symbol: String,
        side: OrderSide,
        quantity: Double,
        orderType: OrderType,
        limitPrice: Double?,
        stopPrice: Double?,
        takeProfitPrice: Double?,
        notional: Double?,
        validate: Boolean
    ): Result<String> {
        lastPlaceOrderSymbol = symbol
        lastPlaceOrderSide = side
        lastPlaceOrderQuantity = quantity
        lastPlaceOrderType = orderType
        return if (validate) Result.success("validate") else placeOrderResult
    }

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        lastCancelOrderId = orderId
        return cancelOrderResult
    }

    override suspend fun getOrder(orderId: String): Result<BrokerOrderInfo> = getOrderResult
    override suspend fun getOpenOrders(): Result<List<BrokerOrderInfo>> = getOpenOrdersResult
    override suspend fun getOpenPositions(): Result<List<BrokerPositionInfo>> = getOpenPositionsResult

    override suspend fun health(): Result<BrokerHealth> = healthResult

    override fun updateCredentials(apiKey: String, apiSecret: String, timeout: Long) {}
    override fun close() {}

    companion object {
        fun createTicker(
            pair: String = "BTC/EUR",
            price: Double = 50000.0
        ): Ticker = Ticker(
            pair = pair,
            ask = price * 1.001,
            bid = price * 0.999,
            lastTrade = price,
            volume = 100.0,
            volumeWeightedAverage = price,
            numberOfTrades = 1000,
            low = price * 0.98,
            high = price * 1.02,
            opening = price
        )

        fun createOhlcData(
            basePrice: Double = 50000.0,
            count: Int = 100,
            trend: Double = 1.0
        ): List<OHLC> = List(count) { i ->
            OHLC(
                time = (1000000L + i * 60),
                open = basePrice + i * trend,
                high = basePrice + i * trend + 50.0,
                low = basePrice + i * trend - 50.0,
                close = basePrice + i * trend + 10.0,
                vwap = basePrice + i * trend,
                volume = 100.0 + (i % 10) * 10.0,
                count = 100 + i
            )
        }

        fun createBrokerOrderInfo(
            orderId: String = "mock-order-1",
            status: String = "closed",
            price: Double = 50000.0
        ): BrokerOrderInfo = BrokerOrderInfo(
            orderId = orderId,
            symbol = "BTC/EUR",
            side = "buy",
            type = "market",
            status = status,
            quantity = 0.01,
            filledQuantity = 0.01,
            price = price,
            avgFillPrice = price,
            fee = 1.3,
            createdAt = System.currentTimeMillis(),
            executedAt = System.currentTimeMillis()
        )
    }
}
