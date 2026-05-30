package com.uscrooge.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.executor.CircuitBreaker
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.notification.NotificationHelper
import com.uscrooge.app.strategy.TradingStrategy
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
    private val notificationHelper: NotificationHelper,
    private val tradingStrategy: TradingStrategy,
    private val circuitBreaker: CircuitBreaker,
    private val gitHubIssueReporter: GitHubIssueReporter
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val config = configRepository.configFlow.first()

            // Ensure KrakenApiClient credentials, strategy and executor reflect
            // the current config before any API call. On cold start the
            // BrokerRegistry.start() collector may not have emitted yet, so the
            // singleton client could otherwise be holding empty credentials.
            brokerRegistry.applyConfig(config)

            // --- STEP 1: Monitor exit conditions on open positions ---
            // This runs FIRST regardless of circuit breaker state, because
            // protecting capital takes priority over opening new positions.
            try {
                val closedPositions = orderExecutor.monitorExitConditions(tradingStrategy)
                if (closedPositions.isNotEmpty() && config.notifyOnExecution) {
                    closedPositions.forEach { pos ->
                        notificationHelper.sendErrorNotification(
                            "Position closed: ${pos.pair}",
                            "P/L: ${String.format("%.2f", pos.realizedPnL ?: pos.unrealizedPnL)} EUR"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MarketAnalysisWorker", "Exit monitoring failed: ${e.message}", e)
                reportToGitHub("Exit monitoring failed", e)
            }

            // Sync positions from both brokers
            try {
                if (config.krakenApiKey.isNotBlank() && config.krakenApiSecret.isNotBlank()) {
                    repository.syncOpenPositionsFromKraken(config)
                }
            } catch (e: Exception) {
                Log.w("MarketAnalysisWorker", "Kraken position sync failed: ${e.message}")
                reportToGitHub("Kraken position sync failed", e)
            }
            try {
                if (config.alpacaApiKey.isNotBlank() && config.alpacaApiSecret.isNotBlank()) {
                    repository.syncOpenPositionsFromAlpaca(config)
                }
            } catch (e: Exception) {
                Log.w("MarketAnalysisWorker", "Alpaca position sync failed: ${e.message}")
                reportToGitHub("Alpaca position sync failed", e)
            }

            // --- STEP 2: Validate pending trading signals ---
            try {
                repository.validatePendingSignals(config)
            } catch (e: Exception) {
                Log.w("MarketAnalysisWorker", "Signal validation failed: ${e.message}")
            }

            // --- STEP 3: Check circuit breaker before new analysis/trading ---
            val blocked = circuitBreaker.checkTradingAllowed(config)
            if (blocked != null) {
                Log.w("MarketAnalysisWorker", "Trading blocked: $blocked")
                if (config.notifyOnErrors) {
                    notificationHelper.sendErrorNotification(
                        "Trading halted",
                        blocked
                    )
                }
                // Still update prices even if trading is halted
                orderExecutor.updatePositionPrices()
                return Result.success()
            }

            // --- STEP 4: Analyze all trading pairs ---
            val signals = repository.analyzeAllPairs(config)

            // Notify analysis errors
            val analysisLog = repository.lastAnalysisLog.value
            if (analysisLog != null) {
                if (analysisLog.errorCount > 0) {
                    val errorPairs = analysisLog.entries
                        .filter { !it.isSuccess }
                        .joinToString(", ") { "${it.pair}: ${it.errorMessage}" }
                    if (config.notifyOnErrors) {
                        notificationHelper.sendErrorNotification(
                            "Analysis errors (${analysisLog.errorCount}/${analysisLog.totalCount})",
                            errorPairs
                        )
                    }
                    reportToGitHub(
                        "Analysis failed for ${analysisLog.errorCount}/${analysisLog.totalCount} pairs",
                        RuntimeException(errorPairs)
                    )
                } else {
                    notificationHelper.cancelErrorNotification()
                }
            }

            // Send notifications for new signals
            if (config.notifyOnSignals && signals.isNotEmpty()) {
                signals.forEach { signal ->
                    notificationHelper.sendSignalNotification(signal)
                }
            }

            // --- STEP 5: Execute signals if automatic trading is enabled ---
            if (config.automaticTrading) {
                for (signal in signals) {
                    // Re-check circuit breaker before each trade
                    val tradeBlocked = circuitBreaker.checkTradingAllowed(config)
                    if (tradeBlocked != null) {
                        Log.w("MarketAnalysisWorker", "Stopping execution: $tradeBlocked")
                        break
                    }

                    if (signal.strength >= config.strongSignalThreshold) {
                        try {
                            val result = orderExecutor.executeSignal(signal)
                            if (result.isSuccess && config.notifyOnExecution) {
                                notificationHelper.sendOrderExecutedNotification(
                                    result.getOrNull()!!
                                )
                            } else if (result.isFailure) {
                                val error = result.exceptionOrNull()
                                if (config.notifyOnErrors) {
                                    notificationHelper.sendErrorNotification(
                                        "Failed to execute signal for ${signal.pair}",
                                        error?.message
                                    )
                                }
                                if (error != null) {
                                    reportToGitHub("Signal execution failed for ${signal.pair}", error)
                                }
                            }
                        } catch (e: Exception) {
                            if (config.notifyOnErrors) {
                                notificationHelper.sendErrorNotification(
                                    "Error executing signal",
                                    e.message
                                )
                            }
                            reportToGitHub("Signal execution failed for ${signal.pair}", e)
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
                reportToGitHub("Market analysis failed", e)
            } catch (_: Exception) { }
            Result.retry()
        }
    }

    private suspend fun reportToGitHub(context: String, error: Throwable) {
        if (!gitHubIssueReporter.isConfigured()) return
        val title = "[${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}] $context"
        val body = buildString {
            appendLine("## Error Report")
            appendLine()
            appendLine("- **Context:** $context")
            appendLine("- **Timestamp:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("- **App Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- **Error:** ${error.message ?: "Unknown"}")
            appendLine()
            appendLine("### Stack Trace")
            appendLine("```")
            appendLine(error.stackTraceToString())
            appendLine("```")
        }
        val result = gitHubIssueReporter.reportError(title, body)
        result.onSuccess { issueNumber ->
            notificationHelper.sendErrorNotification(
                "GitHub issue created",
                "Issue #$issueNumber reported successfully"
            )
        }.onFailure { err ->
            notificationHelper.sendErrorNotification(
                "GitHub issue failed",
                err.message ?: "Unknown error creating issue"
            )
        }
    }

    companion object {
        const val WORK_NAME = "market_analysis_work"

        fun schedule(context: Context, intervalMinutes: Long) {
            val work = PeriodicWorkRequestBuilder<MarketAnalysisWorker>(
                intervalMinutes,
                TimeUnit.MINUTES
            )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    1,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    work
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
