package com.uscrooge.app.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for Alpaca Trading API v2.
 * Base URL: https://paper-api.alpaca.markets/ (paper) or https://api.alpaca.markets/ (live)
 */
interface AlpacaApiService {

    // Account
    @GET("v2/account")
    suspend fun getAccount(): Response<AlpacaAccount>

    // Orders
    @GET("v2/orders")
    suspend fun getOrders(
        @Query("status") status: String = "open",
        @Query("limit") limit: Int = 50
    ): Response<List<AlpacaOrder>>

    @POST("v2/orders")
    suspend fun placeOrder(@Body order: AlpacaOrderRequest): Response<AlpacaOrder>

    @GET("v2/orders/{orderId}")
    suspend fun getOrder(@Path("orderId") orderId: String): Response<AlpacaOrder>

    @DELETE("v2/orders/{orderId}")
    suspend fun cancelOrder(@Path("orderId") orderId: String): Response<AlpacaOrder>

    // Positions
    @GET("v2/positions")
    suspend fun getPositions(): Response<List<AlpacaPosition>>

    @GET("v2/positions/{symbol}")
    suspend fun getPosition(@Path("symbol") symbol: String): Response<AlpacaPosition>

    // Assets (dynamic ticker discovery)
    @GET("v2/assets")
    suspend fun getAssets(
        @Query("status") status: String = "active",
        @Query("asset_class") assetClass: String = "us_equity",
        @Query("exchange") exchange: String? = null
    ): Response<List<AlpacaAsset>>
    @GET("v2/stocks/{symbol}/bars")
    suspend fun getStockBarsRaw(
        @Path("symbol") symbol: String,
        @Query("timeframe") timeframe: String,
        @Query("start") start: String,
        @Query("limit") limit: Int = 1000,
        @Query("adjustment") adjustment: String = "raw",
        @Query("feed") feed: String = "iex"
    ): Response<ResponseBody>

    @GET("v2/stocks/{symbol}/quotes/latest")
    suspend fun getLatestQuote(
        @Path("symbol") symbol: String,
        @Query("feed") feed: String = "iex"
    ): Response<ResponseBody>

    @GET("v2/stocks/{symbol}/trades/latest")
    suspend fun getLatestTrade(
        @Path("symbol") symbol: String,
        @Query("feed") feed: String = "iex"
    ): Response<ResponseBody>

    // Clock (market hours)
    @GET("v2/clock")
    suspend fun getClock(): Response<AlpacaClock>

    // Calendar
    @GET("v2/calendar")
    suspend fun getCalendar(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null
    ): Response<List<AlpacaCalendarDay>>
}

// Account response
data class AlpacaAccount(
    val id: String,
    val status: String,
    val currency: String,
    val cash: String,
    val portfolio_value: String,
    val pattern_day_trader: Boolean,
    val trading_blocked: Boolean,
    val transfers_blocked: Boolean,
    val account_blocked: Boolean,
    val created_at: String,
    val trade_suspended_by_user: Boolean,
    val multiplier: String,
    val buying_power: String,
    val equity: String,
    val last_equity: String,
    val long_market_value: String,
    val short_market_value: String,
    val initial_margin: String,
    val maintenance_margin: String,
    val last_maintenance_margin: String,
    val sma: String,
    val daytrade_count: Int
)

// Order models
data class AlpacaOrderRequest(
    val symbol: String,
    val qty: Double? = null,
    val notional: Double? = null,
    val side: String,          // "buy" or "sell"
    val type: String,          // "market", "limit", "stop_limit", "stop"
    val time_in_force: String = "day",  // "day", "gtc", "ioc", "opg"
    val limit_price: Double? = null,
    val stop_price: Double? = null,
    val trail_price: Double? = null,
    val trail_percent: Double? = null,
    val extended_hours: Boolean = false,
    val order_class: String? = null,  // "simple", "bracket", "oco", "oto"
    val take_profit: AlpacaTakeProfit? = null,
    val stop_loss: AlpacaStopLoss? = null
)

data class AlpacaTakeProfit(
    val limit_price: Double
)

data class AlpacaStopLoss(
    val limit_price: Double? = null,
    val stop_price: Double
)

data class AlpacaOrder(
    val id: String,
    val client_order_id: String,
    val created_at: String,
    val updated_at: String,
    val submitted_at: String,
    val filled_at: String?,
    val expired_at: String?,
    val canceled_at: String?,
    val failed_at: String?,
    val replaced_at: String?,
    val replaced_by: String?,
    val replaces: String?,
    val asset_id: String,
    val symbol: String,
    val asset_class: String,
    val notional: String?,
    val qty: String?,
    val filled_qty: String,
    val filled_avg_price: String?,
    val order_class: String,
    val order_type: String,
    val type: String,
    val side: String,
    val time_in_force: String,
    val limit_price: String?,
    val stop_price: String?,
    val status: String,
    val extended_hours: Boolean,
    val legs: List<AlpacaOrder>?,
    val trail_percent: String?,
    val trail_price: String?,
    val hwm: String?
)

// Position models
data class AlpacaPosition(
    val asset_id: String,
    val symbol: String,
    val exchange: String,
    val asset_class: String,
    val asset_marginable: Boolean?,
    val avg_entry_price: String,
    val qty: String,
    val side: String,          // "long" or "short"
    val market_value: String,
    val cost_basis: String,
    val unrealized_pl: String,
    val unrealized_plpc: String,
    val unrealized_intraday_pl: String,
    val unrealized_intraday_plpc: String,
    val current_price: String,
    val lastday_price: String,
    val change_today: String,
    val qty_available: String?
)

// Market data models
data class AlpacaBarsResponse(
    val bars: Map<String, List<AlpacaBar>>?,
    val next_page_token: String?
)

data class AlpacaBar(
    val t: String,  // timestamp
    val o: Double,  // open
    val h: Double,  // high
    val l: Double,  // low
    val c: Double,  // close
    val v: Long,    // volume
    val n: Int,     // number of trades
    val vw: Double? // vwap
)

// Clock
data class AlpacaClock(
    val timestamp: String,
    val is_open: Boolean,
    val next_open: String,
    val next_close: String
)

// Calendar
data class AlpacaCalendarDay(
    val date: String,
    val open: String,
    val close: String
)

// Asset
data class AlpacaAsset(
    val id: String,
    val asset_class: String,
    val exchange: String,
    val symbol: String,
    val name: String?,
    val status: String,
    val tradable: Boolean,
    val marginable: Boolean,
    val maintenance_margin_requirement: Int?,
    val shortable: Boolean,
    val easy_to_borrow: Boolean?,
    val fractionable: Boolean,
    val min_order_size: Double?,
    val min_trade_increment: Double?,
    val price_increment: Double?
)
