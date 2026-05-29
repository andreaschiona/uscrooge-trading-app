package com.uscrooge.app

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.di.ApplicationScope
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.TradingStrategy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.StringWriter
import java.io.PrintWriter
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
    lateinit var healthCheckRepository: HealthCheckRepository

    @Inject
    lateinit var gitHubIssueReporter: GitHubIssueReporter

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        installCrashHandler()
        reportPendingCrash()

        brokerRegistry.start(appScope, tradingStrategy, orderExecutor)
        healthCheckRepository.start(appScope)
    }

    private fun installCrashHandler() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashReport(throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashReport(throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()
            val stackTrace = sw.toString()

            val report = buildString {
                appendLine("## Crash Report (Auto-detected)")
                appendLine()
                appendLine("### Device Info")
                appendLine("- **Brand:** ${Build.BRAND}")
                appendLine("- **Model:** ${Build.MODEL}")
                appendLine("- **Android API:** ${Build.VERSION.SDK_INT}")
                appendLine("- **Android Version:** ${Build.VERSION.RELEASE}")
                appendLine("- **App Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("- **Timestamp:** ${System.currentTimeMillis()}")
                appendLine()
                appendLine("### Stack Trace")
                appendLine("```")
                appendLine(stackTrace)
                appendLine("```")
            }

            getSharedPreferences(PREFS_CRASH, MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_CRASH_TITLE, "App crash: ${throwable.message ?: "Unknown error"}")
                .putString(KEY_PENDING_CRASH_BODY, report)
                .putLong(KEY_PENDING_CRASH_TIME, System.currentTimeMillis())
                .apply()

            android.util.Log.e(TAG, "Crash report saved: ${throwable.message}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save crash report", e)
        }
    }

    private fun reportPendingCrash() {
        val prefs = getSharedPreferences(PREFS_CRASH, MODE_PRIVATE)
        val title = prefs.getString(KEY_PENDING_CRASH_TITLE, null) ?: return
        val body = prefs.getString(KEY_PENDING_CRASH_BODY, null) ?: return

        prefs.edit()
            .remove(KEY_PENDING_CRASH_TITLE)
            .remove(KEY_PENDING_CRASH_BODY)
            .remove(KEY_PENDING_CRASH_TIME)
            .apply()

        appScope.launch {
            val result = gitHubIssueReporter.reportError(
                title = title,
                body = body,
                labels = listOf("bug", "auto-reported", "crash")
            )
            result.onFailure { error ->
                android.util.Log.e(TAG, "Failed to report pending crash: ${error.message}")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        private const val TAG = "UScroogeApplication"
        private const val PREFS_CRASH = "crash_reports"
        private const val KEY_PENDING_CRASH_TITLE = "pending_crash_title"
        private const val KEY_PENDING_CRASH_BODY = "pending_crash_body"
        private const val KEY_PENDING_CRASH_TIME = "pending_crash_time"
    }
}
