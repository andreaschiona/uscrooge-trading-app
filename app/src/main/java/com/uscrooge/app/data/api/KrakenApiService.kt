package com.uscrooge.app.data.api

import retrofit2.Response
import retrofit2.http.*

interface KrakenApiService {

    // Public endpoints (no authentication required)

    @GET("0/public/Time")
    suspend fun getServerTime(): Response<KrakenResponse<ServerTime>>

    @GET("0/public/AssetPairs")
    suspend fun getAssetPairs(
        @Query("pair") pair: String? = null
    ): Response<KrakenResponse<Map<String, AssetPairInfo>>>

    @GET("0/public/Ticker")
    suspend fun getTicker(
        @Query("pair") pair: String
    ): Response<KrakenResponse<Map<String, TickerInfo>>>

    @GET("0/public/OHLC")
    suspend fun getOHLC(
        @Query("pair") pair: String,
        @Query("interval") interval: Int = 60,  // in minutes
        @Query("since") since: Long? = null
    ): Response<KrakenResponse<OHLCResponse>>

    @GET("0/public/Depth")
    suspend fun getOrderBook(
        @Query("pair") pair: String,
        @Query("count") count: Int = 100
    ): Response<KrakenResponse<Map<String, OrderBookData>>>

    @GET("0/public/Trades")
    suspend fun getRecentTrades(
        @Query("pair") pair: String,
        @Query("since") since: Long? = null
    ): Response<KrakenResponse<TradesResponse>>

    // Private endpoints (authentication required)

    @POST("0/private/Balance")
    @FormUrlEncoded
    suspend fun getAccountBalance(
        @Field("nonce") nonce: Long
    ): Response<KrakenResponse<Map<String, String>>>

    @POST("0/private/TradeBalance")
    @FormUrlEncoded
    suspend fun getTradeBalance(
        @Field("nonce") nonce: Long,
        @Field("asset") asset: String = "ZEUR"
    ): Response<KrakenResponse<TradeBalance>>

    @POST("0/private/OpenOrders")
    @FormUrlEncoded
    suspend fun getOpenOrders(
        @Field("nonce") nonce: Long,
        @Field("trades") trades: Boolean = false
    ): Response<KrakenResponse<OpenOrdersResponse>>

    @POST("0/private/ClosedOrders")
    @FormUrlEncoded
    suspend fun getClosedOrders(
        @Field("nonce") nonce: Long,
        @Field("trades") trades: Boolean = false,
        @Field("start") start: Long? = null,
        @Field("end") end: Long? = null
    ): Response<KrakenResponse<ClosedOrdersResponse>>

    @POST("0/private/QueryOrders")
    @FormUrlEncoded
    suspend fun queryOrders(
        @Field("nonce") nonce: Long,
        @Field("txid") txid: String,
        @Field("trades") trades: Boolean = false
    ): Response<KrakenResponse<Map<String, OrderInfo>>>

    @POST("0/private/TradesHistory")
    @FormUrlEncoded
    suspend fun getTradesHistory(
        @Field("nonce") nonce: Long,
        @Field("type") type: String = "all",
        @Field("start") start: Long? = null,
        @Field("end") end: Long? = null
    ): Response<KrakenResponse<TradesHistoryResponse>>

    @POST("0/private/OpenPositions")
    @FormUrlEncoded
    suspend fun getOpenPositions(
        @Field("nonce") nonce: Long,
        @Field("docalcs") docalcs: Boolean = true
    ): Response<KrakenResponse<Map<String, PositionInfo>>>

    @POST("0/private/AddOrder")
    @FormUrlEncoded
    suspend fun addOrder(
        @Field("nonce") nonce: Long,
        @Field("ordertype") ordertype: String,      // market, limit, stop-loss, take-profit
        @Field("type") type: String,                // buy or sell
        @Field("volume") volume: String,
        @Field("pair") pair: String,
        @Field("price") price: String? = null,
        @Field("price2") price2: String? = null,    // Secondary price for stop-loss, take-profit
        @Field("leverage") leverage: String? = null,
        @Field("oflags") oflags: String? = null,    // fcib, fciq, nompp, post
        @Field("starttm") starttm: String? = null,
        @Field("expiretm") expiretm: String? = null,
        @Field("validate") validate: Boolean = false
    ): Response<KrakenResponse<AddOrderResponse>>

    @POST("0/private/CancelOrder")
    @FormUrlEncoded
    suspend fun cancelOrder(
        @Field("nonce") nonce: Long,
        @Field("txid") txid: String
    ): Response<KrakenResponse<CancelOrderResponse>>
}

