package com.uscrooge.app.worker

import android.content.Context
import android.util.Log
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
import com.uscrooge.app.notification.NotificationHelper
import com.uscrooge.app.update.UpdateChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val configRepository: ConfigRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val config = configRepository.configFlow.first()
            val now = System.currentTimeMillis()
            val intervalMs = config.updateCheckIntervalHours * 3600_000L
            val timeSinceLastCheck = now - config.lastUpdateCheckEpoch

            if (timeSinceLastCheck < intervalMs && config.lastUpdateCheckEpoch > 0L) {
                return Result.success()
            }

            val result = UpdateChecker().checkForUpdate()
            result.onSuccess { checkResult ->
                configRepository.updateConfig(
                    config.copy(
                        lastUpdateCheckEpoch = now,
                        lastAvailableVersion = checkResult.latestVersion,
                        lastDownloadUrl = checkResult.downloadUrl,
                        lastReleaseNotes = checkResult.releaseNotes
                    )
                )
                if (checkResult.isUpdateAvailable) {
                    notificationHelper.sendUpdateNotification(
                        latestVersion = checkResult.latestVersion,
                        downloadUrl = checkResult.downloadUrl,
                        releaseNotes = checkResult.releaseNotes
                    )
                }
            }.onFailure { error ->
                Log.w("UpdateCheckWorker", "Check failed: ${error.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("UpdateCheckWorker", "doWork failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "app_update_check_work"

        fun schedule(context: Context, intervalHours: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                intervalHours,
                TimeUnit.HOURS
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
                    ExistingPeriodicWorkPolicy.UPDATE,
                    work
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
