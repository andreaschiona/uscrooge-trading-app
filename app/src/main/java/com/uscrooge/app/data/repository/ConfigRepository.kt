package com.uscrooge.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.uscrooge.app.data.model.TradingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trading_config",
    corruptionHandler = ReplaceFileCorruptionHandler(
        produceNewData = { emptyPreferences() }
    )
)

class ConfigRepository(private val context: Context) {

    private val gson = Gson()

    companion object {
        private val TRADING_PAIRS = stringPreferencesKey("trading_pairs")
        private val RISK_PER_TRADE = doublePreferencesKey("risk_per_trade")
        private val MAX_OPEN_POSITIONS = intPreferencesKey("max_open_positions")
        private val MAX_DAILY_TRADES = intPreferencesKey("max_daily_trades")
        private val MIN_SIGNAL_STRENGTH = doublePreferencesKey("min_signal_strength")
        private val STRONG_SIGNAL_THRESHOLD = doublePreferencesKey("strong_signal_threshold")
        private val RSI_PERIOD = intPreferencesKey("rsi_period")
        private val RSI_OVERSOLD = doublePreferencesKey("rsi_oversold")
        private val RSI_OVERBOUGHT = doublePreferencesKey("rsi_overbought")
        private val MACD_FAST_PERIOD = intPreferencesKey("macd_fast_period")
        private val MACD_SLOW_PERIOD = intPreferencesKey("macd_slow_period")
        private val MACD_SIGNAL_PERIOD = intPreferencesKey("macd_signal_period")
        private val STOP_LOSS_PERCENT = doublePreferencesKey("stop_loss_percent")
        private val TAKE_PROFIT_PERCENT = doublePreferencesKey("take_profit_percent")
        private val TRAILING_STOP_PERCENT = doublePreferencesKey("trailing_stop_percent")
        private val AUTOMATIC_TRADING = booleanPreferencesKey("automatic_trading")
        private val CHECK_INTERVAL_SECONDS = intPreferencesKey("check_interval_seconds")
        private val MAX_SLIPPAGE_PERCENT = doublePreferencesKey("max_slippage_percent")
        private val KRAKEN_API_KEY = stringPreferencesKey("kraken_api_key")
        private val KRAKEN_API_SECRET = stringPreferencesKey("kraken_api_secret")
        private val API_TIMEOUT = longPreferencesKey("api_timeout")
        private val USE_MULTI_TIMEFRAME = booleanPreferencesKey("use_multi_timeframe")
        private val PRIMARY_TIMEFRAME = intPreferencesKey("primary_timeframe")
        private val SECONDARY_TIMEFRAME = intPreferencesKey("secondary_timeframe")
        private val TERTIARY_TIMEFRAME = intPreferencesKey("tertiary_timeframe")
        private val USE_CANDLESTICK_PATTERNS = booleanPreferencesKey("use_candlestick_patterns")
        private val USE_VOLUME_ANALYSIS = booleanPreferencesKey("use_volume_analysis")
        private val USE_SUPPORT_RESISTANCE = booleanPreferencesKey("use_support_resistance")
        private val MIN_VOLUME_RATIO = doublePreferencesKey("min_volume_ratio")
        private val NOTIFY_ON_SIGNALS = booleanPreferencesKey("notify_on_signals")
        private val NOTIFY_ON_EXECUTION = booleanPreferencesKey("notify_on_execution")
        private val NOTIFY_ON_ERRORS = booleanPreferencesKey("notify_on_errors")
    }

