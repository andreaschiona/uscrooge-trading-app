package com.uscrooge.app.data.model

data class TradingConfig(
    // Trading pairs
    val tradingPairs: List<String> = listOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR"),

    // Risk management
    val riskPerTrade: Double = 0.25,          // 25% of available balance per trade
    val maxOpenPositions: Int = 3,
    val maxDailyTrades: Int = 5,

    // Signal thresholds
    val minSignalStrength: Double = 0.65,     // Minimum 65% confidence
    val strongSignalThreshold: Double = 0.80,  // 80%+ is strong signal

    // Technical analysis parameters
    val rsiPeriod: Int = 14,
    val rsiOversold: Double = 30.0,
    val rsiOverbought: Double = 70.0,
    val macdFastPeriod: Int = 12,
    val macdSlowPeriod: Int = 26,
    val macdSignalPeriod: Int = 9,

    // Risk management
    val stopLossPercent: Double = 2.0,        // 2% stop loss
    val takeProfitPercent: Double = 4.0,      // 4% take profit (2:1 ratio)
    val trailingStopPercent: Double = 1.5,    // Trailing stop

    // Execution
    val automaticTrading: Boolean = false,     // Require confirmation
    val checkIntervalSeconds: Int = 300,       // Check every 5 minutes
    val maxSlippagePercent: Double = 0.5,     // Max 0.5% slippage
    val useLimitOrders: Boolean = true,        // Use limit orders for non-urgent signals

    // API Configuration - Kraken (Crypto)
    val krakenApiKey: String = "",
    val krakenApiSecret: String = "",
    val apiTimeout: Long = 30000,              // 30 seconds

    // API Configuration - Alpaca (Stocks)
    val alpacaApiKey: String = "",
    val alpacaApiSecret: String = "",
    val alpacaPaperTrading: Boolean = true,
    val stockTradingPairs: List<String> = listOf("AAPL/USD", "MSFT/USD", "GOOGL/USD"),
    val enableStockTrading: Boolean = false,

    // GitHub integration
    val githubToken: String = "",

    // Dynamic symbol discovery limits
    val maxCryptoPairsToScan: Int = 15,          // Max crypto pairs scanned (wishlist + dynamic)
    val maxStockPairsToScan: Int = 20,           // Max stock pairs scanned (wishlist + dynamic)

    // Multi-timeframe analysis
    val useMultiTimeframe: Boolean = true,
    val primaryTimeframe: Int = 60,            // 1 hour
    val secondaryTimeframe: Int = 240,         // 4 hours
    val tertiaryTimeframe: Int = 1440,         // 1 day

    // Advanced features
    val useCandlestickPatterns: Boolean = true,
    val useVolumeAnalysis: Boolean = true,
    val useSupportResistance: Boolean = true,
    val minVolumeRatio: Double = 0.8,          // Min 80% of average volume

    // Notifications
    val notifyOnSignals: Boolean = true,
    val notifyOnExecution: Boolean = true,
    val notifyOnErrors: Boolean = true,

    // Circuit breaker
    val circuitBreakerEnabled: Boolean = true,
    val maxDailyDrawdownPercent: Double = 5.0,    // Halt trading if daily loss > 5%
    val maxConsecutiveFailures: Int = 3,           // Halt after 3 consecutive failures
    val circuitBreakerCooldownMinutes: Int = 60,   // Wait 60 min before resuming

    // Update timestamp
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun validate(): Result<Unit> {
        return when {
            riskPerTrade <= 0 || riskPerTrade > 1.0 ->
                Result.failure(IllegalArgumentException("Risk per trade must be between 0 and 1"))
            minSignalStrength < 0 || minSignalStrength > 1.0 ->
                Result.failure(IllegalArgumentException("Signal strength must be between 0 and 1"))
            stopLossPercent <= 0 ->
                Result.failure(IllegalArgumentException("Stop loss must be positive"))
            takeProfitPercent <= 0 ->
                Result.failure(IllegalArgumentException("Take profit must be positive"))
            tradingPairs.isEmpty() ->
                Result.failure(IllegalArgumentException("At least one trading pair required"))
            checkIntervalSeconds < 60 ->
                Result.failure(IllegalArgumentException("Check interval minimum 60 seconds"))
            else -> Result.success(Unit)
        }
    }

    fun getMaxAmountPerTrade(availableBalance: Double): Double {
        return availableBalance * riskPerTrade
    }
}
