package com.uscrooge.app.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface CoinGeckoApiService {

    @GET("coins/markets")
    suspend fun getMarkets(
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("order") order: String = "volume_desc",
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
        @Query("sparkline") sparkline: Boolean = false,
        @Query("price_change_percentage") priceChange: String = "1h,24h,7d"
    ): List<CoinGeckoMarket>

    @GET("coins/{id}/ohlc")
    suspend fun getOHLC(
        @Path("id") coinId: String,
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("days") days: Int = 7
    ): List<List<Double>>

    @GET("simple/price")
    suspend fun getSimplePrice(
        @Query("ids") ids: String,
        @Query("vs_currencies") vsCurrencies: String = "usd",
        @Query("include_24hr_vol") includeVolume: Boolean = true,
        @Query("include_24hr_change") includeChange: Boolean = true
    ): Map<String, Map<String, Double>>
}

data class CoinGeckoMarket(
    val id: String,
    val symbol: String,
    val name: String,
    val image: String? = null,
    val current_price: Double? = null,
    val market_cap: Double? = null,
    val market_cap_rank: Int? = null,
    val total_volume: Double? = null,
    val price_change_percentage_1h_in_currency: Double? = null,
    val price_change_percentage_24h: Double? = null,
    val price_change_percentage_7d_in_currency: Double? = null,
    val circulating_supply: Double? = null,
    val total_supply: Double? = null,
    val ath: Double? = null,
    val ath_change_percentage: Double? = null
)
