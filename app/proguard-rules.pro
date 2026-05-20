# Add project specific ProGuard rules here.

# Keep generic metadata required by Retrofit/Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class com.uscrooge.app.data.model.** { *; }

# Keep Kraken API DTOs for release parsing
-keep class com.uscrooge.app.data.api.KrakenResponse { *; }
-keep class com.uscrooge.app.data.api.ServerTime { *; }
-keep class com.uscrooge.app.data.api.AssetPairInfo { *; }
-keep class com.uscrooge.app.data.api.TickerInfo { *; }
-keep class com.uscrooge.app.data.api.OHLCResponse { *; }
-keep class com.uscrooge.app.data.api.OrderBookData { *; }
-keep class com.uscrooge.app.data.api.TradesResponse { *; }
-keep class com.uscrooge.app.data.api.TradeBalance { *; }
-keep class com.uscrooge.app.data.api.OpenOrdersResponse { *; }
-keep class com.uscrooge.app.data.api.ClosedOrdersResponse { *; }
-keep class com.uscrooge.app.data.api.OrderInfo { *; }
-keep class com.uscrooge.app.data.api.OrderDescription { *; }
-keep class com.uscrooge.app.data.api.TradesHistoryResponse { *; }
-keep class com.uscrooge.app.data.api.TradeInfo { *; }
-keep class com.uscrooge.app.data.api.PositionInfo { *; }
-keep class com.uscrooge.app.data.api.AddOrderResponse { *; }
-keep class com.uscrooge.app.data.api.CancelOrderResponse { *; }

# Retrofit
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keep interface com.uscrooge.app.data.api.KrakenApiService { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Kotlin suspend support for Retrofit reflection
-keep,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
