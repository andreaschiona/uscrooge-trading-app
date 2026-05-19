package com.uscrooge.app.worker

import android.content.Context
import androidx.work.*
import com.uscrooge.app.UScroogeApplication
import com.uscrooge.app.data.model.SignalStatus
import com.uscrooge.app.notification.NotificationHelper
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class MarketAnalysisWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as UScroogeApplication
            val repository = app.tradingRepository
            val configRepository = app.configRepository
            val orderExecutor = app.orderExecutor
            val notificationHelper = NotificationHelper(applicationContext)

            val config = configRepository.configFlow.first()

            // Analyze all trading pairs
            val signals = repository.analyzeAllPairs(config)

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