// Response wrapper
data class KrakenResponse<T>(
    val error: List<String>,
    val result: T?
)

// Public API response models
data class ServerTime(
    val unixtime: Long,
    val rfc1123: String
)

data class AssetPairInfo(
    val altname: String,
    val wsname: String?,
    val aclass_base: String,
    val base: String,
    val aclass_quote: String,
    val quote: String,
    val lot: String,
    val pair_decimals: Int,
    val lot_decimals: Int,
    val lot_multiplier: Int,
    val leverage_buy: List<Int>?,
    val leverage_sell: List<Int>?,
    val fees: List<List<Double>>?,
    val fees_maker: List<List<Double>>?,
    val fee_volume_currency: String?,
    val margin_call: Int?,
    val margin_stop: Int?,
    val ordermin: String?
)

data class TickerInfo(
    val a: List<String>,  // ask [price, whole lot volume, lot volume]
    val b: List<String>,  // bid [price, whole lot volume, lot volume]
    val c: List<String>,  // last trade closed [price, lot volume]
    val v: List<String>,  // volume [today, last 24 hours]
    val p: List<String>,  // volume weighted average price [today, last 24 hours]
    val t: List<Int>,     // number of trades [today, last 24 hours]
    val l: List<String>,  // low [today, last 24 hours]
    val h: List<String>,  // high [today, last 24 hours]
    val o: String         // today's opening price
)

data class OHLCResponse(
    val last: Long? = null
) {
    // The actual OHLC data comes as a map key with the pair name
    // and value as List<List<Any>> where each inner list is:
    // [time, open, high, low, close, vwap, volume, count]
}

data class OrderBookData(
    val asks: List<List<String>>,  // [price, volume, timestamp]
    val bids: List<List<String>>   // [price, volume, timestamp]
)

data class TradesResponse(
    val last: String? = null
) {
    // Trades come as map with pair name as key
    // and value as List<List<Any>> [price, volume, time, buy/sell, market/limit, miscellaneous]
}

// Private API response models
data class TradeBalance(
    val eb: String,   // equivalent balance (combined balance of all currencies)
    val tb: String,   // trade balance (combined balance of all equity currencies)
    val m: String,    // margin amount of open positions
    val n: String,    // unrealized net profit/loss of open positions
    val c: String,    // cost basis of open positions
    val v: String,    // current floating valuation of open positions
    val e: String,    // equity = trade balance + unrealized net profit/loss
    val mf: String,   // free margin = equity - initial margin (maximum margin available to open new positions)
    val ml: String?   // margin level = (equity / initial margin) * 100
)

data class OpenOrdersResponse(
    val open: Map<String, OrderInfo>
)

data class ClosedOrdersResponse(
    val closed: Map<String, OrderInfo>,
    val count: Int
)

data class OrderInfo(
    val refid: String?,
    val userref: Int?,
    val status: String,          // pending, open, closed, canceled, expired
    val opentm: Double,
    val starttm: Double?,
    val expiretm: Double?,
    val descr: OrderDescription,
    val vol: String,
    val vol_exec: String,
    val cost: String,
    val fee: String,
    val price: String,
    val stopprice: String?,
    val limitprice: String?,
    val misc: String,
    val oflags: String,
    val trades: List<String>?
)

data class OrderDescription(
    val pair: String,
    val type: String,           // buy or sell
    val ordertype: String,      // market, limit, etc.
    val price: String,
    val price2: String?,
    val leverage: String?,
    val order: String,
    val close: String?
)

data class TradesHistoryResponse(
    val trades: Map<String, TradeInfo>,
    val count: Int
)

data class TradeInfo(
    val ordertxid: String,
    val postxid: String,
    val pair: String,
    val time: Double,
    val type: String,
    val ordertype: String,
    val price: String,
    val cost: String,
    val fee: String,
    val vol: String,
    val margin: String?,
    val misc: String
)

data class PositionInfo(
    val ordertxid: String,
    val posstatus: String,
    val pair: String,
    val time: Double,
    val type: String,
    val ordertype: String,
    val cost: String,
    val fee: String,
    val vol: String,
    val vol_closed: String,
    val margin: String,
    val value: String?,
    val net: String?,
    val terms: String?,
    val rollovertm: String?,
    val misc: String,
    val oflags: String
)

data class AddOrderResponse(
    val descr: OrderDescription,
    val txid: List<String>
)

data class CancelOrderResponse(
    val count: Int,
    val pending: Boolean?
)
