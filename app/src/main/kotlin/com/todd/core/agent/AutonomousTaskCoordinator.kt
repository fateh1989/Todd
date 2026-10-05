package com.todd.core.agent

import com.todd.core.model.Task
import com.todd.core.model.TaskStatus
import com.todd.core.state.ToddStateMachine
import com.todd.core.tools.GitHubCredentialStore
import com.todd.data.repository.ToddRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns autonomous coding work outside the Activity/Compose lifecycle.
 *
 * Every new autonomous task gets a durable checkpoint before execution starts.
 * A recovery scheduler can therefore re-enter the same task after process death or reboot.
 */
class AutonomousTaskCoordinator(
    private val repository: ToddRepository,
    private val stateMachine: ToddStateMachine,
    private val githubCredentialStore: GitHubCredentialStore,
    private val codingLoop: AutonomousCodingLoop,
    private val checkpointStore: AutonomousCheckpointStore,
    private val scope: CoroutineScope,
    private val scheduleRecovery: (() -> Unit)? = null
) {
    private val runningTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun start(
        projectId: String,
        title: String,
        goal: String,
        maxIterations: Int = 4
    ): String {
        val taskId = "code-${System.currentTimeMillis()}-${System.nanoTime()}"
        if (!runningTaskIds.add(taskId)) return taskId

        scope.launch {
            try {
                val task = stateMachine.planTask(
                    taskId = taskId,
                    projectId = projectId,
                    title = title,
                    goal = goal,
                    criteria = "Todd must produce a commit and remote Android verification must succeed"
                )
                repository.saveTask(task)

                val project = repository.getProjectById(projectId)
                val repoName = project?.repository
                    ?: if (projectId == "todd-main") "fateh1989/Todd" else null
                val branch = project?.branch?.ifBlank { "main" } ?: "main"

                if (repoName == null) {
                    repository.updateTask(
                        task.copy(
                            status = TaskStatus.BLOCKED,
                            currentStep = "لا يوجد مستودع مرتبط بهذا المشروع.",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return@launch
                }

                val request = AutonomousCodingRequest(
                    taskId = task.id,
                    projectId = projectId,
                    repository = repoName,
                    branch = branch,
                    objective = goal,
                    completionCriteria = task.completionCriteria,
                    maxIterations = maxIterations
                )

                checkpointStore.save(
                    AutonomousCheckpoint(
                        request = request,
                        iteration = 1,
                        headSha = "",
                        phase = AutonomousCheckpointPhase.INSPECTING
                    )
                )
                scheduleRecovery?.invoke()

                execute(task, request, resumed = false)
            } finally {
                runningTaskIds -= taskId
            }
        }

        return taskId
    }

    /**
     * Starts immediate in-process recovery and also leaves a durable scheduler in place.
     */
    fun resumePending() {
        scheduleRecovery?.invoke()
        scope.launch {
            resumePendingAndAwait()
        }
    }

    /**
     * Used by WorkManager. It waits for every checkpoint it can claim in this process.
     * The returned count is the number of checkpoints still durable afterwards.
     */
    suspend fun resumePendingAndAwait(): Int {
        checkpointStore.listPending().forEach { checkpoint ->
            val request = checkpoint.request
            if (!runningTaskIds.add(request.taskId)) return@forEach

            try {
                val task = repository.getTaskById(request.taskId)
                    ?: Task(
                        id = request.taskId,
                        projectId = request.projectId,
                        title = request.objective.take(120),
                        goal = request.objective,
                        userInstructions = request.objective,
                        status = TaskStatus.WAITING,
                        currentStep = "تم العثور على نقطة استئناف محفوظة.",
                        completionCriteria = request.completionCriteria
                    ).also { repository.saveTask(it) }

                execute(task, request, resumed = true)
            } finally {
                runningTaskIds -= request.taskId
            }
        }

        return checkpointStore.listPending().size
    }

    fun isRunning(taskId: String): Boolean = taskId in runningTaskIds

    private suspend fun execute(
        task: Task,
        request: AutonomousCodingRequest,
        resumed: Boolean
    ) {
        if (!githubCredentialStore.hasToken()) {
            repository.updateTask(
                task.copy(
                    status = TaskStatus.BLOCKED,
                    currentStep = if (resumed) {
                        "نقطة الاستئناف محفوظة. أضف تفويض GitHub ثم سيستطيع Todd المتابعة."
                    } else {
                        "أضف تفويض GitHub من الإعدادات حتى يستطيع Todd قراءة وكتابة المستودع."
                    },
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        repository.updateTask(
            task.copy(
                status = TaskStatus.IN_PROGRESS,
                currentStep = if (resumed) {
                    "Todd استعاد نقطة الاستئناف ويكمل من آخر مرحلة محفوظة."
                } else {
                    "Todd يفحص المستودع ويحدد الملفات اللازمة قبل أي تعديل."
                },
                failureCause = null,
                updatedAt = System.currentTimeMillis()
            )
        )

        val result = codingLoop.run(request)

        result.fold(
            onSuccess = { coding ->
                repository.updateTask(
                    task.copy(
                        status = if (coding.success) TaskStatus.VERIFIED else TaskStatus.FAILED,
                        currentStep = if (coding.success) {
                            "اكتمل التعديل البرمجي وتحقق البناء والاختبار عن بُعد."
                        } else {
                            "انتهت دورة البرمجة بدون تحقق ناجح."
                        },
                        lastEvidence = coding.evidenceUrl
                            ?: coding.finalCommitSha
                            ?: task.lastEvidence,
                        failureCause = coding.failureMessage,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            },
            onFailure = { error ->
                val checkpointStillExists = checkpointStore.load(task.id) != null
                repository.updateTask(
                    task.copy(
                        status = if (checkpointStillExists) TaskStatus.WAITING else TaskStatus.FAILED,
                        currentStep = if (checkpointStillExists) {
                            "توقف التنفيذ مؤقتاً، ونقطة الاستئناف محفوظة للمتابعة دون البدء من الصفر."
                        } else {
                            "توقفت دورة البرمجة الذاتية بسبب خطأ فعلي."
                        },
                        failureCause = error.message,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                if (checkpointStillExists) {
                    scheduleRecovery?.invoke()
                }
            }
        )
    }
}