    val configFlow: Flow<TradingConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            TradingConfig(
                tradingPairs = preferences[TRADING_PAIRS]?.split(",") ?: listOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR"),
                riskPerTrade = preferences[RISK_PER_TRADE] ?: 0.25,
                maxOpenPositions = preferences[MAX_OPEN_POSITIONS] ?: 3,
                maxDailyTrades = preferences[MAX_DAILY_TRADES] ?: 5,
                minSignalStrength = preferences[MIN_SIGNAL_STRENGTH] ?: 0.65,
                strongSignalThreshold = preferences[STRONG_SIGNAL_THRESHOLD] ?: 0.80,
                rsiPeriod = preferences[RSI_PERIOD] ?: 14,
                rsiOversold = preferences[RSI_OVERSOLD] ?: 30.0,
                rsiOverbought = preferences[RSI_OVERBOUGHT] ?: 70.0,
                macdFastPeriod = preferences[MACD_FAST_PERIOD] ?: 12,
                macdSlowPeriod = preferences[MACD_SLOW_PERIOD] ?: 26,
                macdSignalPeriod = preferences[MACD_SIGNAL_PERIOD] ?: 9,
                stopLossPercent = preferences[STOP_LOSS_PERCENT] ?: 2.0,
                takeProfitPercent = preferences[TAKE_PROFIT_PERCENT] ?: 4.0,
                trailingStopPercent = preferences[TRAILING_STOP_PERCENT] ?: 1.5,
                automaticTrading = preferences[AUTOMATIC_TRADING] ?: false,
                checkIntervalSeconds = preferences[CHECK_INTERVAL_SECONDS] ?: 300,
                maxSlippagePercent = preferences[MAX_SLIPPAGE_PERCENT] ?: 0.5,
                krakenApiKey = preferences[KRAKEN_API_KEY] ?: "",
                krakenApiSecret = preferences[KRAKEN_API_SECRET] ?: "",
                apiTimeout = preferences[API_TIMEOUT] ?: 30000,
                useMultiTimeframe = preferences[USE_MULTI_TIMEFRAME] ?: true,
                primaryTimeframe = preferences[PRIMARY_TIMEFRAME] ?: 60,
                secondaryTimeframe = preferences[SECONDARY_TIMEFRAME] ?: 240,
                tertiaryTimeframe = preferences[TERTIARY_TIMEFRAME] ?: 1440,
                useCandlestickPatterns = preferences[USE_CANDLESTICK_PATTERNS] ?: true,
                useVolumeAnalysis = preferences[USE_VOLUME_ANALYSIS] ?: true,
                useSupportResistance = preferences[USE_SUPPORT_RESISTANCE] ?: true,
                minVolumeRatio = preferences[MIN_VOLUME_RATIO] ?: 0.8,
                notifyOnSignals = preferences[NOTIFY_ON_SIGNALS] ?: true,
                notifyOnExecution = preferences[NOTIFY_ON_EXECUTION] ?: true,
                notifyOnErrors = preferences[NOTIFY_ON_ERRORS] ?: true
            )
        }

    suspend fun updateConfig(config: TradingConfig) {
        context.dataStore.edit { preferences ->
            preferences[TRADING_PAIRS] = config.tradingPairs.joinToString(",")
            preferences[RISK_PER_TRADE] = config.riskPerTrade
            preferences[MAX_OPEN_POSITIONS] = config.maxOpenPositions
            preferences[MAX_DAILY_TRADES] = config.maxDailyTrades
            preferences[MIN_SIGNAL_STRENGTH] = config.minSignalStrength
            preferences[STRONG_SIGNAL_THRESHOLD] = config.strongSignalThreshold
            preferences[RSI_PERIOD] = config.rsiPeriod
            preferences[RSI_OVERSOLD] = config.rsiOversold
            preferences[RSI_OVERBOUGHT] = config.rsiOverbought
            preferences[MACD_FAST_PERIOD] = config.macdFastPeriod
            preferences[MACD_SLOW_PERIOD] = config.macdSlowPeriod
            preferences[MACD_SIGNAL_PERIOD] = config.macdSignalPeriod
            preferences[STOP_LOSS_PERCENT] = config.stopLossPercent
            preferences[TAKE_PROFIT_PERCENT] = config.takeProfitPercent
            preferences[TRAILING_STOP_PERCENT] = config.trailingStopPercent
            preferences[AUTOMATIC_TRADING] = config.automaticTrading
            preferences[CHECK_INTERVAL_SECONDS] = config.checkIntervalSeconds
            preferences[MAX_SLIPPAGE_PERCENT] = config.maxSlippagePercent
            preferences[KRAKEN_API_KEY] = config.krakenApiKey
            preferences[KRAKEN_API_SECRET] = config.krakenApiSecret
            preferences[API_TIMEOUT] = config.apiTimeout
            preferences[USE_MULTI_TIMEFRAME] = config.useMultiTimeframe
            preferences[PRIMARY_TIMEFRAME] = config.primaryTimeframe
            preferences[SECONDARY_TIMEFRAME] = config.secondaryTimeframe
            preferences[TERTIARY_TIMEFRAME] = config.tertiaryTimeframe
            preferences[USE_CANDLESTICK_PATTERNS] = config.useCandlestickPatterns
            preferences[USE_VOLUME_ANALYSIS] = config.useVolumeAnalysis
            preferences[USE_SUPPORT_RESISTANCE] = config.useSupportResistance
            preferences[MIN_VOLUME_RATIO] = config.minVolumeRatio
            preferences[NOTIFY_ON_SIGNALS] = config.notifyOnSignals
            preferences[NOTIFY_ON_EXECUTION] = config.notifyOnExecution
            preferences[NOTIFY_ON_ERRORS] = config.notifyOnErrors
        }
    }

    suspend fun resetToDefaults() {
        updateConfig(TradingConfig())
    }
}
