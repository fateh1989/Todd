package com.todd.service.recovery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.todd.ToddApplication
import java.util.concurrent.TimeUnit

class AutonomousRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val app = applicationContext as? ToddApplication
            ?: return Result.failure()

        val remaining = app.autonomousTaskCoordinator.resumePendingAndAwait()
        return if (remaining == 0) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Todd Autonomous Work",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Todd continues saved coding and verification tasks."
            }
            applicationContext
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Todd")
            .setContentText("Todd يكمل مهمة محفوظة ويتحقق من نتيجتها")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "todd_autonomous_recovery"
        private const val NOTIFICATION_ID = 1004
    }
}

object AutonomousRecoveryScheduler {
    private const val UNIQUE_WORK_NAME = "todd-autonomous-recovery"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<AutonomousRecoveryWorker>()
            .setConstraints(constraints)
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
