package com.todd.core.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.todd.ToddApplication
import com.todd.core.ai.AIRequest
import com.todd.core.model.MemoryEntry
import com.todd.core.model.MemoryLayer
import com.todd.core.model.ScheduledTask
import com.todd.core.model.TaskStatus
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ToddTaskScheduler(
    private val context: Context
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun schedule(schedule: ScheduledTask) {
        require(schedule.prompt.isNotBlank()) { "Scheduled prompt must not be blank." }
        schedule.intervalMinutes?.let {
            require(it >= MIN_PERIODIC_MINUTES) {
                "Recurring Todd tasks must be at least $MIN_PERIODIC_MINUTES minutes apart."
            }
        }

        ToddApplication.instance.repository.saveScheduledTask(schedule)
        enqueue(schedule)
    }

    suspend fun pause(scheduleId: String): Boolean {
        val repository = ToddApplication.instance.repository
        val schedule = repository.getScheduledTask(scheduleId) ?: return false
        workManager.cancelUniqueWork(workName(scheduleId))
        repository.updateScheduledTask(
            schedule.copy(
                enabled = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun resume(scheduleId: String): Boolean {
        val repository = ToddApplication.instance.repository
        val schedule = repository.getScheduledTask(scheduleId) ?: return false
        val resumed = schedule.copy(
            enabled = true,
            nextRunAt = schedule.nextRunAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        repository.updateScheduledTask(resumed)
        enqueue(resumed)
        return true
    }

    suspend fun cancel(scheduleId: String): Boolean {
        val repository = ToddApplication.instance.repository
        val schedule = repository.getScheduledTask(scheduleId) ?: return false
        workManager.cancelUniqueWork(workName(scheduleId))
        repository.deleteScheduledTask(schedule)
        return true
    }

    suspend fun reconcile() {
        ToddApplication.instance.repository.getEnabledSchedules().forEach(::enqueue)
    }

    private fun enqueue(schedule: ScheduledTask) {
        if (!schedule.enabled) return

        val delayMs = max(
            0L,
            (schedule.nextRunAt ?: schedule.firstRunAt) - System.currentTimeMillis()
        )
        val data = Data.Builder()
            .putString(ScheduledTaskWorker.KEY_SCHEDULE_ID, schedule.id)
            .build()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val interval = schedule.intervalMinutes
        if (interval != null) {
            val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
                interval,
                TimeUnit.MINUTES
            )
                .setInputData(data)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                workName(schedule.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInputData(data)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            workManager.enqueueUniqueWork(
                workName(schedule.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    companion object {
        const val MIN_PERIODIC_MINUTES = 15L

        private fun workName(scheduleId: String): String =
            "todd-schedule:$scheduleId"
    }
}

class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ToddApplication ?: return Result.failure()
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val repository = app.repository
        val schedule = repository.getScheduledTask(scheduleId) ?: return Result.success()

        if (!schedule.enabled) return Result.success()

        val task = repository.getTaskById(schedule.taskId)
        val context = repository.buildProjectContext(schedule.projectId)
        val startedAt = System.currentTimeMillis()

        task?.let {
            repository.updateTask(
                it.copy(
                    status = TaskStatus.IN_PROGRESS,
                    currentStep = "Todd ينفذ المهمة المجدولة الآن.",
                    failureCause = null,
                    updatedAt = startedAt
                )
            )
        }

        val response = app.textAgent.respond(
            AIRequest(
                prompt = schedule.prompt,
                projectContext = context
            ),
            app.stateMachine.state.value.aiMode
        )

        return response.fold(
            onSuccess = { ai ->
                val now = System.currentTimeMillis()
                val nextRun = schedule.intervalMinutes?.let {
                    now + TimeUnit.MINUTES.toMillis(it)
                }

                repository.saveMemory(
                    MemoryEntry(
                        id = "scheduled-${schedule.id}-$now",
                        projectId = schedule.projectId,
                        layer = MemoryLayer.TASK,
                        key = "scheduled:${schedule.id}:$now",
                        value = ai.text,
                        provenance = "MODEL",
                        isVerified = false,
                        timestamp = now
                    )
                )

                repository.updateScheduledTask(
                    schedule.copy(
                        enabled = schedule.intervalMinutes != null,
                        lastRunAt = now,
                        nextRunAt = nextRun,
                        updatedAt = now
                    )
                )

                task?.let {
                    repository.updateTask(
                        it.copy(
                            status = if (schedule.intervalMinutes == null) {
                                TaskStatus.COMPLETED
                            } else {
                                TaskStatus.WAITING
                            },
                            currentStep = if (schedule.intervalMinutes == null) {
                                "اكتمل تنفيذ المهمة المجدولة وحُفظت النتيجة؛ لم تُوسم كمتحققة دون دليل خارجي."
                            } else {
                                "اكتملت هذه الدورة؛ Todd ينتظر الموعد التالي."
                            },
                            lastEvidence = "scheduled:${schedule.id}:$now",
                            failureCause = null,
                            updatedAt = now
                        )
                    )
                }

                notifyResult(
                    title = task?.title ?: "Todd",
                    body = ai.text
                )
                Result.success()
            },
            onFailure = { error ->
                val now = System.currentTimeMillis()
                task?.let {
                    repository.updateTask(
                        it.copy(
                            status = TaskStatus.WAITING,
                            currentStep = "تعذر تنفيذ الدورة المجدولة؛ سيعيد Todd المحاولة.",
                            failureCause = error.message,
                            updatedAt = now
                        )
                    )
                }
                Result.retry()
            }
        )
    }

    private fun notifyResult(title: String, body: String) {
        val manager = applicationContext
            .getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Todd Scheduled Work",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(800)))
            .setAutoCancel(true)
            .build()

        runCatching {
            manager.notify(scheduleNotificationId(title), notification)
        }
    }

    private fun scheduleNotificationId(value: String): Int =
        10_000 + (value.hashCode() and 0x0FFF)

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        private const val CHANNEL_ID = "todd_scheduled_work"
    }
}
