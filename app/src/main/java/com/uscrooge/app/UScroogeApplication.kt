package com.uscrooge.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.uscrooge.app.di.ApplicationScope
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.strategy.TradingStrategy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltAndroidApp
class UScroogeApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var brokerRegistry: BrokerRegistry

    @Inject
    lateinit var tradingStrategy: TradingStrategy

    @Inject
    lateinit var orderExecutor: OrderExecutor

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Start observing config changes so KrakenApiClient credentials,
        // TradingStrategy and OrderExecutor stay in sync with Settings.
        brokerRegistry.start(appScope, tradingStrategy, orderExecutor)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
