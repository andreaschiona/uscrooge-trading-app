package com.uscrooge.app

import android.app.Application
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.TradingDatabase
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UScroogeApplication : Application() {

    // Database
    private val database by lazy { TradingDatabase.getDatabase(this) }

    // Repositories
    val configRepository by lazy { ConfigRepository(this) }

    // Technical Analyzer
    private val technicalAnalyzer by lazy { TechnicalAnalyzer() }

    // API Client (lazy initialized with default empty credentials)
    val krakenApiClient by lazy {
        KrakenApiClient(
            apiKey = "",
            apiSecret = "",
            timeout = 30000L
        )
    }

    // Strategy (lazy initialized with default config)
    val tradingStrategy by lazy {
        TradingStrategy(
            config = com.uscrooge.app.data.model.TradingConfig(),
            analyzer = technicalAnalyzer
        )
    }

    // Trading Repository
    val tradingRepository by lazy {
        TradingRepository(
            apiClient = krakenApiClient,
            signalDao = database.signalDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            strategy = tradingStrategy
        )
    }

    // Order Executor
    val orderExecutor by lazy {
        OrderExecutor(
            apiClient = krakenApiClient,
            signalDao = database.signalDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            config = com.uscrooge.app.data.model.TradingConfig()
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Initialization happens lazily when components are first accessed
    }
}
