package com.uscrooge.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import android.util.Log
import java.util.concurrent.TimeUnit

@HiltWorker
class MarketAnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TradingRepository,
    private val configRepository: ConfigRepository,
    private val brokerRegistry: BrokerRegistry,
    private val orderExecutor: OrderExecutor,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val config = configRepository.configFlow.first()

            // Ensure KrakenApiClient credentials, strategy and executor reflect
            // the current config before any API call. On cold start the
            // BrokerRegistry.start() collector may not have emitted yet, so the
            // singleton client could otherwise be holding empty credentials.
            brokerRegistry.applyConfig(config)

            // Analyze all trading pairs
            val signals = repository.analyzeAllPairs(config)

            // Notify analysis errors
            val analysisLog = repository.lastAnalysisLog.value
            if (config.notifyOnErrors && analysisLog != null && analysisLog.errorCount > 0) {
                val errorPairs = analysisLog.entries
                    .filter { !it.isSuccess }
                    .joinToString(", ") { "${it.pair}: ${it.errorMessage}" }
                notificationHelper.sendErrorNotification(
                    "Analysis errors (${analysisLog.errorCount}/${analysisLog.totalCount})",
                    errorPairs
                )
            }

            // Send notifications for new signals
            if (config.notifyOnSignals && signals.isNotEmpty()) {
                signals.forEach { signal ->
                    notificationHelper.sendSignalNotification(signal)
                }
            }

            // If automatic trading is enabled, execute signals
            if (config.automaticTrading) {
                for (signal in signals) {
                    if (signal.strength >= config.strongSignalThreshold) {
                        try {
                            val result = orderExecutor.executeSignal(signal)
                            if (result.isSuccess && config.notifyOnExecution) {
                                notificationHelper.sendOrderExecutedNotification(
                                    result.getOrNull()!!
                                )
                            } else if (result.isFailure && config.notifyOnErrors) {
                                notificationHelper.sendErrorNotification(
                                    "Failed to execute signal for ${signal.pair}",
                                    result.exceptionOrNull()?.message
                                )
                            }
                        } catch (e: Exception) {
                            if (config.notifyOnErrors) {
                                notificationHelper.sendErrorNotification(
                                    "Error executing signal",
                                    e.message
                                )
                            }
                        }
                    }
                }
            }

            // Update position prices
            orderExecutor.updatePositionPrices()

            // Cleanup old data
            repository.cleanupOldData()

            Result.success()
        } catch (e: Exception) {
            Log.e("MarketAnalysisWorker", "doWork failed: ${e.message}", e)
            try {
                val config = configRepository.configFlow.first()
                if (config.notifyOnErrors) {
                    notificationHelper.sendErrorNotification(
                        "Market analysis failed",
                        e.message ?: "Unknown error"
                    )
                }
            } catch (_: Exception) { }
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "market_analysis_work"

        fun schedule(context: Context, intervalMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val work = PeriodicWorkRequestBuilder<MarketAnalysisWorker>(
                intervalMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    1,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    work
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
